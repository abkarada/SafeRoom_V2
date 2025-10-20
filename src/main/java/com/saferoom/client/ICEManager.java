package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.UDPHoleGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.KeepAliveStrategy;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.NominationStrategy;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TrickleCallback;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;
import org.ice4j.message.Indication;
import org.ice4j.message.MessageFactory;
import org.ice4j.stack.StunStack;

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
    private final UDPHoleGrpc.UDPHoleStub asyncStub;

    private final Object streamLock = new Object();
    private StreamObserver<SafeRoomProto.ICEStreamMessage> requestObserver;

    private final AtomicBoolean gatheringComplete = new AtomicBoolean(false);
    private final AtomicBoolean remoteGatheringComplete = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connectivityStarted = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean localCandidatesReady = new AtomicBoolean(false);
    private final AtomicBoolean remoteCandidatesReady = new AtomicBoolean(false);

    private final AtomicInteger retryCounter = new AtomicInteger(0);
    private static final int MAX_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_MS = 1000L;

    private final ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ice-retry-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService eventExecutor;

    private volatile CandidatePair selectedPair;
    private final Set<String> sentCandidateKeys = ConcurrentHashMap.newKeySet();

    private String stunServer;
    private int stunPort;
    private TurnConfig turnConfig;

    private volatile ICEEventListener eventListener;
    private volatile ScheduledFuture<?> keepAliveTask;

    private static final String[] DEFAULT_STUNS = {
        "stun.l.google.com",
        "stun1.l.google.com",
        "stun2.l.google.com",
        "stun3.l.google.com",
        "stun4.l.google.com"
    };

    public ICEManager(String localUsername, String remoteUsername, ManagedChannel grpcChannel) {
        this.localUsername = localUsername;
        this.remoteUsername = remoteUsername;
        this.grpcChannel = grpcChannel;
        this.asyncStub = UDPHoleGrpc.newStub(grpcChannel);
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ice-events-" + localUsername + "-" + remoteUsername);
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(ICEEventListener listener) {
        this.eventListener = listener;
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

        SafeRoomProto.P2PInitResponse initResponse = UDPHoleGrpc.newBlockingStub(grpcChannel)
            .initiateP2PConnection(initRequest);
        if (!initResponse.getSuccess()) {
            throw new Exception("P2P init failed: " + initResponse.getMessage());
        }

        this.sessionId = initResponse.getSessionId();
        this.closed.set(false);
        this.retryCounter.set(0);
        System.out.println("P2P session created: " + sessionId);

        openIceStream();
        cleanupAgent();
        initializeICEAgent();
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
                        remoteCandidatesReady.compareAndSet(false, true);
                        maybeStartConnectivityCheck();
                    }
                } else if (message.hasRestart()) {
                    handleRemoteRestart(message.getRestart());
                }
            }

            @Override
            public void onError(Throwable t) {
                if (closed.get()) {
                    return;
                }
                System.err.println("ICE stream error: " + t.getMessage());
                fireListener(l -> l.onFailure("ICE stream error: " + t.getMessage()));
                scheduleStreamReconnect();
                scheduleIceRestart("Stream error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (closed.get()) {
                    return;
                }
                System.out.println("ICE stream completed, reopening");
                fireListener(l -> l.onFailure("ICE stream completed unexpectedly"));
                scheduleStreamReconnect();
                scheduleIceRestart("Stream completed");
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
            if (!closed.get()) {
                System.out.println("Re-opening ICE stream for session " + sessionId);
                openIceStream();
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void initializeICEAgent() throws Exception {
        System.out.println("Initializing ICE Agent");
        Agent agent = new Agent();
        agent.setNominationStrategy(NominationStrategy.NOMINATE_HIGHEST_PRIO);

        agent.addStateChangeListener(evt -> {
            if (!Agent.PROPERTY_ICE_PROCESSING_STATE.equals(evt.getPropertyName())) {
                return;
            }
            IceProcessingState state = (IceProcessingState) evt.getNewValue();
            fireListener(l -> l.onIceStateChanged(state));
            if (state == IceProcessingState.COMPLETED) {
                connected.set(true);
                retryCounter.set(0);
                CandidatePair pair = extractSelectedPair();
                selectedPair = pair;
                if (pair != null) {
                    System.out.println("Candidate pair succeeded: " + pair);
                }
                startKeepAliveTask();
                fireListener(l -> l.onConnected(pair));
            } else if (state == IceProcessingState.FAILED) {
                connected.set(false);
                cancelKeepAliveTask();
                fireListener(l -> l.onFailure("Connectivity failed"));
                handleConnectionFailure("ICE state failed");
            }
        });

        configureStunHarvesters(agent);

        if (turnConfig != null) {
            LongTermCredential credential = new LongTermCredential(turnConfig.getUsername(), turnConfig.getPassword());
            TransportAddress udpAddress = new TransportAddress(turnConfig.getServer(), turnConfig.getPort(), Transport.UDP);
            agent.addCandidateHarvester(new TurnCandidateHarvester(udpAddress, credential));

            TransportAddress tcpAddress = new TransportAddress(turnConfig.getServer(), turnConfig.getPort(), Transport.TCP);
            agent.addCandidateHarvester(new TurnCandidateHarvester(tcpAddress, credential));

            try {
                InetAddress[] turnAddresses = InetAddress.getAllByName(turnConfig.getServer());
                for (InetAddress resolved : turnAddresses) {
                    if (resolved instanceof Inet6Address) {
                        TransportAddress ipv6Udp = new TransportAddress(resolved, turnConfig.getPort(), Transport.UDP);
                        agent.addCandidateHarvester(new TurnCandidateHarvester(ipv6Udp, credential));
                        TransportAddress ipv6Tcp = new TransportAddress(resolved, turnConfig.getPort(), Transport.TCP);
                        agent.addCandidateHarvester(new TurnCandidateHarvester(ipv6Tcp, credential));
                        System.out.println("TURN IPv6 support enabled for " + resolved.getHostAddress());
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to configure IPv6 TURN harvester: " + e.getMessage());
            }

            System.out.println("TURN support enabled for " + turnConfig.getServer() + ":" + turnConfig.getPort());
        }

        mediaStream = agent.createMediaStream("data");
        component = agent.createComponent(
            mediaStream,
            0,
            49152,
            65535,
            KeepAliveStrategy.SELECTED_ONLY
        );

        iceAgent = agent;
        sentCandidateKeys.clear();
        gatheringComplete.set(false);
        remoteGatheringComplete.set(false);
        connectivityStarted.set(false);
        connected.set(false);
        selectedPair = null;
        localCandidatesReady.set(false);
        remoteCandidatesReady.set(false);

        startCandidateTrickle(agent);

        System.out.println("ICE Agent initialized");
    }

    private void cleanupAgent() {
        cancelKeepAliveTask();
        if (iceAgent != null) {
            try {
                iceAgent.free();
            } catch (Exception ignored) {
            }
        }
        iceAgent = null;
        mediaStream = null;
        component = null;
    }

    private void startCandidateTrickle(Agent agent) {
        agent.startCandidateTrickle(new TrickleCallback() {
            @Override
            public void onIceCandidates(java.util.Collection<LocalCandidate> candidates) {
                if (closed.get()) {
                    return;
                }

                if (candidates == null || candidates.isEmpty()) {
                    if (gatheringComplete.compareAndSet(false, true)) {
                        sendGatheringComplete();
                        fireListener(ICEEventListener::onGatheringComplete);
                        localCandidatesReady.compareAndSet(false, true);
                        maybeStartConnectivityCheck();
                    }
                    return;
                }

                for (LocalCandidate candidate : candidates) {
                    if (sendCandidate(candidate)) {
                        localCandidatesReady.set(true);
                        fireListener(l -> l.onLocalCandidateDiscovered(candidate));
                    }
                }
                maybeStartConnectivityCheck();
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
                .setPriority(candidate.getPriority())
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
            remoteCandidatesReady.set(true);
            fireListener(l -> l.onRemoteCandidateAdded(remoteCandidate));
            maybeStartConnectivityCheck();
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

    private boolean addIpv6Harvesters(Agent agent, String host) {
        boolean added = false;
        boolean ipv6Capable = hasLocalIpv6Capability();
        try {
            InetAddress[] resolvedAddresses = InetAddress.getAllByName(host);
            InetAddress firstIpv4 = null;
            for (InetAddress resolved : resolvedAddresses) {
                if (resolved instanceof Inet6Address) {
                    registerIpv6Harvester(agent, resolved);
                    added = true;
                } else if (firstIpv4 == null) {
                    firstIpv4 = resolved;
                }
            }

            if (!added && ipv6Capable && firstIpv4 != null) {
                String nat64Host = synthesizeNat64Address(firstIpv4);
                if (nat64Host != null) {
                    try {
                        InetAddress nat64Address = InetAddress.getByName(nat64Host);
                        registerIpv6Harvester(agent, nat64Address);
                        added = true;
                        System.out.println("Synthesized NAT64 STUN harvester added: " + nat64Host);
                    } catch (Exception nat64Ex) {
                        System.err.println("Failed to configure NAT64 STUN harvester: " + nat64Ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Unable to resolve IPv6 STUN address for " + host + ": " + e.getMessage());
        }

        if (!added) {
            System.out.println("IPv6 STUN harvesters unavailable for " + host);
        }
        return added;
    }

    private void registerIpv6Harvester(Agent agent, InetAddress address) {
        TransportAddress udp = new TransportAddress(address, stunPort, Transport.UDP);
        agent.addCandidateHarvester(new StunCandidateHarvester(udp));
        TransportAddress tcp = new TransportAddress(address, stunPort, Transport.TCP);
        agent.addCandidateHarvester(new StunCandidateHarvester(tcp));
        System.out.println("IPv6 STUN harvester added: " + udp.getHostAddress());
    }

    private String synthesizeNat64Address(InetAddress ipv4Address) {
        byte[] octets = ipv4Address.getAddress();
        if (octets == null || octets.length != 4) {
            return null;
        }
        return String.format("64:ff9b::%d.%d.%d.%d",
            octets[0] & 0xFF,
            octets[1] & 0xFF,
            octets[2] & 0xFF,
            octets[3] & 0xFF);
    }

    private boolean hasLocalIpv6Capability() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address && !address.isLinkLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to determine IPv6 capability: " + e.getMessage());
        }
        return false;
    }

    private void maybeStartConnectivityCheck() {
        Agent agent = iceAgent;
        Component currentComponent = component;
        if (agent == null || currentComponent == null) {
            return;
        }

        boolean remoteReady = remoteCandidatesReady.get() || remoteGatheringComplete.get()
            || !currentComponent.getRemoteCandidates().isEmpty();
        boolean localReady = localCandidatesReady.get() || gatheringComplete.get()
            || currentComponent.getLocalCandidateCount() > 0;

        if (remoteReady && localReady && connectivityStarted.compareAndSet(false, true)) {
            System.out.println("Starting ICE connectivity immediately (event-driven)");
            agent.startConnectivityEstablishment();
        }
    }

    private void handleConnectionFailure(String reason) {
        if (closed.get()) {
            return;
        }
        cancelKeepAliveTask();
        int attempt = retryCounter.incrementAndGet();
        if (attempt > MAX_RETRIES) {
            System.err.println("Maximum ICE retries reached for session " + sessionId);
            fireListener(l -> l.onFailure("Maximum ICE retries reached"));
            return;
        }
        long delay = (long) Math.pow(2, attempt - 1) * BASE_RETRY_DELAY_MS;
        System.out.println("Scheduling ICE restart (attempt " + attempt + ") in " + delay + " ms: " + reason);
        scheduler.schedule(() -> restartIce(reason, true, attempt), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void restartIce(String reason, boolean notifyServer, int attempt) {
        if (closed.get() || !restarting.compareAndSet(false, true)) {
            return;
        }
        try {
            System.out.println("Restarting ICE for session " + sessionId + ": " + reason);
            cleanupAgent();
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
        } catch (Exception e) {
            System.err.println("ICE restart failed: " + e.getMessage());
            handleConnectionFailure("Restart error: " + e.getMessage());
        } finally {
            restarting.set(false);
        }
    }

    private void scheduleIceRestart(String reason) {
        int attempt = retryCounter.incrementAndGet();
        if (attempt > MAX_RETRIES) {
            System.err.println("Maximum ICE retries reached for session " + sessionId + " (stream restart)");
            fireListener(l -> l.onFailure("Maximum ICE retries reached"));
            return;
        }
        scheduler.execute(() -> restartIce(reason, true, attempt));
    }

    private void handleRemoteRestart(SafeRoomProto.ICERestart restart) {
        System.out.println("Remote requested ICE restart: " + restart.getReason() +
            " (attempt " + restart.getAttempt() + ")");
        retryCounter.set(0);
        localCandidatesReady.set(false);
        remoteCandidatesReady.set(false);
        scheduler.execute(() -> restartIce("Remote requested restart", false, restart.getAttempt()));
    }

    /**
     * Releases all resources associated with the ICE manager.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                SafeRoomProto.P2PTerminateRequest request = SafeRoomProto.P2PTerminateRequest.newBuilder()
                    .setSessionId(sessionId)
                    .setUsername(localUsername)
                    .build();
                UDPHoleGrpc.newBlockingStub(grpcChannel).terminateP2PSession(request);
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
        eventExecutor.shutdownNow();
        System.out.println("ICE Manager closed");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public CandidatePair getSelectedPair() {
        return selectedPair;
    }

    private CandidatePair extractSelectedPair() {
        if (component != null) {
            CandidatePair pair = component.getSelectedPair();
            if (pair != null) {
                return pair;
            }
        }
        if (iceAgent != null) {
            for (IceMediaStream stream : iceAgent.getStreams()) {
                for (Component comp : stream.getComponents()) {
                    CandidatePair pair = comp.getSelectedPair();
                    if (pair != null) {
                        return pair;
                    }
                }
            }
        }
        return null;
    }

    private void fireListener(Consumer<ICEEventListener> action) {
        ICEEventListener listener = eventListener;
        if (listener == null) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                try {
                    action.accept(listener);
                } catch (Throwable t) {
                    System.err.println("ICE listener execution error: " + t.getMessage());
                }
            });
        } catch (RuntimeException e) {
            System.err.println("Unable to dispatch ICE event: " + e.getMessage());
        }
    }

    private void configureStunHarvesters(Agent agent) {
        boolean added = false;
        if (stunServer != null && !stunServer.isBlank()) {
            added |= addStunHarvester(agent, stunServer);
        }
        for (String host : DEFAULT_STUNS) {
            if (stunServer != null && stunServer.equalsIgnoreCase(host)) {
                continue;
            }
            added |= addStunHarvester(agent, host);
        }
        if (!added) {
            System.err.println("No STUN harvesters could be configured; ICE may fail");
        }
    }

    private boolean addStunHarvester(Agent agent, String host) {
        boolean added = false;
        try {
            agent.addCandidateHarvester(new StunCandidateHarvester(
                new TransportAddress(host, stunPort, Transport.UDP)
            ));
            added = true;
        } catch (Exception e) {
            System.err.println("Skipping STUN " + host + " (UDP): " + e.getMessage());
        }

        try {
            agent.addCandidateHarvester(new StunCandidateHarvester(
                new TransportAddress(host, stunPort, Transport.TCP)
            ));
            added = true;
        } catch (Exception e) {
            System.err.println("Skipping STUN " + host + " (TCP): " + e.getMessage());
        }

        if (addIpv6Harvesters(agent, host)) {
            added = true;
        }

        return added;
    }

    private void startKeepAliveTask() {
        cancelKeepAliveTask();
        keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected.get()) {
                return;
            }
            Component currentComponent = component;
            if (currentComponent == null) {
                return;
            }
            CandidatePair pair = currentComponent.getSelectedPair();
            if (pair == null) {
                return;
            }
            try {
                StunStack stunStack = currentComponent.getParentStream().getParentAgent().getStunStack();
                TransportAddress localAddress = pair.getLocalCandidate().getTransportAddress();
                TransportAddress remoteAddress = pair.getRemoteCandidate().getTransportAddress();
                Indication indication = MessageFactory.createBindingIndication();
                stunStack.sendIndication(indication, localAddress, remoteAddress);
            } catch (Exception e) {
                System.err.println("Failed to send ICE keep-alive: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void cancelKeepAliveTask() {
        ScheduledFuture<?> task = keepAliveTask;
        if (task != null) {
            task.cancel(false);
            keepAliveTask = null;
        }
    }
}
