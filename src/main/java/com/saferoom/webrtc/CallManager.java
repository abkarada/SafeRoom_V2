package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import com.saferoom.webrtc.screenshare.ScreenShareController;
import com.saferoom.webrtc.screenshare.ScreenShareManager;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Call Manager
 * Orchestrates WebRTCClient and WebRTCSignalingClient
 * Compatible with WebRTCSessionManager (server) and UDPHoleImpl
 */
public class CallManager {
    
    private static CallManager instance;
    
    private String myUsername;
    private WebRTCSignalingClient signalingClient;
    private boolean isInitialized = false; // 🔧 Track initialization state
    
    // Current call state
    private CallState currentState = CallState.IDLE;
    private String currentCallId;
    private String remoteUsername;
    private boolean isOutgoingCall;
    
    private WebRTCClient webrtcClient;
    private ScreenShareManager screenShareManager;
    private ScreenShareController screenShareController;
    
    // GUI Callbacks
    private Consumer<IncomingCallInfo> onIncomingCallCallback;
    private Consumer<String> onCallAcceptedCallback;
    private Consumer<String> onCallRejectedCallback;
    private Consumer<String> onCallEndedCallback;
    private Runnable onCallConnectedCallback;
    private Consumer<MediaStreamTrack> onRemoteTrackCallback;
    private Runnable onRemoteScreenShareStoppedCallback; // Screen share stopped callback
    
    /**
     * Call states (matching server-side WebRTCSessionManager.CallState)
     */
    public enum CallState {
        IDLE,       // No call
        RINGING,    // Outgoing/incoming call ringing
        CONNECTING, // SDP/ICE exchange in progress
        CONNECTED,  // Call established
        ENDED       // Call ended
    }
    
    /**
     * Incoming call info
     */
    public static class IncomingCallInfo {
        public final String callId;
        public final String callerUsername;
        public final boolean audioEnabled;
        public final boolean videoEnabled;
        public final long timestamp;
        
        public IncomingCallInfo(String callId, String callerUsername, boolean audio, boolean video, long timestamp) {
            this.callId = callId;
            this.callerUsername = callerUsername;
            this.audioEnabled = audio;
            this.videoEnabled = video;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Private constructor (singleton)
     */
    private CallManager() {}
    
    /**
     * Get singleton instance
     */
    public static synchronized CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }
    
    // ⚡ LAZY LOADING flags
    private boolean signalingInitialized = false;
    private boolean webrtcInitialized = false;
    
    /**
     * Initialize ONLY signaling (lightweight - no WebRTC native libs)
     * Used at app startup to receive call notifications without loading heavy libs
     * ~0 MB additional RAM
     */
    public void initializeSignalingOnly(String username) {
        if (signalingInitialized) {
            return;
        }
        
        this.myUsername = username;
        
        System.out.printf("[CallManager] ⚡ Initializing signaling only for user: %s (WebRTC deferred)%n", username);
        
        // Create signaling client (lightweight gRPC - ~5MB)
        signalingClient = new WebRTCSignalingClient(username);
        
        // Set incoming signal handler
        signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        
        // Start signaling stream for real-time signals
        signalingClient.startSignalingStream();
        
        this.signalingInitialized = true;
        
        System.out.println("[CallManager] ⚡ Signaling ready (WebRTC will load on first call)");
    }
    
    /**
     * Ensure WebRTC is fully initialized (call before making/receiving calls)
     * This loads the heavy native libraries (~100-150MB)
     */
    public synchronized void ensureWebRTCInitialized() {
        if (webrtcInitialized) {
            return;
        }
        
        System.out.println("[CallManager] 🎬 Loading WebRTC native libraries...");
        long startTime = System.currentTimeMillis();
        
        // Initialize WebRTC client library (HEAVY - ~100-150MB)
        if (!WebRTCClient.isInitialized()) {
            WebRTCClient.initialize();
        }
        
        webrtcInitialized = true;
        isInitialized = true;
        
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("[CallManager] ✅ WebRTC loaded in %dms%n", elapsed);
    }
    
