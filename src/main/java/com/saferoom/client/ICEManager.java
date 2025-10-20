package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.UDPHoleGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidatePairState;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.KeepAliveStrategy;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

/**
 * Controls ICE gathering and connectivity establishment for a single peer-to-peer session.
 */
public class ICEManager {

    public static class TurnConfig {
        private final String server;
        private final int port;
        private final String username;
        private final String password;

        public TurnConfig(String server, int port, String username, String password) {
            this.server = Objects.requireNonNull(server, "server");
            this.port = port;
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password");
        }

        public String getServer() {
            return server;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public static TurnConfig fromEnvironment() {
            String server = System.getenv("TURN_SERVER");
            String portStr = System.getenv("TURN_PORT");
            String username = System.getenv("TURN_USERNAME");
            String password = System.getenv("TURN_PASSWORD");

            if (server == null || server.isBlank() ||
                portStr == null || portStr.isBlank() ||
                username == null || username.isBlank() ||
                password == null || password.isBlank()) {
                return null;
            }

            try {
                int port = Integer.parseInt(portStr);
                return new TurnConfig(server, port, username, password);
            } catch (NumberFormatException ex) {
                System.err.println("Invalid TURN_PORT value: " + portStr);
                return null;
            }
        }
    }

    private volatile Agent iceAgent;
    private volatile IceMediaStream mediaStream;
    private volatile Component component;

    private final String localUsername;
    private final String remoteUsername;
    private String sessionId;

    private final ManagedChannel grpcChannel;
    private final UDPHoleGrpc.UDPHoleBlockingStub blockingStub;
    private final UDPHoleGrpc.UDPHoleStub asyncStub;

    private final Object streamLock = new Object();
    private StreamObserver<SafeRoomProto.ICEStreamMessage> requestObserver;

    private final AtomicBoolean gatheringComplete = new AtomicBoolean(false);
    private final AtomicBoolean remoteGatheringComplete = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connectivityStarted = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    private final AtomicInteger retryCounter = new AtomicInteger(0);
    private static final int MAX_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_MS = 1000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ice-retry-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ice-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile CandidatePair selectedPair;
    private final Set<String> sentCandidateKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private String stunServer;
    private int stunPort;
    private TurnConfig turnConfig;

    public ICEManager(String localUsername, String remoteUsername, ManagedChannel grpcChannel) {
        this.localUsername = localUsername;
        this.remoteUsername = remoteUsername;
        this.grpcChannel = grpcChannel;
        this.blockingStub = UDPHoleGrpc.newBlockingStub(grpcChannel);
        this.asyncStub = UDPHoleGrpc.newStub(grpcChannel);
    }

    /**
     * Initiates the P2P session and starts ICE gathering.
     */
    public void initiateConnection(String stunServer, int stunPort, TurnConfig turnConfig) throws Exception {
        System.out.println("Initiating P2P connection to " + remoteUsername);

        this.stunServer = stunServer;
        this.stunPort = stunPort;
        this.turnConfig = turnConfig;

        SafeRoomProto.P2PInitRequest initRequest = SafeRoomProto.P2PInitRequest.newBuilder()
            .setFromUser(localUsername)
            .setToUser(remoteUsername)
            .build();

        SafeRoomProto.P2PInitResponse initResponse = blockingStub.initiateP2PConnection(initRequest);
        if (!initResponse.getSuccess()) {
            throw new Exception("P2P init failed: " + initResponse.getMessage());
        }

        this.sessionId = initResponse.getSessionId();
        this.closed = false;
        this.retryCounter.set(0);
        System.out.println("P2P session created: " + sessionId);

        openIceStream();
        initializeICEAgent();
        startCandidateGathering();
    }

    private void openIceStream() {
        StreamObserver<SafeRoomProto.ICEStreamMessage> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(SafeRoomProto.ICEStreamMessage message) {
                if (!sessionId.equals(message.getSessionId())) {
                    return;
                }

                if (message.hasCandidate()) {
                    addRemoteCandidate(message.getCandidate());
                } else if (message.hasGathering()) {
                    if (message.getGathering().getComplete()) {
                        remoteGatheringComplete.set(true);
                        maybeStartConnectivityCheck();
                    }
                } else if (message.hasRestart()) {
                    handleRemoteRestart(message.getRestart());
                }
            }

            @Override
            public void onError(Throwable t) {
                if (closed) {
                    return;
                }
                System.err.println("ICE stream error: " + t.getMessage());
                scheduleStreamReconnect();
            }

            @Override
            public void onCompleted() {
                if (closed) {
                    return;
                }
                System.out.println("ICE stream completed, reopening");
                scheduleStreamReconnect();
            }
        };

