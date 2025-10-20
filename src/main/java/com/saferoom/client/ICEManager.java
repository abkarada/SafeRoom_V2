package com.saferoom.client;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.UDPHoleGrpc;
import io.grpc.ManagedChannel;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeEvent;
import java.util.concurrent.*;

public class ICEManager {
    
    private Agent iceAgent;
    private IceMediaStream mediaStream;
    private Component component;
    
    private String localUsername;
    private String remoteUsername;
    private String sessionId;
    
    private ManagedChannel grpcChannel;
    private UDPHoleGrpc.UDPHoleBlockingStub stub;
    
    private ScheduledExecutorService pollExecutor;
    private int lastReceivedCandidateIndex = 0;
    
    private boolean isGatheringComplete = false;
    private boolean isConnected = false;
    
    private CandidatePair selectedPair;
    
    public ICEManager(String localUsername, String remoteUsername, ManagedChannel grpcChannel) {
        this.localUsername = localUsername;
        this.remoteUsername = remoteUsername;
        this.grpcChannel = grpcChannel;
        this.stub = UDPHoleGrpc.newBlockingStub(grpcChannel);
    }
    
    /**
     * P2P bağlantısını başlat
     * 1. Server'a P2P session oluşturma isteği gönder
     * 2. ICE Agent'ı başlat
     * 3. Candidate gathering'e başla (trickle mode)
     */
    public void initiateConnection(String stunServer, int stunPort) throws Exception {
        System.out.println("Initiating P2P connection to " + remoteUsername);
        
        // 1. Server'a P2P session oluşturma isteği
        SafeRoomProto.P2PInitRequest initRequest = SafeRoomProto.P2PInitRequest.newBuilder()
            .setFromUser(localUsername)
            .setToUser(remoteUsername)
            .build();
        
        SafeRoomProto.P2PInitResponse initResponse = stub.initiateP2PConnection(initRequest);
        
        if (!initResponse.getSuccess()) {
            throw new Exception("P2P init failed: " + initResponse.getMessage());
        }
        
        this.sessionId = initResponse.getSessionId();
        System.out.println("P2P session created: " + sessionId);
        
        // 2. ICE Agent'ı başlat
        initializeICEAgent(stunServer, stunPort);
        
        // 3. Remote candidate polling başlat
        startRemoteCandidatePolling();
    }
    
