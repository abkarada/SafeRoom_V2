package com.saferoom.p2p;

import com.saferoom.webrtc.WebRTCSignalingClient;
import com.saferoom.webrtc.WebRTCClient;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import dev.onvoid.webrtc.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * P2P Connection Manager for WebRTC-based messaging/file transfer
 * 
 * SCOPE: ONLY messaging and file transfer (NOT voice/video calls)
 * 
 * Architecture:
 * 1. WebRTC ICE negotiation → Establishes encrypted DataChannel
 * 2. DataChannel → LLS protocol packets (DNS keep-alive, reliable messaging)
 * 3. ReliableMessageSender/Receiver → Handles chunked messaging with ACK/NACK
 * 
 * This REPLACES the legacy UDP punch hole system (NatAnalyzer) for messaging.
 * Voice/video calls still use CallManager (separate WebRTC peer connection).
 */
public class P2PConnectionManager {
    
    private static P2PConnectionManager instance;
    
    private static final String FILE_CTRL_PREFIX = "__FT_CTRL__";
    private static final String CTRL_UR_RECEIVER = "UR_RECEIVER";
    private static final String CTRL_OK_SNDFILE = "OK_SNDFILE";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final long RECEIVER_READY_TIMEOUT_SEC = 30;
    
    private String myUsername;
    private WebRTCSignalingClient signalingClient;
    private PeerConnectionFactory factory;
    
    // Active P2P connections (username → connection)
    private final Map<String, P2PConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingConnections = new ConcurrentHashMap<>();
    
    private P2PConnectionManager() {}
    
    public static synchronized P2PConnectionManager getInstance() {
        if (instance == null) {
            instance = new P2PConnectionManager();
        }
        return instance;
    }
    
    // ⚡ LAZY LOADING flags
    private boolean signalingReady = false;
    private boolean webrtcReady = false;
    
    /**
     * Initialize P2P system for messaging/file transfer
     * Called from ClientMenu.registerUserForP2P()
     * 
     * ⚡ LAZY LOADING: WebRTC is NOT loaded here!
     * Only signaling is initialized (lightweight)
     * WebRTC will load on first P2P connection attempt
     */
    public void initialize(String username) {
        if (signalingReady) {
            return;
        }
        
        this.myUsername = username;
        
        System.out.printf("[P2P] ⚡ Initializing P2P signaling for: %s (WebRTC deferred)%n", username);
        
        // ⚡ DON'T initialize WebRTC here! It will load lazily when needed.
        // Just setup signaling for now.
        
        // IMPORTANT: Share WebRTCSignalingClient with CallManager
        // Get signaling client from CallManager to avoid callback conflicts
        try {
            com.saferoom.webrtc.CallManager callManager = 
                com.saferoom.webrtc.CallManager.getInstance();
            
            // FIX: Use public getter instead of reflection
            this.signalingClient = callManager.getSignalingClient();
            
            if (this.signalingClient == null) {
                System.err.println("[P2P] CallManager signaling client is null, creating new one");
                this.signalingClient = new WebRTCSignalingClient(username);
                this.signalingClient.startSignalingStream();
            }
            
            System.out.println("[P2P] Using shared signaling client from CallManager");
            
            // Register our handler for P2P signals
            registerP2PSignalHandler();
            
        } catch (Exception e) {
            System.err.println("[P2P] Failed to get CallManager signaling client: " + e.getMessage());
            e.printStackTrace();
            // Fallback: create our own (not recommended - callback conflict)
            this.signalingClient = new WebRTCSignalingClient(username);
            this.signalingClient.startSignalingStream();
            this.signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        }
        
        signalingReady = true;
        System.out.printf("[P2P] ⚡ P2P signaling ready for %s (WebRTC will load on first connection)%n", username);
    }
    
    /**
     * ⚡ LAZY LOAD: Ensure WebRTC is initialized before creating connections
     * This is called automatically when needed
     */
    private synchronized void ensureWebRTCReady() {
        if (webrtcReady) {
            return;
        }
        
        System.out.println("[P2P] 🎬 Loading WebRTC for P2P connections...");
        long startTime = System.currentTimeMillis();
        
        // Initialize WebRTC factory (shared with CallManager)
        if (!WebRTCClient.isInitialized()) {
            System.out.println("[P2P] WebRTC not initialized, initializing now...");
            WebRTCClient.initialize();
        }
        
        // Get factory reference from WebRTCClient
        this.factory = WebRTCClient.getFactory();
        if (this.factory == null) {
            System.err.println("[P2P] WebRTC factory is null (running in mock mode)");
        } else {
            System.out.println("[P2P] WebRTC factory initialized successfully");
        }
        
        webrtcReady = true;
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("[P2P] ✅ WebRTC loaded in %dms%n", elapsed);
    }
    