        synchronized (streamLock) {
            if (requestObserver != null) {
                requestObserver.onCompleted();
            }
            requestObserver = asyncStub.streamICE(responseObserver);
        }

        // register the stream immediately so the server can push buffered state
        SafeRoomProto.ICEStreamMessage registerMessage = SafeRoomProto.ICEStreamMessage.newBuilder()
            .setSessionId(sessionId)
            .setFromUser(localUsername)
            .setGathering(SafeRoomProto.ICEGatheringStatus.newBuilder().setComplete(false).build())
            .build();
        sendStreamMessage(registerMessage);
    }

    private void scheduleStreamReconnect() {
        scheduler.schedule(() -> {
            if (!closed) {
                System.out.println("Re-opening ICE stream for session " + sessionId);
                openIceStream();
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void initializeICEAgent() throws Exception {
        cleanupAgent();

        System.out.println("Initializing ICE Agent");
        Agent agent = new Agent();

        StunCandidateHarvester stunHarvester = new StunCandidateHarvester(
            new TransportAddress(stunServer, stunPort, Transport.UDP)
        );
        agent.addCandidateHarvester(stunHarvester);

        addIpv6Harvesters(agent);

        StunCandidateHarvester tcpHarvester = new StunCandidateHarvester(
            new TransportAddress(stunServer, stunPort, Transport.TCP)
        );
        agent.addCandidateHarvester(tcpHarvester);

        if (turnConfig != null) {
            TransportAddress turnAddress = new TransportAddress(turnConfig.getServer(), turnConfig.getPort(), Transport.UDP);
            LongTermCredential credential = new LongTermCredential(turnConfig.getUsername(), turnConfig.getPassword());
            TurnCandidateHarvester turnHarvester = new TurnCandidateHarvester(turnAddress, credential);
            agent.addCandidateHarvester(turnHarvester);
            System.out.println("TURN support enabled for " + turnConfig.getServer() + ":" + turnConfig.getPort());
        }

        mediaStream = agent.createMediaStream("data");
        component = agent.createComponent(
            mediaStream,
            0,
            49152,
            65535,
            KeepAliveStrategy.ALL_SUCCEEDED
        );

        mediaStream.addPairChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (!IceMediaStream.PROPERTY_PAIR_STATE_CHANGED.equals(evt.getPropertyName())) {
                    return;
                }
                CandidatePair pair = (CandidatePair) evt.getNewValue();
                if (pair != null && pair.getState() == CandidatePairState.SUCCEEDED) {
                    selectedPair = pair;
                    connected.set(true);
                    System.out.println("Candidate pair succeeded: " + pair);
                }
            }
        });

        iceAgent = agent;
        sentCandidateKeys.clear();
        gatheringComplete.set(false);
        remoteGatheringComplete.set(false);
        connectivityStarted.set(false);
        connected.set(false);
        selectedPair = null;

        System.out.println("ICE Agent initialized");
    }

    private void cleanupAgent() {
        if (iceAgent != null) {
            try {
                iceAgent.free();
            } catch (Exception ignored) {
            }
        }
    }

    private void startCandidateGathering() {
        workerExecutor.submit(() -> {
            try {
                if (component == null) {
                    throw new IllegalStateException("ICE component not initialized");
                }

                long lastNewCandidateTime = System.currentTimeMillis();
                while (!closed) {
                    boolean newCandidateDispatched = false;
                    for (LocalCandidate candidate : component.getLocalCandidates()) {
                        if (sendCandidate(candidate)) {
                            newCandidateDispatched = true;
                            lastNewCandidateTime = System.currentTimeMillis();
                        }
                    }

                    if (newCandidateDispatched) {
                        continue;
                    }

                    long silence = System.currentTimeMillis() - lastNewCandidateTime;
                    if (silence > 3000) {
                        break;
                    }
                    Thread.sleep(100);
                }

                gatheringComplete.set(true);
                sendGatheringComplete();
                maybeStartConnectivityCheck();
            } catch (Exception e) {
                System.err.println("Candidate gathering error: " + e.getMessage());
            }
        });
    }

    private boolean sendCandidate(LocalCandidate candidate) {
        try {
            TransportAddress addr = candidate.getTransportAddress();
            if (addr == null) {
                return false;
            }

            String candidateKey = candidate.getFoundation() + "|" + addr.getHostAddress() + "|" + addr.getPort()
                + "|" + candidate.getTransport();
            if (!sentCandidateKeys.add(candidateKey)) {
                return false;
            }

            long priority = candidate.getPriority();
            if (addr.isIPv6()) {
                priority += 20;
            }

            String mediaType = candidate.getParentComponent() != null &&
                candidate.getParentComponent().getParentStream() != null
                ? candidate.getParentComponent().getParentStream().getName()
                : "data";

            SafeRoomProto.ICECandidate.Builder builder = SafeRoomProto.ICECandidate.newBuilder()
                .setFoundation(candidate.getFoundation())
                .setComponent(String.valueOf(candidate.getParentComponent().getComponentID()))
                .setProtocol(candidate.getTransport().toString())
                .setIp(addr.getHostAddress())
                .setPort(addr.getPort())
                .setPriority(priority)
                .setType(candidate.getType().toString())
                .setMediaType(mediaType);

            SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
                .setSessionId(sessionId)
                .setFromUser(localUsername)
                .setCandidate(builder.build())
                .build();

            sendStreamMessage(message);
            String ipVersion = addr.isIPv6() ? "IPv6" : "IPv4";
            System.out.println("Sent candidate: " + addr + " (" + candidate.getType() + ", " + ipVersion + ")");
            return true;
        } catch (Exception e) {
            System.err.println("Error sending candidate: " + e.getMessage());
            return false;
        }
    }

    private void sendGatheringComplete() {
        SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
            .setSessionId(sessionId)
            .setFromUser(localUsername)
            .setGathering(SafeRoomProto.ICEGatheringStatus.newBuilder().setComplete(true).build())
            .build();
        sendStreamMessage(message);
        System.out.println("ICE gathering complete signal sent");
    }

    private void sendStreamMessage(SafeRoomProto.ICEStreamMessage message) {
        StreamObserver<SafeRoomProto.ICEStreamMessage> observer;
        synchronized (streamLock) {
            observer = requestObserver;
        }
        if (observer == null) {
            System.err.println("ICE stream observer not ready; dropping message");
            return;
        }
        synchronized (observer) {
            try {
                observer.onNext(message);
            } catch (RuntimeException e) {
                System.err.println("Failed to push ICE message: " + e.getMessage());
            }
        }
    }

    private void addRemoteCandidate(SafeRoomProto.ICECandidate protoCandidate) {
        if (component == null) {
            return;
        }
        try {
            Transport transport = parseTransport(protoCandidate.getProtocol());
            TransportAddress addr = buildTransportAddress(protoCandidate.getIp(), protoCandidate.getPort(), transport);
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
            String ipVersion = addr.isIPv6() ? "IPv6" : "IPv4";
            System.out.println("Added remote candidate: " + addr + " (" + type + ", " + ipVersion + ")");
        } catch (Exception e) {
            System.err.println("Error adding remote candidate: " + e.getMessage());
        }
    }

    private Transport parseTransport(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return Transport.UDP;
        }
        try {
            return Transport.parse(protocol.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Transport.UDP;
        }
    }

    private TransportAddress buildTransportAddress(String host, int port, Transport transport) throws Exception {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Candidate host is empty");
        }
        if (host.contains(":")) {
            InetAddress address = InetAddress.getByName(host);
            return new TransportAddress(address, port, transport);
        }
        return new TransportAddress(host, port, transport);
    }