    /**
     * ICE Agent'ı başlat ve candidate gathering'e başla
     */
    private void initializeICEAgent(String stunServer, int stunPort) throws Exception {
        System.out.println("Initializing ICE Agent");
        
        // ICE Agent oluştur
        iceAgent = new Agent();
        
        // STUN harvester ekle
        StunCandidateHarvester stunHarvester = new StunCandidateHarvester(
            new TransportAddress(stunServer, stunPort, Transport.UDP)
        );
        iceAgent.addCandidateHarvester(stunHarvester);
        
        // Media stream oluştur
        mediaStream = iceAgent.createMediaStream("data");
        
        // Component ekle
        component = iceAgent.createComponent(
            mediaStream,
            0, // preferredPort (0 = random)
            49152, // minPort
            65535, // maxPort
            KeepAliveStrategy.ALL_SUCCEEDED
        );
        
        mediaStream.addPairChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (evt.getPropertyName().equals(IceMediaStream.PROPERTY_PAIR_STATE_CHANGED)) {
                    CandidatePair pair = (CandidatePair) evt.getNewValue();
                    if (pair.getState() == CandidatePairState.SUCCEEDED) {
                        System.out.println("Candidate pair succeeded: " + pair);
                        selectedPair = pair;
                        isConnected = true;
                    }
                }
            }
        });
        
        System.out.println("ICE Agent initialized");
        
        // Candidate gathering başlat
        startCandidateGathering();
    }
    
    /**
     * Local candidate gathering'i başlat (Trickle mode)
     */
    private void startCandidateGathering() {
        System.out.println("Starting candidate gathering (trickle mode)");
        
        // Tüm candidate'leri topla ve hemen gönder
        new Thread(() -> {
            try {
                // Kısa bekle (harvester'ların başlaması için)
                Thread.sleep(50);
                
                // Tüm local candidate'leri topla ve gönder
                for (LocalCandidate candidate : component.getLocalCandidates()) {
                    sendCandidateToServer(candidate);
                }
                
                // Gathering complete işaretle
                markGatheringComplete();
                
            } catch (Exception e) {
                System.err.println("Candidate gathering error: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * Local candidate'i server'a gönder (Trickle)
     */
    private void sendCandidateToServer(LocalCandidate candidate) {
        try {
            TransportAddress addr = candidate.getTransportAddress();
            
            SafeRoomProto.ICECandidate.Builder candidateBuilder = SafeRoomProto.ICECandidate.newBuilder()
                .setFoundation(candidate.getFoundation())
                .setPriority((int)candidate.getPriority())
                .setIp(addr.getHostAddress())
                .setPort(addr.getPort())
                .setType(candidate.getType().toString());
            
            if (candidate.getRelatedAddress() != null) {
                candidateBuilder.setRelatedAddress(candidate.getRelatedAddress().getHostAddress());
                candidateBuilder.setRelatedPort(candidate.getRelatedAddress().getPort());
            }
            
            SafeRoomProto.ICECandidateTrickle trickle = SafeRoomProto.ICECandidateTrickle.newBuilder()
                .setSessionId(sessionId)
                .setFromUser(localUsername)
                .setCandidate(candidateBuilder.build())
                .build();
            
            SafeRoomProto.Status response = stub.sendICECandidate(trickle);
            
            System.out.println("Sent candidate: " + addr + " (" + candidate.getType() + ") - " + response.getMessage());
            
        } catch (Exception e) {
            System.err.println("Error sending candidate: " + e.getMessage());
        }
    }
    
    /**
     * Gathering tamamlandı sinyalini gönder
     */
    private void markGatheringComplete() {
        try {
            SafeRoomProto.ICECompleteRequest request = SafeRoomProto.ICECompleteRequest.newBuilder()
                .setSessionId(sessionId)
                .setUsername(localUsername)
                .build();
            
            stub.iCEGatheringComplete(request);
            isGatheringComplete = true;
            
            System.out.println("ICE gathering complete signal sent");
            
        } catch (Exception e) {
            System.err.println("Error marking gathering complete: " + e.getMessage());
        }
    }
    
    /**
     * Remote candidate polling başlat
     */
    private void startRemoteCandidatePolling() {
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        
        pollExecutor.scheduleAtFixedRate(() -> {
            try {
                SafeRoomProto.ICEPollRequest pollRequest = SafeRoomProto.ICEPollRequest.newBuilder()
                    .setSessionId(sessionId)
                    .setUsername(localUsername)
                    .setLastCandidateIndex(lastReceivedCandidateIndex)
                    .build();
                
                SafeRoomProto.ICEPollResponse pollResponse = stub.pollICECandidates(pollRequest);
                
                if (pollResponse.getCandidatesCount() > 0) {
                    System.out.println("Received " + pollResponse.getCandidatesCount() + " remote candidates");
                    
                    for (SafeRoomProto.ICECandidate protoCandidate : pollResponse.getCandidatesList()) {
                        addRemoteCandidate(protoCandidate);
                        lastReceivedCandidateIndex++;
                    }
                }
                
                // Karşı taraf gathering'i tamamladıysa ve bizim de tamamladıysak
                if (pollResponse.getGatheringComplete() && isGatheringComplete && !isConnected) {
                    startConnectivityCheck();
                    pollExecutor.shutdown();
                }
                
            } catch (Exception e) {
                System.err.println("Polling error: " + e.getMessage());
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Remote candidate ekle
     */
    private void addRemoteCandidate(SafeRoomProto.ICECandidate protoCandidate) {
        try {
            TransportAddress addr = new TransportAddress(
                protoCandidate.getIp(),
                protoCandidate.getPort(),
                Transport.UDP
            );
            
            CandidateType type = CandidateType.parse(protoCandidate.getType());
            
            RemoteCandidate remoteCandidate = new RemoteCandidate(
                addr,
                component,
                type,
                protoCandidate.getFoundation(),
                protoCandidate.getPriority(),
                null
            );
            
            component.addRemoteCandidate(remoteCandidate);
            
            System.out.println("Added remote candidate: " + addr + " (" + type + ")");
            
        } catch (Exception e) {
            System.err.println("Error adding remote candidate: " + e.getMessage());
        }
    }
    
    /**
     * ICE connectivity check başlat
     */
    private void startConnectivityCheck() {
        try {
            System.out.println("Starting ICE connectivity checks");
            
            iceAgent.startConnectivityEstablishment();
            
            // Wait for connection (max 30 seconds)
            long startTime = System.currentTimeMillis();
            while (!isConnected && (System.currentTimeMillis() - startTime) < 30000) {
                if (iceAgent.getState() == IceProcessingState.FAILED) {
                    throw new Exception("ICE connectivity check failed");
                }
                Thread.sleep(100);
            }
            
            if (isConnected) {
                System.out.println("P2P connection established!");
                System.out.println("Selected pair:");
                System.out.println("  Local:  " + selectedPair.getLocalCandidate().getTransportAddress());
                System.out.println("  Remote: " + selectedPair.getRemoteCandidate().getTransportAddress());
            } else {
                throw new Exception("Connection timeout");
            }
            
        } catch (Exception e) {
            System.err.println("Connectivity check error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * P2P bağlantısını kapat
     */
    public void close() {
        try {
            if (pollExecutor != null && !pollExecutor.isShutdown()) {
                pollExecutor.shutdown();
            }
            
            if (sessionId != null) {
                SafeRoomProto.P2PTerminateRequest request = SafeRoomProto.P2PTerminateRequest.newBuilder()
                    .setSessionId(sessionId)
                    .setUsername(localUsername)
                    .build();
                
                stub.terminateP2PSession(request);
            }
            
            if (iceAgent != null) {
                iceAgent.free();
            }
            
            System.out.println("ICE Manager closed");
            
        } catch (Exception e) {
            System.err.println("Error closing ICE Manager: " + e.getMessage());
        }
    }
    
    /**
     * Bağlantı durumunu kontrol et
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * Selected candidate pair'i al
     */
    public CandidatePair getSelectedPair() {
        return selectedPair;
    }
}
