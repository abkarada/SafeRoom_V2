package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import dev.onvoid.webrtc.RTCIceCandidate;
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
    
    /**
     * Initialize call manager
     */
    public void initialize(String username) {
        // 🔧 Prevent re-initialization
        if (isInitialized) {
            System.out.printf("[CallManager] ⚠️ Already initialized for user: %s (current: %s)%n", myUsername, username);
            return;
        }
        
        this.myUsername = username;
        
        System.out.printf("[CallManager] 🔧 Initializing for user: %s%n", username);
        
        // Initialize WebRTC client library
        if (!WebRTCClient.isInitialized()) {
            WebRTCClient.initialize();
        }
        
        // Create signaling client
        signalingClient = new WebRTCSignalingClient(username);
        
        // Set incoming signal handler
        signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        
        // Start signaling stream for real-time signals
        signalingClient.startSignalingStream();
        
        this.isInitialized = true; // 🔧 Mark as initialized
        
        System.out.println("[CallManager] ✅ Initialization complete");
    }
    
    /**
     * Check if CallManager is initialized
     */
    public boolean isInitialized() {
        return isInitialized;
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
                
                // 🎤 Add audio track if audio enabled
                if (audioEnabled) {
                    System.out.println("[CallManager] 🎤 Adding audio track for outgoing call...");
                    webrtcClient.addAudioTrack();
                }
                
                // 📹 Add video track if video enabled
                if (videoEnabled) {
                    System.out.println("[CallManager] 📹 Adding video track for outgoing call...");
                    webrtcClient.addVideoTrack();
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
    
    /**
     * Start screen sharing and renegotiate
     * @param sourceId Desktop source ID
     * @param isWindow true if window, false if screen
     */
    public void startScreenShare(long sourceId, boolean isWindow) {
        if (currentState != CallState.CONNECTED) {
            System.err.println("[CallManager] ⚠️ Cannot share screen - not in active call");
            return;
        }
        
        if (webrtcClient == null) {
            System.err.println("[CallManager] ⚠️ WebRTC client not initialized");
            return;
        }
        
        System.out.printf("[CallManager] 🖥️ Starting screen share: sourceId=%d, isWindow=%b%n", sourceId, isWindow);
        
        // Start screen sharing on WebRTC client
        webrtcClient.startScreenShare(sourceId, isWindow);
        
        // Renegotiate with new screen share track
        renegotiateWithScreenShare();
    }
    
    /**
     * Stop screen sharing and renegotiate
     */
    public void stopScreenShare() {
        if (webrtcClient == null || !webrtcClient.isScreenSharingEnabled()) {
            System.err.println("[CallManager] Screen sharing not active");
            return;
        }
        
        System.out.println("[CallManager] Stopping screen share");
        
        // Stop screen sharing
        webrtcClient.stopScreenShare();
        
        // Notify remote peer
        signalingClient.sendScreenShareStop(currentCallId, remoteUsername);
        
        System.out.println("[CallManager] Screen share stop signal sent");
    }
    
    /**
     * Renegotiate connection with screen share track
     */
    private void renegotiateWithScreenShare() {
        System.out.println("[CallManager] Renegotiating with screen share...");
        
        // Create new offer with screen share track
        webrtcClient.createOffer()
            .thenAccept(sdp -> {
                System.out.println("[CallManager] Sending SCREEN_SHARE_OFFER with new SDP");
                
                // Send screen share offer (special signal type for renegotiation)
                signalingClient.sendScreenShareOffer(currentCallId, remoteUsername, sdp);
                
                System.out.println("[CallManager] Screen share offer sent");
            })
            .exceptionally(e -> {
                System.err.printf("[CallManager] Failed to create screen share offer: %s%n", e.getMessage());
                e.printStackTrace();
                return null;
            });
    }
    
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
                
            // SDRT (Serverless Distributed Relay Tree) signals
            case TREE_PARENT_ASSIGN:
                System.out.println("[CallManager] Processing TREE_PARENT_ASSIGN...");
                handleTreeParentAssign(signal);
                break;
                
            case TREE_PROMOTE:
                System.out.println("[CallManager] Processing TREE_PROMOTE...");
                handleTreePromote(signal);
                break;
                
            case TREE_DEMOTE:
                System.out.println("[CallManager] Processing TREE_DEMOTE...");
                handleTreeDemote(signal);
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
        
        // Add audio track if audio enabled
        if (signal.getAudioEnabled()) {
            System.out.println("[CallManager] Adding audio track for incoming call...");
            webrtcClient.addAudioTrack();
        }
        
        // Add video track if video enabled
        if (signal.getVideoEnabled()) {
            System.out.println("[CallManager] Adding video track for incoming call...");
            webrtcClient.addVideoTrack();
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
    // SDRT (Serverless Distributed Relay Tree) - Room-based Calls
    // ===============================
    
    /**
     * Join a room using SDRT architecture
     * This is for multi-party video calls (NOT 1-to-1)
     * 
     * @param roomId Unique room identifier
     * @return true if join request sent successfully
     */
    public boolean joinRoom(String roomId) {
        if (!isInitialized) {
            System.err.println("[CallManager] Cannot join room - not initialized");
            return false;
        }
        
        System.out.printf("[CallManager] 🏠 Joining SDRT room: %s (user: %s)%n", roomId, myUsername);
        
        // Join room via SDRTSessionManager
        SDRTSessionManager.getInstance().joinRoom(
            roomId,
            myUsername,
            signal -> {
                // Send signal to server via existing signaling client
                try {
                    signalingClient.sendSignal(signal);
                } catch (Exception e) {
                    System.err.printf("[CallManager] Failed to send SDRT signal: %s%n", e.getMessage());
                    e.printStackTrace();
                }
            }
        );
        
        System.out.printf("[CallManager] ✅ Room join request sent for: %s%n", roomId);
        return true;
    }
    
    /**
     * Leave a room
     * 
     * @param roomId Room to leave
     */
    public void leaveRoom(String roomId) {
        System.out.printf("[CallManager] 🚪 Leaving SDRT room: %s%n", roomId);
        
        SDRTSessionManager.getInstance().leaveRoom(
            roomId,
            signal -> {
                try {
                    signalingClient.sendSignal(signal);
                } catch (Exception e) {
                    System.err.printf("[CallManager] Failed to send leave signal: %s%n", e.getMessage());
                }
            }
        );
    }
    
    /**
     * Check if currently in a room
     * 
     * @param roomId Room ID to check
     * @return true if in room
     */
    public boolean isInRoom(String roomId) {
        return SDRTSessionManager.getInstance().isInRoom(roomId);
    }
    
    /**
     * Get current SDRT session for a room
     * 
     * @param roomId Room ID
     * @return SDRT session or null
     */
    public SDRTSessionManager.SDRTSession getSDRTSession(String roomId) {
        return SDRTSessionManager.getInstance().getSession(roomId);
    }
    
    // ===============================
    // SDRT (Serverless Distributed Relay Tree) Handlers
    // ===============================
    
    /**
     * Handle TREE_PARENT_ASSIGN signal from server
     * This assigns us a parent in the SDRT tree for a room
     */
    private void handleTreeParentAssign(WebRTCSignal signal) {
        String roomId = signal.getRoomId();
        String parentUserId = signal.getParentUserId();
        String nodeRole = signal.getNodeRole();
        String groupKey = signal.getGroupKey();
        
        System.out.printf("[CallManager] SDRT: Parent assigned for room %s: parent=%s, role=%s%n",
            roomId, parentUserId != null ? parentUserId : "ROOT", nodeRole);
        
        // Pass to SDRTSessionManager
        SDRTSessionManager.getInstance().handleParentAssignment(
            roomId,
            parentUserId,
            nodeRole,
            groupKey
        );
        
        // Create DataChannel to parent if parentUserId is not null
        if (parentUserId != null && !parentUserId.isEmpty()) {
            System.out.printf("[CallManager] Creating DataChannel to parent: %s%n", parentUserId);
            createSDRTDataChannel(roomId, parentUserId, true); // true = to parent
        } else {
            System.out.println("[CallManager] We are ROOT - no parent DataChannel needed");
        }
    }
    
    /**
     * Handle TREE_PROMOTE signal from server
     * Promotes our role (LEAF → RELAY or RELAY → ROOT)
     */
    private void handleTreePromote(WebRTCSignal signal) {
        String roomId = signal.getRoomId();
        String newRole = signal.getNodeRole();
        
        System.out.printf("[CallManager] SDRT: Promoted in room %s to role: %s%n", roomId, newRole);
        
        // Update role in session
        SDRTSessionManager.SDRTSession session = SDRTSessionManager.getInstance().getSession(roomId);
        if (session != null) {
            session.updateRole(com.saferoom.webrtc.relay.TreeNode.NodeRole.valueOf(newRole));
        }
    }
    
    /**
     * Handle TREE_DEMOTE signal from server
     * Demotes our role (ROOT → RELAY or RELAY → LEAF)
     */
    private void handleTreeDemote(WebRTCSignal signal) {
        String roomId = signal.getRoomId();
        String newRole = signal.getNodeRole();
        
        System.out.printf("[CallManager] SDRT: Demoted in room %s to role: %s%n", roomId, newRole);
        
        // Update role in session
        SDRTSessionManager.SDRTSession session = SDRTSessionManager.getInstance().getSession(roomId);
        if (session != null) {
            session.updateRole(com.saferoom.webrtc.relay.TreeNode.NodeRole.valueOf(newRole));
        }
    }
    
    /**
     * Create SDRT DataChannel for parent or child connection
     * 
     * @param roomId Room ID
     * @param peerUserId Peer user ID (parent or child)
     * @param isParent true if connecting to parent, false if accepting child
     */
    private void createSDRTDataChannel(String roomId, String peerUserId, boolean isParent) {
        System.out.printf("[CallManager] Creating SDRT DataChannel: room=%s, peer=%s, isParent=%b%n",
            roomId, peerUserId, isParent);
        
        // TODO: Get or create PeerConnection to peer
        // For now, this is a placeholder
        // In real implementation, we need:
        // 1. Get/create RTCPeerConnection for peerUserId
        // 2. Create DataChannel with label "sdrt-{roomId}"
        // 3. Set ordered=true (SDRT requires ordered delivery)
        // 4. Attach to TreeNode via setParentChannel() or addChildChannel()
        // 5. Wire DataChannel.onMessage → SDRTMediaBridge.handleIncomingPacket()
        
        System.out.printf("[CallManager] ⚠️ TODO: Implement PeerConnection + DataChannel creation%n");
        System.out.printf("[CallManager] This requires WebRTC PeerConnection to: %s%n", peerUserId);
        
        // Placeholder: Mark DataChannel as "pending creation"
        SDRTSessionManager.SDRTSession session = SDRTSessionManager.getInstance().getSession(roomId);
        if (session != null) {
            if (isParent) {
                System.out.printf("[CallManager] Will connect to parent via DataChannel: %s%n", peerUserId);
                // session.treeNode.setParentChannel(dataChannel, peerUserId);
            } else {
                System.out.printf("[CallManager] Will accept child connection via DataChannel: %s%n", peerUserId);
                // session.treeNode.addChild(childTreeNode); // needs TreeNode object, not userId
            }
        }
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
