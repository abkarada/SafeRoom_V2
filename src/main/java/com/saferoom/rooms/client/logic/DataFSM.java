package com.saferoom.rooms.client.logic;

import com.saferoom.rooms.client.RoomSignalingClient;
import com.saferoom.rooms.grpc.RoomEvent;
import com.saferoom.rooms.grpc.GetSeedsResponse;
import com.saferoom.rooms.grpc.RoomPeer;
import com.saferoom.rooms.grpc.GetSeedsResponse;
import com.saferoom.rooms.grpc.RoomPeer;
import com.saferoom.rooms.client.storage.EventLog;
import com.saferoom.rooms.grpc.ChannelMetadata;
import com.saferoom.rooms.grpc.ChatMessage;
import java.util.logging.Logger;

/**
 * DataFSM: Manages the node's state within the Room Data Overlay.
 * States: IDLE -> SIGNALING_JOINING -> TREE_CONNECTING -> TREE_READY -> SYNCING
 * -> READY
 */
public class DataFSM implements RoomSignalingClient.RoomEventListener, RoomWebRTCManager.Listener {

    private static final Logger logger = Logger.getLogger(DataFSM.class.getName());

    public enum State {
        IDLE,
        SIGNALING_JOINING,
        TREE_CONNECTING, // Looking for parent
        TREE_READY, // Connected to parent, DC established
        SYNCING, // Fetching history
        READY, // Normal operation
        DEGRADED // Lost parent/DC, recovering
    }

    private final String roomId;
    private final String nodeId;
    private final RoomSignalingClient signalingClient;

    private State currentState = State.IDLE;
    private long currentEpoch = 0;

    // Topology
    private String parentNodeId = null;
    // In v1 we might use existing P2P manager for actual connections

    private final EventLog eventLog;
    private final FileManager fileManager;
    private final RoomWebRTCManager webRTCManager;

    // UI Listeners for presence updates
    public interface UIListener {
        void onPresenceUpdate(java.util.List<RoomPeer> peers);

        void onStateChange(State newState);

        // Sprint 15: Real-time events
        default void onChannelCreated(ChannelMetadata channel) {
        }

        default void onChannelDeleted(String channelId) {
        }

        default void onMessageReceived(ChatMessage message) {
        }
    }

    private final java.util.List<UIListener> uiListeners = new java.util.ArrayList<>();

    public void addUIListener(UIListener listener) {
        uiListeners.add(listener);
    }

    public void removeUIListener(UIListener listener) {
        uiListeners.remove(listener);
    }

    private void notifyPresenceUpdate(java.util.List<RoomPeer> peers) {
        for (UIListener l : uiListeners) {
            l.onPresenceUpdate(peers);
        }
    }

    private void notifyStateChange(State newState) {
        for (UIListener l : uiListeners) {
            l.onStateChange(newState);
        }
    }

    private void notifyChannelCreated(ChannelMetadata channel) {
        for (UIListener l : uiListeners) {
            l.onChannelCreated(channel);
        }
    }

    private void notifyChannelDeleted(String channelId) {
        for (UIListener l : uiListeners) {
            l.onChannelDeleted(channelId);
        }
    }

    private void notifyMessageReceived(ChatMessage message) {
        for (UIListener l : uiListeners) {
            l.onMessageReceived(message);
        }
    }

    public DataFSM(String roomId, String nodeId, RoomSignalingClient client, RoomWebRTCManager webRTCManager) {
        this.roomId = roomId;
        this.nodeId = nodeId;
        this.signalingClient = client;
        this.eventLog = new EventLog();
        this.fileManager = new FileManager();
        this.webRTCManager = webRTCManager;

        // Register FSM as the listener for WebRTC events
        this.webRTCManager.setListener(this);
    }

    public void start() {
        transitionTo(State.SIGNALING_JOINING);
        // Signaling Join is handled by caller (RoomManager/UI) calling client.joinRoom
        // But here we listen for events
        signalingClient.addListener(this);
    }

    public void stop() {
        logger.info("Stopping DataFSM for room: " + roomId);
        signalingClient.removeListener(this);
        uiListeners.clear();
        webRTCManager.closeAllConnections();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        transitionTo(State.IDLE);
    }

    public void onSignalingJoinSuccess(long epoch) {
        this.currentEpoch = epoch;
        logger.info("Signaling Join Success. Epoch: " + epoch);
        fetchSeeds();
    }