    /**
     * Full initialization (backward compatible - loads everything)
     * @deprecated Use initializeSignalingOnly() + ensureWebRTCInitialized() for lazy loading
     */
    public void initialize(String username) {
        // 🔧 Prevent re-initialization
        if (isInitialized) {
            System.out.printf("[CallManager] ⚠️ Already initialized for user: %s (current: %s)%n", myUsername, username);
            return;
        }
        
        // Initialize signaling first
        initializeSignalingOnly(username);
        
        // Then load WebRTC
        ensureWebRTCInitialized();
        
        System.out.println("[CallManager] ✅ Full initialization complete");
    }
    
    /**
     * Check if CallManager is initialized (signaling ready)
     */
    public boolean isInitialized() {
        return signalingInitialized;
    }
    
    /**
     * Check if WebRTC native libs are loaded
     */
    public boolean isWebRTCReady() {
        return webrtcInitialized;
    }
    
    // ===============================
    // Outgoing Call Flow
    // ===============================
    
    /**
     * Start outgoing call
     */
    public CompletableFuture<String> startCall(String targetUsername, boolean audioEnabled, boolean videoEnabled) {
        if (currentState != CallState.IDLE) {
            System.err.printf("[CallManager] ❌ Cannot start call - current state: %s%n", currentState);
            return CompletableFuture.failedFuture(new IllegalStateException("Already in a call"));
        }
        
        // ⚡ LAZY LOAD: Initialize WebRTC only when actually making a call
        ensureWebRTCInitialized();
        
        System.out.printf("[CallManager] 📞 Starting call to %s (audio=%b, video=%b)%n", 
            targetUsername, audioEnabled, videoEnabled);
        
        this.remoteUsername = targetUsername;
        this.isOutgoingCall = true;
        this.currentState = CallState.RINGING;
        
        // Send CALL_REQUEST to server
        return signalingClient.sendCallRequest(targetUsername, audioEnabled, videoEnabled)
            .thenApply(callId -> {
                this.currentCallId = callId;
                System.out.printf("[CallManager] ✅ Call request sent, callId: %s%n", callId);
                
                // Create WebRTC peer connection
                webrtcClient = new WebRTCClient(callId, targetUsername);
                webrtcClient.createPeerConnection(audioEnabled, videoEnabled);
                ensureScreenShareController();
                
                // 🎤 Add audio track if audio enabled
                if (audioEnabled) {
                    System.out.println("[CallManager] 🎤 Adding audio track for outgoing call...");
                    webrtcClient.addAudioTrack();
                }
                
                // 📹 Add video track if video enabled
                if (videoEnabled) {
                    System.out.println("[CallManager] 📹 Adding video track for outgoing call...");
                    webrtcClient.addVideoTrack();
                    registerCameraWithScreenShareController();
                }
                
                // Set up callbacks
                setupWebRTCCallbacks();
                
                return callId;
            })
            .exceptionally(e -> {
                System.err.printf("[CallManager] ❌ Failed to start call: %s%n", e.getMessage());
                this.currentState = CallState.IDLE;
                return null;
            });
    }
    
    /**
     * Cancel outgoing call (before accepted)
     */
    public void cancelCall() {
        if (!isOutgoingCall || currentState != CallState.RINGING) {
            System.err.println("[CallManager] ⚠️ No outgoing call to cancel");
            return;
        }
        
        System.out.printf("[CallManager] 🚫 Cancelling call: %s%n", currentCallId);
        
        // Send CALL_CANCEL
        signalingClient.sendCallCancel(currentCallId, remoteUsername);
        
        // Cleanup
        cleanup();
    }
    
    // ===============================
    // Incoming Call Flow
    // ===============================
    
    /**
     * Accept incoming call
     */
    public void acceptCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            System.err.println("[CallManager] ⚠️ No incoming call to accept");
            return;
        }
        