    private void addIpv6Harvesters(Agent agent) {
        try {
            InetAddress[] resolvedAddresses = InetAddress.getAllByName(stunServer);
            for (InetAddress resolved : resolvedAddresses) {
                if (resolved instanceof Inet6Address) {
                    TransportAddress ipv6Address = new TransportAddress(resolved, stunPort, Transport.UDP);
                    agent.addCandidateHarvester(new StunCandidateHarvester(ipv6Address));
                    System.out.println("IPv6 STUN harvester added: " + ipv6Address.getHostAddress());
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Unable to resolve IPv6 STUN address for " + stunServer + ": " + e.getMessage());
        }
    }

    private void maybeStartConnectivityCheck() {
        if (gatheringComplete.get() && remoteGatheringComplete.get() && !connected.get()) {
            if (connectivityStarted.compareAndSet(false, true)) {
                startConnectivityCheckAsync();
            }
        }
    }

    private void startConnectivityCheckAsync() {
        workerExecutor.submit(() -> {
            if (!runConnectivityCheck()) {
                handleConnectionFailure("Connectivity check failed");
            }
        });
    }

    private boolean runConnectivityCheck() {
        try {
            if (iceAgent == null) {
                throw new IllegalStateException("ICE agent not initialised");
            }
            iceAgent.startConnectivityEstablishment();

            long startTime = System.currentTimeMillis();
            while (!connected.get() && !closed &&
                   (System.currentTimeMillis() - startTime) < 30000) {
                if (iceAgent.getState() == IceProcessingState.FAILED) {
                    return false;
                }
                Thread.sleep(100);
            }
            if (connected.get()) {
                System.out.println("P2P connection established!");
                if (selectedPair != null) {
                    System.out.println("  Local:  " + selectedPair.getLocalCandidate().getTransportAddress());
                    System.out.println("  Remote: " + selectedPair.getRemoteCandidate().getTransportAddress());
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("Connectivity check error: " + e.getMessage());
            return false;
        }
    }

    private void handleConnectionFailure(String reason) {
        if (closed) {
            return;
        }
        int attempt = retryCounter.incrementAndGet();
        if (attempt > MAX_RETRIES) {
            System.err.println("Maximum ICE retries reached for session " + sessionId);
            return;
        }
        long delay = (long) Math.pow(2, attempt - 1) * BASE_RETRY_DELAY_MS;
        System.out.println("Scheduling ICE restart (attempt " + attempt + ") in " + delay + " ms: " + reason);
        scheduler.schedule(() -> restartIce(reason, true, attempt), delay, TimeUnit.MILLISECONDS);
    }

    private void restartIce(String reason, boolean notifyServer, int attempt) {
        if (closed || !restarting.compareAndSet(false, true)) {
            return;
        }
        try {
            System.out.println("Restarting ICE for session " + sessionId + ": " + reason);
            initializeICEAgent();
            if (notifyServer) {
                SafeRoomProto.ICERestart restart = SafeRoomProto.ICERestart.newBuilder()
                    .setReason(reason)
                    .setAttempt(attempt)
                    .build();
                SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
                    .setSessionId(sessionId)
                    .setFromUser(localUsername)
                    .setRestart(restart)
                    .build();
                sendStreamMessage(message);
            }
            startCandidateGathering();
        } catch (Exception e) {
            System.err.println("ICE restart failed: " + e.getMessage());
            handleConnectionFailure("Restart error: " + e.getMessage());
        } finally {
            restarting.set(false);
        }
    }

    private void handleRemoteRestart(SafeRoomProto.ICERestart restart) {
        System.out.println("Remote requested ICE restart: " + restart.getReason() +
            " (attempt " + restart.getAttempt() + ")");
        retryCounter.set(0);
        scheduler.execute(() -> restartIce("Remote requested restart", false, restart.getAttempt()));
    }

    /**
     * Releases all resources associated with the ICE manager.
     */
    public void close() {
        closed = true;
        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                SafeRoomProto.P2PTerminateRequest request = SafeRoomProto.P2PTerminateRequest.newBuilder()
                    .setSessionId(sessionId)
                    .setUsername(localUsername)
                    .build();
                blockingStub.terminateP2PSession(request);
            } catch (Exception e) {
                System.err.println("Error terminating P2P session: " + e.getMessage());
            }
        }

        synchronized (streamLock) {
            if (requestObserver != null) {
                try {
                    requestObserver.onCompleted();
                } catch (Exception ignored) {
                }
                requestObserver = null;
            }
        }

        cleanupAgent();
        scheduler.shutdownNow();
        workerExecutor.shutdownNow();
        System.out.println("ICE Manager closed");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public CandidatePair getSelectedPair() {
        return selectedPair;
    }
}