    private void fetchSeeds() {
        transitionTo(State.TREE_CONNECTING);
        try {
            GetSeedsResponse seeds = signalingClient.getSeeds(roomId, currentEpoch);
            logger.info("Got seeds: " + seeds.getSeedRoutersCount() + " routers");

            if (seeds.getSeedRoutersCount() > 0) {
                RoomPeer bestParent = selectBestParent(seeds.getSeedRoutersList());
                if (bestParent != null) {
                    connectToParent(bestParent);
                } else {
                    logger.warning("No suitable parent found in seeds.");
                    transitionTo(State.DEGRADED);
                }
            } else {
                logger.warning("No seeds found. Becoming orphan/root or waiting?");
                // For v1 if no seeds, maybe we are the first/root?
                transitionTo(State.TREE_READY);
            }
        } catch (Exception e) {
            logger.severe("Failed to get seeds: " + e.getMessage());
            transitionTo(State.DEGRADED);
        }
    }

    private void connectToParent(RoomPeer seed) {
        logger.info("Attempting to connect to parent: " + seed.getNodeId());
        // Sprint 6: Real WebRTC Call
        webRTCManager.initiateConnection(seed.getNodeId());
        // The Offer will be generated asynchronously and "onLocalSignal" will be
        // called.
    }

    private void startSync() {
        transitionTo(State.SYNCING);
        // Sprint 3: Sync Logic
        // 1. Check local log for last known state
        RoomEvent last = eventLog.getLastEvent();
        long lastTs = (last != null) ? last.getTimestamp() : 0;

        logger.info("Starting Sync from TS: " + lastTs);

        // 2. In real P2P: send SYNC_REQ(lastTs) to parent/neighbors via DataChannel
        // envelope = Envelope.type(SYNC).payload(lastTs)...

        // 3. For now, we assume we are caught up or server streams events
        transitionTo(State.READY);
    }