        // ⚡ LAZY LOAD: Initialize WebRTC only when actually accepting a call
        ensureWebRTCInitialized();
        
        System.out.printf("[CallManager] ✅ Accepting call: %s%n", callId);
        
        // Send CALL_ACCEPT
        boolean success = signalingClient.sendCallAccept(callId, remoteUsername);
        
        if (success) {
            this.currentState = CallState.CONNECTING;
            
            // 🔧 DON'T create answer here! Wait for OFFER to arrive first.
            // Answer will be created in handleOffer() after remote description is set.
            System.out.println("[CallManager] ⏳ Waiting for SDP offer from caller...");
            
            if (onCallAcceptedCallback != null) {
                onCallAcceptedCallback.accept(callId);
            }
        } else {
            System.err.println("[CallManager] ❌ Failed to accept call");
            cleanup();
        }
    }
    
    /**
     * Reject incoming call
     */
    public void rejectCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            System.err.println("[CallManager] ⚠️ No incoming call to reject");
            return;
        }
        
        System.out.printf("[CallManager] ❌ Rejecting call: %s%n", callId);
        
        // Send CALL_REJECT
        signalingClient.sendCallReject(callId, remoteUsername);
        
        if (onCallRejectedCallback != null) {
            onCallRejectedCallback.accept(callId);
        }
        
        // Cleanup
        cleanup();
    }
    
    // ===============================
    // Active Call Management
    // ===============================
    
    /**
     * End active call
     */
    public void endCall() {
        if (currentState == CallState.IDLE || currentState == CallState.ENDED) {
            System.err.println("[CallManager] ⚠️ No active call to end");
            return;
        }
        
        System.out.printf("[CallManager] 📴 Ending call: %s%n", currentCallId);
        
        // Send CALL_END
        signalingClient.sendCallEnd(currentCallId, remoteUsername);
        
        if (onCallEndedCallback != null) {
            onCallEndedCallback.accept(currentCallId);
        }
        
        // Cleanup
        cleanup();
    }
    
    /**
     * Toggle audio (mute/unmute)
     */
    public void toggleAudio(boolean enabled) {
        if (webrtcClient != null) {
            webrtcClient.toggleAudio(enabled);
        }
    }
    
    /**
     * Toggle video (on/off)
     */
    public void toggleVideo(boolean enabled) {
        if (webrtcClient != null) {
            webrtcClient.toggleVideo(enabled);
        }
    }
    
    // ===============================
    // Screen Sharing
    // ===============================
    
    // ===============================
    // Signal Handlers (from server)
    // ===============================
    
    /**
     * Handle incoming WebRTC signal
     */
    private void handleIncomingSignal(WebRTCSignal signal) {
        SignalType type = signal.getType();
        String from = signal.getFrom();
        String callId = signal.getCallId();
        
        // ✅ ROUTE P2P MESSAGING SIGNALS TO P2PConnectionManager
        // P2P signals: P2P_OFFER, P2P_ANSWER, and ICE_CANDIDATE with "p2p-" prefix
        boolean isP2PSignal = (type == SignalType.P2P_OFFER || type == SignalType.P2P_ANSWER ||
                               (type == SignalType.ICE_CANDIDATE && callId != null && callId.startsWith("p2p-")));
        
        if (isP2PSignal) {
            System.out.printf("[CallManager] Routing %s to P2PConnectionManager (callId: %s)%n", type, callId);
            try {
                com.saferoom.p2p.P2PConnectionManager p2pManager = 
                    com.saferoom.p2p.P2PConnectionManager.getInstance();
                
                // Call handleIncomingSignal via reflection
                java.lang.reflect.Method method = com.saferoom.p2p.P2PConnectionManager.class
                    .getDeclaredMethod("handleIncomingSignal", WebRTCSignal.class);
                method.setAccessible(true);
                method.invoke(p2pManager, signal);
                
                System.out.printf("[CallManager] Routed %s to P2PConnectionManager%n", type);
            } catch (Exception e) {
                System.err.printf("[CallManager] Failed to route P2P signal: %s%n", e.getMessage());
                e.printStackTrace();
            }
            return; // Don't process P2P signals in CallManager
        }
        
        // Handle voice/video call signals normally
        System.out.printf("[CallManager] Received %s from %s (callId: %s, currentState: %s)%n", 
            type, from, callId, currentState);
        
        switch (type) {
            case CALL_REQUEST:
                System.out.println("[CallManager] Processing CALL_REQUEST...");
                handleIncomingCallRequest(signal);
                break;
                
            case CALL_ACCEPT:
                System.out.println("[CallManager] Processing CALL_ACCEPT...");
                handleCallAccepted(signal);
                break;
                
            case CALL_REJECT:
            case CALL_CANCEL:
                System.out.println("[CallManager] Processing CALL_REJECT/CANCEL...");
                handleCallRejected(signal);
                break;
                
            case CALL_END:
                System.out.println("[CallManager] Processing CALL_END...");
                handleCallEnded(signal);
                break;
                
            case OFFER:
                System.out.println("[CallManager] Processing OFFER...");
                handleOffer(signal);
                break;
                
            case ANSWER:
                System.out.println("[CallManager] Processing ANSWER...");
                handleAnswer(signal);
                break;
                
            case ICE_CANDIDATE:
                System.out.println("[CallManager] Processing ICE_CANDIDATE...");
                handleIceCandidate(signal);
                break;
                
            case SCREEN_SHARE_OFFER:
                System.out.println("[CallManager] Processing SCREEN_SHARE_OFFER...");
                handleScreenShareOffer(signal);
                break;
                
            case SCREEN_SHARE_STOP:
                System.out.println("[CallManager] Processing SCREEN_SHARE_STOP...");
                handleScreenShareStop(signal);
                break;
                
            default:
                System.err.printf("[CallManager] Unknown signal type: %s%n", type);
        }
    }
    
    /**
     * Handle incoming call request
     */
    private void handleIncomingCallRequest(WebRTCSignal signal) {
        System.out.printf("[CallManager] Incoming call from %s (audio=%b, video=%b)%n",
            signal.getFrom(), signal.getAudioEnabled(), signal.getVideoEnabled());
        
        if (currentState != CallState.IDLE) {
            System.err.printf("[CallManager] Already in a call (state: %s) - rejecting%n", currentState);
            signalingClient.sendCallReject(signal.getCallId(), signal.getFrom());
            return;
        }
        
        this.currentCallId = signal.getCallId();
        this.remoteUsername = signal.getFrom();
        this.isOutgoingCall = false;
        this.currentState = CallState.RINGING;
        
        System.out.printf("[CallManager] Call state updated: RINGING (callId: %s)%n", currentCallId);
        
        // Create WebRTC peer connection
        webrtcClient = new WebRTCClient(currentCallId, remoteUsername);
        webrtcClient.createPeerConnection(signal.getAudioEnabled(), signal.getVideoEnabled());
        ensureScreenShareController();
        
        // 🎤 Add audio track if audio enabled
        if (signal.getAudioEnabled()) {
            System.out.println("[CallManager] Adding audio track for incoming call...");
            webrtcClient.addAudioTrack();
        }
        
        // 📹 Add video track if video enabled
        if (signal.getVideoEnabled()) {
            System.out.println("[CallManager] Adding video track for incoming call...");
            webrtcClient.addVideoTrack();
            registerCameraWithScreenShareController();
        }
        
        setupWebRTCCallbacks();
        
        // Notify GUI
        if (onIncomingCallCallback != null) {
            System.out.printf("[CallManager] Triggering incoming call callback for GUI...%n");
            IncomingCallInfo info = new IncomingCallInfo(
                signal.getCallId(),
                signal.getFrom(),
                signal.getAudioEnabled(),
                signal.getVideoEnabled(),
                signal.getTimestamp()
            );
            onIncomingCallCallback.accept(info);
            System.out.println("[CallManager] Incoming call callback triggered successfully");
        } else {
            System.err.println("[CallManager] WARNING: onIncomingCallCallback is NULL! Dialog won't show!");
        }
    }
    
    /**
     * Handle call accepted (for outgoing call)
     */
    private void handleCallAccepted(WebRTCSignal signal) {
        if (!isOutgoingCall) return;
        
        System.out.println("[CallManager] Call accepted by remote user");
        
        this.currentState = CallState.CONNECTING;
        
        // Create SDP offer
        webrtcClient.createOffer().thenAccept(sdp -> {
            // Send OFFER to callee
            signalingClient.sendOffer(currentCallId, remoteUsername, sdp);
            System.out.println("[CallManager] Offer sent");
        });
        
        if (onCallAcceptedCallback != null) {
            onCallAcceptedCallback.accept(currentCallId);
        }
    }
    
    /**
     * Handle call rejected/cancelled
     */
    private void handleCallRejected(WebRTCSignal signal) {
        System.out.println("[CallManager] Call rejected/cancelled");
        
        if (onCallRejectedCallback != null) {
            onCallRejectedCallback.accept(currentCallId);
        }
        
        cleanup();
    }
    
    /**
     * Handle call ended
     */
    private void handleCallEnded(WebRTCSignal signal) {
        System.out.println("[CallManager] Call ended by remote user");
        
        if (onCallEndedCallback != null) {
            onCallEndedCallback.accept(currentCallId);
        }
        
        cleanup();
    }
    
    /**
     * Handle SDP offer
     */
    private void handleOffer(WebRTCSignal signal) {
        System.out.println("[CallManager] Received SDP offer");
        
        // Set remote description
        webrtcClient.setRemoteDescription("offer", signal.getSdp());
        
        // 🔧 If we're the callee (incoming call accepted), create answer now
        if (!isOutgoingCall && currentState == CallState.CONNECTING) {
            System.out.println("[CallManager] Creating SDP answer (after remote offer set)...");
            webrtcClient.createAnswer().thenAccept(sdp -> {
                // Send ANSWER to caller
                signalingClient.sendAnswer(currentCallId, remoteUsername, sdp);
                System.out.println("[CallManager] Answer sent to caller");
            }).exceptionally(ex -> {
                System.err.printf("[CallManager] Failed to create answer: %s%n", ex.getMessage());
                return null;
            });
        }
    }
    
    /**
     * Handle SDP answer
     */
    private void handleAnswer(WebRTCSignal signal) {
        System.out.println("[CallManager] Received SDP answer");
        
        // Set remote description
        webrtcClient.setRemoteDescription("answer", signal.getSdp());
        
        // Mark as connected
        this.currentState = CallState.CONNECTED;
        
        if (onCallConnectedCallback != null) {
            onCallConnectedCallback.run();
        }
    }
    
    /**
     * Handle ICE candidate
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        System.out.println("[CallManager] Received ICE candidate");
        
        webrtcClient.addIceCandidate(
            signal.getCandidate(),
            signal.getSdpMid(),
            signal.getSdpMLineIndex()
        );
    }
    
    /**
     * Handle screen share offer (renegotiation)
     */
    private void handleScreenShareOffer(WebRTCSignal signal) {
        System.out.println("[CallManager] Received screen share offer - remote peer started sharing");
        
        if (webrtcClient == null) {
            System.err.println("[CallManager] WebRTC client not initialized");
            return;
        }
        
        String remoteSdp = signal.getSdp();
        
        // Set remote description (this is a renegotiation)
        webrtcClient.setRemoteDescription("offer", remoteSdp);
        System.out.println("[CallManager] Screen share offer set as remote description");
        
        // Create answer for renegotiation
        webrtcClient.createAnswer()
            .thenAccept(answerSdp -> {
                System.out.println("[CallManager] Sending answer for screen share");
                
                // Send answer back
                signalingClient.sendAnswer(currentCallId, remoteUsername, answerSdp);
                
                System.out.println("[CallManager] Screen share renegotiation complete");
            })
            .exceptionally(e -> {
                System.err.printf("[CallManager] Failed to handle screen share offer: %s%n", e.getMessage());
                e.printStackTrace();
                return null;
            });
    }
    
    /**
     * Handle screen share stop notification
     */
    private void handleScreenShareStop(WebRTCSignal signal) {
        System.out.println("[CallManager] 🛑 Remote peer stopped screen sharing");
        
        // Notify GUI that remote screen share ended
        if (onRemoteScreenShareStoppedCallback != null) {
            onRemoteScreenShareStoppedCallback.run();
        }
        
        System.out.println("[CallManager] ✅ Remote screen share stop handled");
    }
    
    // ===============================
    // WebRTC Callbacks Setup
    // ===============================
    
    /**
     * Setup WebRTC client callbacks
     */
    private void setupWebRTCCallbacks() {
        // SDP callback
        webrtcClient.setOnLocalSDPCallback(sdp -> {
            System.out.println("[CallManager] Local SDP generated");
            // SDP is sent in createOffer/createAnswer methods
        });
        
        // ICE candidate callback
        webrtcClient.setOnIceCandidateCallback(candidate -> {
            System.out.printf("[CallManager] ICE candidate generated: %s%n", candidate.sdp);
            
            // Send ICE candidate to remote peer via signaling
            signalingClient.sendIceCandidate(
                currentCallId, 
                remoteUsername, 
                candidate.sdp, 
                candidate.sdpMid, 
                candidate.sdpMLineIndex
            );
        });
        
        // Connection established callback
        webrtcClient.setOnConnectionEstablishedCallback(() -> {
            System.out.println("[CallManager] WebRTC connection established");
            this.currentState = CallState.CONNECTED;
            
            if (onCallConnectedCallback != null) {
                onCallConnectedCallback.run();
            }
        });
        
        // Connection closed callback
        webrtcClient.setOnConnectionClosedCallback(() -> {
            System.out.println("[CallManager] WebRTC connection closed");
            cleanup();
        });
        
        // Remote track callback (for video/audio tracks)
        webrtcClient.setOnRemoteTrackCallback(track -> {
            System.out.printf("[CallManager] Remote track received: %s (kind=%s)%n", 
                track.getId(), track.getKind());
            
            if (onRemoteTrackCallback != null) {
                onRemoteTrackCallback.accept(track);
            }
        });
    }

    private void ensureScreenShareController() {
        if (screenShareController != null || webrtcClient == null) {
            return;
        }
        PeerConnectionFactory factory = WebRTCClient.getFactory();
        if (factory == null) {
            System.err.println("[CallManager] ScreenShareController unavailable: factory is null");
            return;
        }
        RTCPeerConnection peerConnection = webrtcClient.getPeerConnection();
        if (peerConnection == null) {
            System.err.println("[CallManager] ScreenShareController unavailable: peer connection not ready");
            return;
        }
        screenShareManager = new ScreenShareManager(factory, peerConnection, new CallScreenShareRenegotiationHandler());
        screenShareController = new ScreenShareController(screenShareManager);
        registerCameraWithScreenShareController();
    }

    private void registerCameraWithScreenShareController() {
        if (screenShareController == null || webrtcClient == null) {
            return;
        }
        if (webrtcClient.getVideoSender() != null && webrtcClient.getLocalVideoTrack() != null) {
            screenShareController.registerCameraSource(
                webrtcClient.getVideoSender(),
                webrtcClient.getLocalVideoTrack()
            );
        }
    }

    private final class CallScreenShareRenegotiationHandler implements ScreenShareManager.RenegotiationHandler {
        @Override
        public void onScreenShareOffer(String sdp) {
            if (signalingClient == null || currentCallId == null || remoteUsername == null) {
                return;
            }
            signalingClient.sendScreenShareOffer(currentCallId, remoteUsername, sdp);
        }

        @Override
        public void onScreenShareStopped(String sdp) {
            if (signalingClient == null || currentCallId == null || remoteUsername == null) {
                return;
            }
            signalingClient.sendScreenShareStop(currentCallId, remoteUsername);
        }
    }
    
    // ===============================
    // Cleanup
    // ===============================
    
    /**
     * Cleanup call resources
     */
    private void cleanup() {
        System.out.println("[CallManager] Cleaning up call resources...");
        
        // Prevent infinite recursion: check if already cleaning up
        if (currentState == CallState.IDLE) {
            System.out.println("[CallManager] Already cleaned up, skipping");
            return;
        }
        
        // Set state to IDLE immediately to prevent re-entry
        this.currentState = CallState.IDLE;
        this.currentCallId = null;
        this.remoteUsername = null;
        this.isOutgoingCall = false;
        
        // Now close WebRTC connection (this may trigger callbacks, but state is already IDLE)
        if (webrtcClient != null) {
            webrtcClient.close();
            webrtcClient = null;
        }

        if (screenShareController != null) {
            try {
                screenShareController.close();
            } catch (Exception ignored) {
            }
            screenShareController = null;
            screenShareManager = null;
        }
        
        System.out.println("[CallManager] Cleanup complete");
    }
    
    // ===============================
    // GUI Callback Setters
    // ===============================
    
    public void setOnIncomingCallCallback(Consumer<IncomingCallInfo> callback) {
        this.onIncomingCallCallback = callback;
    }
    
    public void setOnCallAcceptedCallback(Consumer<String> callback) {
        this.onCallAcceptedCallback = callback;
    }
    
    public void setOnCallRejectedCallback(Consumer<String> callback) {
        this.onCallRejectedCallback = callback;
    }
    
    public void setOnCallEndedCallback(Consumer<String> callback) {
        this.onCallEndedCallback = callback;
    }
    
    public void setOnCallConnectedCallback(Runnable callback) {
        this.onCallConnectedCallback = callback;
    }
    
    public void setOnRemoteTrackCallback(Consumer<MediaStreamTrack> callback) {
        this.onRemoteTrackCallback = callback;
    }
    
    public void setOnRemoteScreenShareStoppedCallback(Runnable callback) {
        this.onRemoteScreenShareStoppedCallback = callback;
    }
    
    // ===============================
    // Getters
    // ===============================
    
    public CallState getCurrentState() {
        return currentState;
    }
    
    public String getCurrentCallId() {
        return currentCallId;
    }
    
    public String getRemoteUsername() {
        return remoteUsername;
    }
    
    public boolean isInCall() {
        return currentState != CallState.IDLE && currentState != CallState.ENDED;
    }

    public ScreenShareController getScreenShareController() {
        ensureScreenShareController();
        return screenShareController;
    }
    
    public VideoTrack getLocalVideoTrack() {
        VideoTrack track = webrtcClient != null ? webrtcClient.getLocalVideoTrack() : null;
        System.out.printf("[CallManager] getLocalVideoTrack called: webrtcClient=%s, track=%s%n",
            webrtcClient != null ? "EXISTS" : "NULL",
            track != null ? "EXISTS" : "NULL");
        return track;
    }
    
    public WebRTCClient getWebRTCClient() {
        return webrtcClient;
    }
    
    /**
     * Get signaling client (for P2P integration)
     */
    public WebRTCSignalingClient getSignalingClient() {
        return signalingClient;
    }
    
    /**
     * Get username
     */
    public String getUsername() {
        return myUsername;
    }
    
    // ===============================
    // Shutdown
    // ===============================
    
    /**
     * Shutdown call manager
     */
    public void shutdown() {
        System.out.println("[CallManager] Shutting down...");
        
        // End any active call
        if (isInCall()) {
            endCall();
        }
        
        // Stop signaling
        if (signalingClient != null) {
            signalingClient.shutdown();
            signalingClient = null;
        }
        
        // Shutdown WebRTC
        WebRTCClient.shutdown();
        
        System.out.println("[CallManager] Shutdown complete");
    }
}