    /**
     * Register P2P signal handler with CallManager
     * 
     * IMPORTANT: Now using MESH_* signal types for consistency with GroupCallManager
     * This ensures proper signal routing through the server and client handlers.
     */
    private void registerP2PSignalHandler() {
        try {
            if (signalingClient != null) {
                // Use MESH_* signal types (same as GroupCallManager) for reliable routing
                // The server has optimized handlers for MESH_* signals
                signalingClient.addSignalHandler(SignalType.MESH_OFFER, this::handleMeshOffer);
                signalingClient.addSignalHandler(SignalType.MESH_ANSWER, this::handleMeshAnswer);
                signalingClient.addSignalHandler(SignalType.MESH_ICE_CANDIDATE, this::handleMeshIceCandidate);
                
                System.out.println("[P2P] ✅ P2P signal handlers registered (using MESH_* types for reliability)");
            }
        } catch (Exception e) {
            System.err.println("[P2P] Could not register P2P handler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Wrapper methods to handle MESH signals for P2P messaging
    private void handleMeshOffer(WebRTCSignal signal) {
        // Only handle if it's a P2P messaging signal (no roomId or roomId is empty)
        if (signal.getRoomId() == null || signal.getRoomId().isEmpty() || 
            signal.getRoomId().startsWith("p2p-")) {
            handleP2POffer(signal);
        }
        // Otherwise, let GroupCallManager handle it
    }
    
    private void handleMeshAnswer(WebRTCSignal signal) {
        if (signal.getRoomId() == null || signal.getRoomId().isEmpty() || 
            signal.getRoomId().startsWith("p2p-")) {
            handleP2PAnswer(signal);
        }
    }
    
    private void handleMeshIceCandidate(WebRTCSignal signal) {
        // Check if this ICE candidate is for a P2P connection
        String from = signal.getFrom();
        if (activeConnections.containsKey(from) || pendingConnections.containsKey(from)) {
            handleIceCandidate(signal);
        } else if (signal.getRoomId() == null || signal.getRoomId().isEmpty() || 
                   signal.getRoomId().startsWith("p2p-")) {
            handleIceCandidate(signal);
        }
        // Otherwise, let GroupCallManager handle it
    }
    
    /**
     * Create WebRTC P2P connection with a friend
     * Called when friend comes online (from FriendsController)
     */
    public CompletableFuture<Boolean> createConnection(String targetUsername) {
        System.out.printf("[P2P] Creating P2P connection to: %s%n", targetUsername);
        
        // ⚡ LAZY LOAD: Initialize WebRTC only when actually creating a connection
        ensureWebRTCReady();
        
        // Check if already connected
        if (activeConnections.containsKey(targetUsername)) {
            System.out.printf("[P2P] Already connected to %s%n", targetUsername);
            return CompletableFuture.completedFuture(true);
        }
        
        // Check if connection attempt in progress
        CompletableFuture<Boolean> existingFuture = pendingConnections.get(targetUsername);
        if (existingFuture != null) {
            System.out.printf("[P2P] Connection attempt already in progress for %s%n", targetUsername);
            return existingFuture;
        }
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConnections.put(targetUsername, future);
        
        try {
            // Create P2P connection (offer side)
            P2PConnection connection = new P2PConnection(targetUsername, true);
            connection.createPeerConnection();
            connection.createDataChannel();
            
            // Store in activeConnections immediately so we can handle P2P_ANSWER and ICE candidates
            activeConnections.put(targetUsername, connection);
            
            // Create offer and send via signaling
            connection.peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    connection.peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            System.out.printf("[P2P] Local description set for %s%n", targetUsername);
                            
                            // Use MESH_OFFER for reliable signal routing (same as GroupCallManager)
                            String p2pRoomId = "p2p-" + myUsername + "-" + targetUsername;
                            connection.callId = p2pRoomId;  // Store for ICE candidates
                            
                            WebRTCSignal signal = WebRTCSignal.newBuilder()
                                .setType(SignalType.MESH_OFFER)  // Changed from P2P_OFFER
                                .setFrom(myUsername)
                                .setTo(targetUsername)
                                .setRoomId(p2pRoomId)  // Use roomId like GroupCallManager
                                .setSdp(description.sdp)
                                .setTimestamp(System.currentTimeMillis())
                                .build();
                            
                            signalingClient.sendSignal(signal);  // Use sendSignal like GroupCallManager
                            System.out.printf("[P2P] MESH_OFFER sent to %s (roomId: %s)%n", targetUsername, p2pRoomId);
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] Failed to set local description: %s%n", error);
                            future.complete(false);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to create offer: %s%n", error);
                    future.complete(false);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error creating connection: %s%n", e.getMessage());
            e.printStackTrace();
            future.complete(false);
            pendingConnections.remove(targetUsername);
        }
        
        return future;
    }
    
    /**
     * Handle incoming WebRTC signals (now using MESH_* types for reliability)
     * This method is kept for backward compatibility but signals now come through
     * the registered handlers (handleMeshOffer, handleMeshAnswer, handleMeshIceCandidate)
     */
    private void handleIncomingSignal(WebRTCSignal signal) {
        // Handle both old P2P_* and new MESH_* signal types
        SignalType type = signal.getType();
        String remoteUsername = signal.getFrom();
        
        System.out.printf("[P2P] Received %s from %s%n", type, remoteUsername);
        
        try {
            switch (type) {
                // New MESH_* types (preferred)
                case MESH_OFFER:
                    handleP2POffer(signal);
                    break;
                    
                case MESH_ANSWER:
                    handleP2PAnswer(signal);
                    break;
                    
                case MESH_ICE_CANDIDATE:
                    handleIceCandidate(signal);
                    break;
                    
                // Legacy P2P_* types (backward compatibility)
                case P2P_OFFER:
                    handleP2POffer(signal);
                    break;
                    
                case P2P_ANSWER:
                    handleP2PAnswer(signal);
                    break;
                    
                case ICE_CANDIDATE:
                    handleIceCandidate(signal);
                    break;
                    
                default:
                    break;
            }
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling signal: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming MESH_OFFER for P2P messaging (create answer)
     * Now uses MESH_* signal types for reliable routing
     */
    private void handleP2POffer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        String incomingRoomId = signal.getRoomId();  // Use roomId like GroupCallManager
        System.out.printf("[P2P] Handling MESH_OFFER from %s (roomId: %s)%n", remoteUsername, incomingRoomId);
        
        // ⚡ LAZY LOAD: Initialize WebRTC for incoming P2P connections
        ensureWebRTCReady();
        
        // Skip if already have active connection
        if (activeConnections.containsKey(remoteUsername)) {
            P2PConnection existing = activeConnections.get(remoteUsername);
            if (existing.isActive()) {
                System.out.printf("[P2P] Already connected to %s, ignoring offer%n", remoteUsername);
                return;
            }
        }
        
        try {
            // Create P2P connection (answer side)
            P2PConnection connection = new P2PConnection(remoteUsername, false);
            connection.callId = incomingRoomId;  // Store roomId for ICE candidates
            connection.createPeerConnection();
            
            // Store in activeConnections for ICE candidate handling
            activeConnections.put(remoteUsername, connection);
            
            // Set remote description (offer)
            RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.OFFER, signal.getSdp());
            connection.peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.printf("[P2P] Remote description set for %s%n", remoteUsername);
                    
                    // Create answer
                    connection.peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            connection.peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                                @Override
                                public void onSuccess() {
                                    System.out.printf("[P2P] Answer created for %s%n", remoteUsername);
                                    
                                    // Send MESH_ANSWER (changed from P2P_ANSWER)
                                    WebRTCSignal answerSignal = WebRTCSignal.newBuilder()
                                        .setType(SignalType.MESH_ANSWER)  // Use MESH_ANSWER
                                        .setFrom(myUsername)
                                        .setTo(remoteUsername)
                                        .setRoomId(incomingRoomId)  // Use roomId
                                        .setSdp(description.sdp)
                                        .setTimestamp(System.currentTimeMillis())
                                        .build();
                                    
                                    signalingClient.sendSignal(answerSignal);  // Use sendSignal
                                    System.out.printf("[P2P] MESH_ANSWER sent to %s (roomId: %s)%n", remoteUsername, incomingRoomId);
                                }
                                
                                @Override
                                public void onFailure(String error) {
                                    System.err.printf("[P2P] Failed to set local description: %s%n", error);
                                }
                            });
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] Failed to create answer: %s%n", error);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to set remote description: %s%n", error);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling MESH_OFFER: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming P2P_ANSWER
     */
    private void handleP2PAnswer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        System.out.printf("[P2P] Handling P2P_ANSWER from %s%n", remoteUsername);
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] No pending connection for %s%n", remoteUsername);
            return;
        }
        
        try {
            RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.ANSWER, signal.getSdp());
            connection.peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.printf("[P2P] Remote answer set for %s%n", remoteUsername);
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to set remote answer: %s%n", error);
                }
            });
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling P2P_ANSWER: %s%n", e.getMessage());
        }
    }
    
    /**
     * Handle incoming ICE candidate
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] No connection for ICE candidate from %s%n", remoteUsername);
            return;
        }
        
        try {
            RTCIceCandidate candidate = new RTCIceCandidate(
                signal.getSdpMid(),
                signal.getSdpMLineIndex(),
                signal.getCandidate()
            );
            connection.peerConnection.addIceCandidate(candidate);
        } catch (Exception e) {
            System.err.printf("[P2P] Error adding ICE candidate: %s%n", e.getMessage());
        }
    }
    
    /**
     * Check if we have active P2P connection with user
     */
    public boolean hasActiveConnection(String username) {
        P2PConnection conn = activeConnections.get(username);
        return conn != null && conn.isActive();
    }
    
    /**
     * Send message via WebRTC DataChannel with reliable messaging protocol
     * Called from ChatService.sendMessage()
     */
    public CompletableFuture<Boolean> sendMessage(String targetUsername, String message) {
        P2PConnection connection = activeConnections.get(targetUsername);
        if (connection == null || !connection.isActive()) {
            System.err.printf("[P2P] No active connection to %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        if (connection.reliableMessaging == null) {
            System.err.printf("[P2P] Reliable messaging not initialized for %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            // Send via reliable messaging protocol (with chunking, ACK, NACK, CRC)
            System.out.printf("[P2P] Sending reliable message to %s: %s%n", targetUsername, message);
            return connection.reliableMessaging.sendMessage(targetUsername, message);
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error sending message: %s%n", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * Send file via WebRTC DataChannel with file transfer protocol
     */
    public CompletableFuture<Boolean> sendFile(String targetUsername, java.nio.file.Path filePath) {
        return sendFile(targetUsername, filePath, null);
    }

    /**
     * Send file via WebRTC DataChannel with file transfer protocol and observer callbacks
     */
    public CompletableFuture<Boolean> sendFile(String targetUsername,
                                               java.nio.file.Path filePath,
                                               FileTransferObserver observer) {
        P2PConnection connection = activeConnections.get(targetUsername);
        if (connection == null || !connection.isActive()) {
            System.err.printf("[P2P] No active connection to %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        if (connection.fileTransfer == null) {
            connection.initializeFileTransfer();
        }
        if (connection.fileTransfer == null) {
            System.err.printf("[P2P] File transfer not initialized for %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            long fileSize = Files.size(filePath);
            long fileId = System.currentTimeMillis();
            if (observer != null) {
                observer.onTransferStarted(fileId, filePath, fileSize);
            }
            System.out.println("[FT-SENDER] ═══════════════════════════════════════════════");
            System.out.printf("[FT-SENDER] sendFile() called: fileId=%d, file=%s, size=%d bytes%n", 
                fileId, filePath.getFileName(), fileSize);
            System.out.println("[FT-SENDER] ═══════════════════════════════════════════════");
            
            CompletableFuture<Void> readyFuture = connection.fileTransfer.awaitReceiverReady(fileId);
            connection.sendControlMessage(connection.buildUrReceiverControl(
                fileId, fileSize, filePath.getFileName().toString()));
            
            return readyFuture.orTimeout(RECEIVER_READY_TIMEOUT_SEC, TimeUnit.SECONDS)
                .thenCompose(v -> connection.fileTransfer.sendFile(filePath, fileId, observer))
                .exceptionally(ex -> {
                    System.err.printf("[P2P] File transfer failed: %s%n", ex.getMessage());
                    if (observer != null) observer.onTransferFailed(fileId, ex);
                    return false;
                });
        } catch (Exception e) {
            System.err.printf("[P2P] Error preparing file transfer: %s%n", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * P2P Connection class (inner class)
     * Represents one WebRTC peer connection with DataChannel
     */
    private class P2PConnection {
        final String remoteUsername;
        String callId;  // P2P-specific callId for signal routing
        
        RTCPeerConnection peerConnection;
        RTCDataChannel dataChannel;
        RTCDataChannel fileDataChannel;
        DataChannelReliableMessaging reliableMessaging;  // Reliable messaging protocol
        
        // File transfer: DataChannelFileTransfer coordinates roles, uses original file_transfer/
        DataChannelFileTransfer fileTransfer;
        
        volatile boolean active = false;
        
        P2PConnection(String remoteUsername, boolean isOfferer) {
            this.remoteUsername = remoteUsername;
            // isOfferer not needed - WebRTC handles offer/answer automatically
        }
        
        void createPeerConnection() {
            if (factory == null) {
                System.err.println("[P2P] WebRTC factory not initialized");
                return;
            }
            
            // Configure STUN/TURN servers for NAT traversal
            List<RTCIceServer> iceServers = new ArrayList<>();
            
            // Google STUN servers
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls.add("stun:stun.l.google.com:19302");
            stunServer.urls.add("stun:stun1.l.google.com:19302");
            stunServer.urls.add("stun:stun2.l.google.com:19302");
            iceServers.add(stunServer);
            
            // TODO: Add TURN server for symmetric NAT
            // RTCIceServer turnServer = new RTCIceServer();
            // turnServer.urls.add("turn:your-turn-server.com:3478");
            // turnServer.username = "username";
            // turnServer.password = "password";
            // iceServers.add(turnServer);
            
            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;  // Try all candidates (relay, srflx, host)
            
            System.out.printf("[P2P] Configuring peer connection with %d ICE servers%n", iceServers.size());
            
            // Create peer connection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    System.out.printf("[P2P] ICE candidate generated for %s%n", remoteUsername);
                    
                    // Use MESH_ICE_CANDIDATE for reliable routing (same as GroupCallManager)
                    WebRTCSignal signal = WebRTCSignal.newBuilder()
                        .setType(SignalType.MESH_ICE_CANDIDATE)  // Changed from ICE_CANDIDATE
                        .setFrom(myUsername)
                        .setTo(remoteUsername)
                        .setRoomId(callId)  // Use roomId instead of callId
                        .setCandidate(candidate.sdp)
                        .setSdpMid(candidate.sdpMid)
                        .setSdpMLineIndex(candidate.sdpMLineIndex)
                        .setTimestamp(System.currentTimeMillis())
                        .build();
                    
                    signalingClient.sendSignal(signal);  // Use sendSignal like GroupCallManager
                    System.out.printf("[P2P] MESH_ICE_CANDIDATE sent to %s (roomId: %s)%n", remoteUsername, callId);
                }
                
                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    System.out.printf("[P2P] ICE state: %s (with %s)%n", state, remoteUsername);
                    
                    if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                        System.out.printf("[P2P] ICE connected with %s%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.FAILED) {
                        System.err.printf("[P2P] ICE connection FAILED with %s%n", remoteUsername);
                        System.err.println("[P2P] Possible causes:");
                        System.err.println("    1. Both clients behind symmetric NAT (need TURN server)");
                        System.err.println("    2. Firewall blocking UDP traffic");
                        System.err.println("    3. Testing on localhost (try different networks)");
                        System.err.println("    4. STUN servers unreachable");
                        
                        // Clean up failed connection
                        activeConnections.remove(remoteUsername);
                        pendingConnections.remove(remoteUsername);
                        
                        // TODO: Fallback to server relay
                    } else if (state == RTCIceConnectionState.DISCONNECTED) {
                        System.err.printf("[P2P] ICE disconnected with %s (may reconnect)%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.CLOSED) {
                        System.out.printf("[P2P] ICE connection closed with %s%n", remoteUsername);
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    String label = channel.getLabel();
                    System.out.printf("[P2P] DataChannel received (%s) from %s%n", label, remoteUsername);
                    if ("file-transfer".equals(label)) {
                        attachFileChannel(channel);
                    } else {
                        attachMessagingChannel(channel);
                    }
                }
            });
            
            System.out.printf("[P2P] Peer connection created for %s%n", remoteUsername);
        }
        
        void createDataChannel() {
            if (peerConnection == null) {
                System.err.println("[P2P] Cannot create DataChannel - peer connection not created");
                return;
            }
            
            // Create messaging channel (offer side)
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true; // LLS protocol needs ordered delivery
            RTCDataChannel messagingChannel = peerConnection.createDataChannel("messaging", init);
            attachMessagingChannel(messagingChannel);
            
            // Create dedicated file-transfer channel
            RTCDataChannelInit ftInit = new RTCDataChannelInit();
            ftInit.ordered = true;
            RTCDataChannel ftChannel = peerConnection.createDataChannel("file-transfer", ftInit);
            attachFileChannel(ftChannel);
            
            System.out.printf("[P2P] DataChannels created for %s%n", remoteUsername);
        }
        
        private void attachMessagingChannel(RTCDataChannel channel) {
            this.dataChannel = channel;
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}
                
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = channel.getState();
                    System.out.printf("[P2P] DataChannel state (messaging): %s (with %s)%n",
                        state, remoteUsername);
                    
                    if (state == RTCDataChannelState.OPEN) {
                        active = true;
                        activeConnections.put(remoteUsername, P2PConnection.this);
                        initializeReliableMessaging();
                        
                        CompletableFuture<Boolean> future = pendingConnections.remove(remoteUsername);
                        if (future != null) {
                            future.complete(true);
                        }
                    } else if (state == RTCDataChannelState.CLOSED) {
                        active = false;
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    handleMessagingChannelMessage(buffer);
                }
            });
        }
        
        private void attachFileChannel(RTCDataChannel channel) {
            this.fileDataChannel = channel;
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}
                
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = channel.getState();
                    System.out.printf("[P2P] DataChannel state (file-transfer): %s (with %s)%n",
                        state, remoteUsername);
                    
                    if (state == RTCDataChannelState.OPEN) {
                        initializeFileTransfer();
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    if (fileTransfer != null) {
                        fileTransfer.handleIncomingMessage(buffer);
                    } else {
                        System.err.printf("[P2P] File transfer handler not ready for %s%n", remoteUsername);
                    }
                }
            });
        }
        
        /**
         * Initialize reliable messaging protocol over DataChannel
         */
        void initializeReliableMessaging() {
            if (dataChannel == null || dataChannel.getState() != RTCDataChannelState.OPEN) {
                System.err.println("[P2P] Cannot initialize reliable messaging - DataChannel not open");
                return;
            }
            
            reliableMessaging = new DataChannelReliableMessaging(myUsername, dataChannel);
            
            // Set callback for completed messages
            reliableMessaging.setCompletionCallback((sender, messageId, messageBytes) -> {
                try {
                    String messageText = new String(messageBytes, StandardCharsets.UTF_8);
                    System.out.printf("[P2P] Reliable message complete from %s: %s%n", 
                        remoteUsername, messageText);
                    
                    if (handleFileTransferControl(messageText)) {
                        return;
                    }
                    
                    // Forward to ChatService
                    javafx.application.Platform.runLater(() -> {
                        try {
                            com.saferoom.gui.service.ChatService chatService = 
                                com.saferoom.gui.service.ChatService.getInstance();
                            
                            // FIXED: Call receiveP2PMessage() instead of direct add
                            // This ensures persistence hooks are triggered!
                            chatService.receiveP2PMessage(remoteUsername, myUsername, messageText);
                            
                            // Update contact last message
                            com.saferoom.gui.service.ContactService.getInstance()
                                .updateLastMessage(remoteUsername, messageText, false);
                                
                            System.out.printf("[P2P] Message forwarded to ChatService for persistence%n");
                            
                        } catch (Exception e) {
                            System.err.println("[P2P] Error forwarding message: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                    
                } catch (Exception e) {
                    System.err.printf("[P2P] Error processing completed message: %s%n", e.getMessage());
                }
            });
            
            System.out.printf("[P2P] Reliable messaging initialized for %s%n", remoteUsername);
            
            // Also initialize file transfer (if not already initialized)
            if (fileTransfer == null) {
                initializeFileTransfer();
            }
        }
        
        /**
         * Initialize file transfer protocol over DataChannel
         * Uses DataChannelFileTransfer which coordinates roles and uses original file_transfer/
         */
        void initializeFileTransfer() {
            if (fileDataChannel == null || fileDataChannel.getState() != RTCDataChannelState.OPEN) {
                System.err.println("[P2P] Cannot initialize file transfer - file DataChannel not open");
                return;
            }
            
            try {
                // Create DataChannelFileTransfer (handles wrapper + original classes)
                fileTransfer = new DataChannelFileTransfer(myUsername, fileDataChannel, remoteUsername);
                fileTransfer.setTransferCallback((sender, fileId, path, size) -> {
                    javafx.application.Platform.runLater(() -> {
                        com.saferoom.gui.service.ChatService.getInstance()
                            .handleIncomingFile(sender, path, size);
                    });
                });
                
                // DON'T start receiver here! It will start LAZY on first SYN
                System.out.printf("[P2P] File transfer initialized for %s (receiver will start on SYN)%n", 
                    remoteUsername);
                
            } catch (Exception e) {
                System.err.printf("[P2P] Failed to initialize file transfer: %s%n", e.getMessage());
                e.printStackTrace();
            }
        }
        
        void handleMessagingChannelMessage(RTCDataChannelBuffer buffer) {
            try {
                ByteBuffer data = buffer.data.duplicate();
                if (data.remaining() < 1) return;
                
                byte signal = data.get(0);
                
                System.out.printf("[MSG-CH-RECV] Received signal 0x%02X (%d bytes) from %s%n",
                    signal, data.remaining(), remoteUsername);
                
                // 🔥 NEW: File transfer control signal (0xF0) - direct handling
                if (signal == (byte) 0xF0) {
                    System.out.println("[MSG-CH-RECV] ✅ File transfer control signal detected (0xF0)");
                    data.position(1); // Skip signal byte
                    byte[] payloadBytes = new byte[data.remaining()];
                    data.get(payloadBytes);
                    String messageText = new String(payloadBytes, StandardCharsets.UTF_8);
                    
                    System.out.printf("[MSG-CH-RECV] Control payload: %s%n", 
                        messageText.length() > 100 ? messageText.substring(0, 100) + "..." : messageText);
                    
                    handleFileTransferControl(messageText);
                    return;
                }
                
                // Messaging signals only (0x20-0x23)
                if (signal >= 0x20 && signal <= 0x23) {
                    if (reliableMessaging != null) {
                        reliableMessaging.handleIncomingMessage(buffer);
                    } else {
                        System.err.println("[MSG-CH-RECV] ERROR: Reliable messaging not initialized");
                    }
                } else {
                    System.err.printf("[MSG-CH-RECV] WARNING: Unexpected signal on messaging channel: 0x%02X%n", signal);
                }
                
            } catch (Exception e) {
                System.err.println("[P2P] Error handling DataChannel message: " + e.getMessage());
            }
        }
        
        private boolean handleFileTransferControl(String messageText) {
            if (!messageText.startsWith(FILE_CTRL_PREFIX)) {
                return false;
            }
            
            System.out.printf("[FT-CTRL-RECV] Control message received: %s%n", 
                messageText.length() > 100 ? messageText.substring(0, 100) + "..." : messageText);
            
            String[] parts = messageText.split("\\|");
            if (parts.length < 3) {
                System.err.println("[FT-CTRL-RECV] ERROR: Malformed control message (parts < 3)");
                return true;
            }
            
            String type = parts[1];
            System.out.printf("[FT-CTRL-RECV] Control type: %s%n", type);
            
            if (CTRL_UR_RECEIVER.equals(type)) {
                if (parts.length < 5) {
                    System.err.println("[FT-CTRL-RECV] ERROR: UR_RECEIVER malformed (parts < 5)");
                    return true;
                }
                
                long fileId = parseLongSafe(parts[2]);
                String fileName = decodeFileName(parts[4]);
                
                System.out.println("[FT-CTRL-RECV] ╔════════════════════════════════════════════════");
                System.out.printf("[FT-CTRL-RECV] ║ UR_RECEIVER: fileId=%d, fileName=%s%n", fileId, fileName);
                System.out.println("[FT-CTRL-RECV] ╚════════════════════════════════════════════════");
                
                if (fileTransfer == null) {
                    System.out.println("[FT-CTRL-RECV] Initializing file transfer (was null)...");
                    initializeFileTransfer();
                }
                
                if (fileTransfer != null) {
                    fileTransfer.prepareIncomingFile(fileId, fileName);
                    
                    // 🔥 CRITICAL FIX: Send OK_SNDFILE BEFORE starting receiver!
                    System.out.println("[FT-CTRL-RECV] 🚀 Sending OK_SNDFILE to unblock sender...");
                    sendControlMessage(buildOkControl(fileId));
                    
                    // Small safety delay to ensure OK_SNDFILE is sent first
                    try {
                        Thread.sleep(10); // 10ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    System.out.println("[FT-CTRL-RECV] Starting receiver (will block in handshake)...");
                    fileTransfer.startPreparedReceiver(fileId);
                } else {
                    System.err.println("[FT-CTRL-RECV] ERROR: File transfer still null after initialization!");
                }
            } else if (CTRL_OK_SNDFILE.equals(type)) {
                long fileId = parseLongSafe(parts[2]);
                System.out.println("[FT-CTRL-RECV] ╔════════════════════════════════════════════════");
                System.out.printf("[FT-CTRL-RECV] ║ OK_SNDFILE received: fileId=%d%n", fileId);
                System.out.println("[FT-CTRL-RECV] ║ Unblocking sender readyFuture...");
                System.out.println("[FT-CTRL-RECV] ╚════════════════════════════════════════════════");
                
                if (fileTransfer != null) {
                    fileTransfer.markReceiverReady(fileId);
                } else {
                    System.err.println("[FT-CTRL-RECV] ERROR: Cannot mark receiver ready, fileTransfer is null!");
                }
            }
            
            return true;
        }
        
        private void sendControlMessage(String payload) {
            System.out.printf("[FT-CTRL-SEND] Sending: %s%n", 
                payload.length() > 100 ? payload.substring(0, 100) + "..." : payload);
            System.out.printf("[FT-CTRL-SEND] Channel state: %s, Messaging ready: %s%n",
                dataChannel != null ? dataChannel.getState() : "NULL",
                reliableMessaging != null ? "YES" : "NO");
            
            // 🔥 CRITICAL FIX: Use DIRECT DataChannel for file transfer control (bypass reliable messaging)
            // Reason: Reliable messaging may not complete in time, causing deadlock
            if (dataChannel == null || dataChannel.getState() != RTCDataChannelState.OPEN) {
                System.err.println("[FT-CTRL-SEND] ERROR: DataChannel not ready!");
                return;
            }
            
            try {
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                // Use signal 0xF0 for file transfer control (different from messaging 0x20-0x23)
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1 + payloadBytes.length);
                buffer.put((byte) 0xF0); // File transfer control signal
                buffer.put(payloadBytes);
                buffer.flip();
                
                RTCDataChannelBuffer dcBuffer = new RTCDataChannelBuffer(buffer, true);
                dataChannel.send(dcBuffer);
                System.out.println("[FT-CTRL-SEND] ✅ Control message sent DIRECTLY via DataChannel (signal 0xF0)");
            } catch (Exception e) {
                System.err.printf("[FT-CTRL-SEND] ❌ Failed to send control message: %s%n", e.getMessage());
                e.printStackTrace();
            }
        }
        
        private String buildUrReceiverControl(long fileId, long fileSize, String fileName) {
            String encodedName = BASE64_ENCODER.encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
            return FILE_CTRL_PREFIX + "|" + CTRL_UR_RECEIVER + "|" + fileId + "|" + fileSize + "|" + encodedName;
        }
        
        private String buildOkControl(long fileId) {
            return FILE_CTRL_PREFIX + "|" + CTRL_OK_SNDFILE + "|" + fileId;
        }
        
        private long parseLongSafe(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        
        private String decodeFileName(String encoded) {
            try {
                return new String(BASE64_DECODER.decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return "file.bin";
            }
        }
        
        boolean isActive() {
            return active && dataChannel != null && 
                   dataChannel.getState() == RTCDataChannelState.OPEN;
        }
    }
}