    // Sprint 2: Parent Selection Algorithm
    private RoomPeer selectBestParent(java.util.List<RoomPeer> candidates) {
        if (candidates.isEmpty())
            return null;

        RoomPeer best = null;
        double bestScore = -1.0;

        for (RoomPeer candidate : candidates) {
            // Calculate Score:
            // 1. Prefer lower children count (Load Balancing)
            // 2. Prefer ROUTER role over LEAF (though seeds are usually routers)
            // 3. (Future) Prefer lower RTT

            double score = 0.0;
            if (candidate.getMaxChildren() > 0) {
                double capacityRatio = 1.0 - ((double) candidate.getConnectedChildren() / candidate.getMaxChildren());
                score += capacityRatio * 100;
            }

            // Penalty for full nodes
            if (candidate.getConnectedChildren() >= candidate.getMaxChildren()) {
                score = -1.0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            logger.warning("No suitable parent found (all full?)");
            // Fallback: Pick random or first if desperate, but spec says wait/retry
            if (!candidates.isEmpty())
                return candidates.get(0);
        }

        return best;
    }

    @Override
    public void onEvent(RoomEvent event) {
        // Persist to Log
        eventLog.append(event);

        switch (event.getType()) {
            case ROOM_PRESENCE:
                logger.info("Presence update: " + event.getPresence().getConnectedPeersCount() + " peers");
                notifyPresenceUpdate(event.getPresence().getPeersList());
                break;
            case EPOCH_CHANGE:
                if (event.getNewEpoch() > currentEpoch) {
                    logger.info("Epoch changed: " + currentEpoch + " -> " + event.getNewEpoch());
                    this.currentEpoch = event.getNewEpoch();
                    onParentActivity();
                    // If we are DEGRADED, this might be a signal to try again
                    if (currentState == State.DEGRADED) {
                        fetchSeeds();
                    }
                }
                break;
            case SIGNAL_RELAY:
                handleSignalRelay(event.getSignal());
                break;
            case FILE_META:
                if (event.hasFileMeta()) {
                    fileManager.handleMeta(event.getFileMeta());
                }
                break;
            case CHANNEL_CREATED:
                if (event.hasChannel()) {
                    notifyChannelCreated(event.getChannel());
                }
                break;
            case CHANNEL_DELETED:
                if (!event.getChannelId().isEmpty()) {
                    notifyChannelDeleted(event.getChannelId());
                }
                break;
            case MESSAGE_RECEIVED:
                if (event.hasMessage()) {
                    notifyMessageReceived(event.getMessage());
                }
                break;
            default:
                break;
        }
    }

    private void handleSignalRelay(com.saferoom.rooms.grpc.SignalRelayRequest request) {
        String from = request.getSrcNodeId();
        if (request.hasOffer()) {
            logger.info("Received OFFER from " + from);
            webRTCManager.handleOffer(from, request.getOffer().getSdp());
        } else if (request.hasAnswer()) {
            logger.info("Received ANSWER from " + from);
            if (currentState == State.TREE_CONNECTING) {
                // We set the remote desc. Transition to TREE_READY happens when DC opens (or
                // assume here?)
                // Better: wait for onConnectionStateChange(true)
                webRTCManager.handleAnswer(from, request.getAnswer().getSdp());
                // For Sprint 6 verify: we assume immediate success or wait for callback
            }
            onParentActivity();
        } else if (request.hasIce()) {
            logger.info("Received ICE from " + from);
            webRTCManager.addIceCandidate(from, request.getIce().getCandidate(),
                    request.getIce().getSdpMid(), request.getIce().getSdpMLineIndex());
        }
    }

    private java.util.concurrent.ScheduledExecutorService scheduler;
    private int missedHeartbeats = 0;

    @Override
    public void onDisconnected() {
        stopHeartbeat();
        transitionTo(State.IDLE);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        // Send heartbeat every 2 seconds
        scheduler.scheduleAtFixedRate(this::checkHealth, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void checkHealth() {
        if (currentState != State.TREE_READY && currentState != State.SYNCING && currentState != State.READY) {
            return;
        }

        missedHeartbeats++;
        if (missedHeartbeats >= 3) {
            logger.severe("Parent Heartbeat timeout (" + missedHeartbeats + "). Triggering Failover.");
            handleParentFailure();
            return;
        }

        // In real logic: send ping via DataChannel
        // For simulation: we assume "success" if connected, but we'd reset
        // missedHeartbeats on receiving events
        // Here we simulate self-healing just by logging.
        logger.info("[Health] Pinging parent " + parentNodeId);
    }

    private void handleParentFailure() {
        transitionTo(State.DEGRADED);
        stopHeartbeat();
        missedHeartbeats = 0;
        parentNodeId = null;

        // Re-initiate search for new parent
        fetchSeeds();
    }

    // Call this when any message is received from parent
    private void onParentActivity() {
        missedHeartbeats = 0;
    }

    public State getState() {
        return currentState;
    }

    private void transitionTo(State newState) {
        logger.info("FSM: " + currentState + " -> " + newState);
        this.currentState = newState;
        notifyStateChange(newState);
    }

    /**
     * Entry point for DataChannel messages (Sprint 5/6).
     * To be called by WebRTCSessionManager when data is received.
     */
    public void onDataChannelMessage(com.saferoom.rooms.grpc.Envelope envelope) {
        if (envelope.getType() == com.saferoom.rooms.grpc.Envelope.Type.FILE) {
            try {
                com.saferoom.rooms.grpc.FileMessage fileMsg = com.saferoom.rooms.grpc.FileMessage
                        .parseFrom(envelope.getPayload());
                // The source node ID usually comes from Envelope wrapper or the channel context
                String srcNodeId = envelope.getSrcNodeId();
                fileManager.handleMessage(fileMsg, srcNodeId);
            } catch (Exception e) {
                logger.warning("Failed to parse FileMessage: " + e.getMessage());
            }
        } else {
            logger.info("Received DataChannel message type: " + envelope.getType());
        }
    }

    // --- RoomWebRTCManager.Listener Impl ---

    @Override
    public void onLocalSignal(String remoteNodeId, com.saferoom.rooms.grpc.SignalRelayRequest signal) {
        // Relay via gRPC
        if (signal.hasOffer()) {
            signalingClient.sendOffer(roomId, nodeId, remoteNodeId, signal.getOffer().getSdp());
        } else if (signal.hasAnswer()) {
            signalingClient.sendAnswer(roomId, nodeId, remoteNodeId, signal.getAnswer().getSdp());
        } else if (signal.hasIce()) {
            signalingClient.sendIceCandidate(roomId, nodeId, remoteNodeId,
                    signal.getIce().getCandidate(), signal.getIce().getSdpMid(), signal.getIce().getSdpMLineIndex());
        }
    }

    @Override
    public void onDataMessage(String remoteNodeId, com.saferoom.rooms.grpc.Envelope envelope) {
        // Bridge directly to existing logic
        onDataChannelMessage(envelope);
        onParentActivity(); // Any data counts as activity
    }

    @Override
    public void onConnectionStateChange(String remoteNodeId, boolean connected) {
        logger.info("WebRTC Connection State for " + remoteNodeId + ": " + connected);

        if (connected) {
            if (currentState == State.TREE_CONNECTING && remoteNodeId.equals(parentNodeId)) {
                // Nothing logic-wise, we wait for Answer processing usually.
                // But real DC open is the true sign of readiness.
            }
            // Use this to transition to TREE_READY?
            if (currentState == State.TREE_CONNECTING) {
                this.parentNodeId = remoteNodeId;
                transitionTo(State.TREE_READY);
                startSync();
                startHeartbeat();
            }
        } else {
            if (remoteNodeId.equals(parentNodeId)) {
                logger.warning("Parent DC closed/failed.");
                handleParentFailure();
            }
        }
    }

    public void destroy() {
        signalingClient.removeListener(this);
    }
}
