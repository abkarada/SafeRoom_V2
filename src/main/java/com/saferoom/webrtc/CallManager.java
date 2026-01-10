package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import com.saferoom.webrtc.screenshare.ScreenShareController;
import com.saferoom.webrtc.screenshare.ScreenShareManager;
import dev.onvoid.webrtc.PeerConnectionFactory;

import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import com.saferoom.log.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;

/**
 * Call Manager
 * Orchestrates WebRTCClient and WebRTCSignalingClient
 * Compatible with WebRTCSessionManager (server) and UDPHoleImpl
 */
public class CallManager {

    private static final Logger logger = Logger.getLogger(CallManager.class);
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
    private Runnable onLocalTracksReadyCallback; // Callback when local audio/video tracks are added

    // 🚀 EXECUTOR SERVICE (VIRTUAL THREADS)
    // Using newVirtualThreadPerTaskExecutor for high concurrency and non-blocking
    // operations
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
            .newVirtualThreadPerTaskExecutor();

    // 🔒 Signaling Lock: prevents race conditions between OFFER, ANSWER, and ICE
    // candidates
    private final java.util.concurrent.locks.ReentrantLock signalingLock = new java.util.concurrent.locks.ReentrantLock();

    // 🧊 ICE Candidate Buffering to prevent race conditions
    // Guarded by signalingLock (was synchronized list)
    private final java.util.List<WebRTCSignal> pendingIceCandidates = new java.util.ArrayList<>();

    // 📺 Remote Track Buffering: stores tracks that arrive before UI callback is
    // registered
    // Fixes race condition where callee's ActiveCallDialog misses remote video
    private final java.util.List<MediaStreamTrack> pendingRemoteTracks = new java.util.ArrayList<>();

    // ⚡ FAST P2P: Pre-generated offer to send immediately on accept
    private String preGeneratedOffer;

    // 🔥 Pre-warm future: coordinate camera pre-warming with handleOffer
    // FIX: Must await this before addVideoTrack to avoid race condition
    private CompletableFuture<Void> preWarmFuture = null;

    /**
     * Run a task asynchronously on the CallManager's virtual thread executor.
     * Useful for offloading UI actions or long-running tasks.
     */
    public void runAsync(Runnable task) {
        if (executor != null && !executor.isShutdown()) {
            executor.submit(task);
        }
    }

    /**
     * Call states (matching server-side WebRTCSessionManager.CallState)
     */
    public enum CallState {
        IDLE, // No call
        RINGING, // Outgoing/incoming call ringing
        CONNECTING, // SDP/ICE exchange in progress
        CONNECTED, // Call established
        ENDED // Call ended
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
    private CallManager() {
    }

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
            logger.warn(String.format("⚠️ Already initialized for user: %s (current: %s)", myUsername, username));
            return;
        }

        this.myUsername = username;

        logger.info("🔧 Initializing for user: " + username);

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

        logger.info("✅ Initialization complete");
    }

    /**
     * Check if CallManager is initialized
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Handle async failures during call setup (after UI success)
     */
    private void handleAsyncCallError(String context, Throwable ex) {
        logger.error(String.format("%s: %s", context, ex.getMessage()), ex);

        // Notify user via existing callbacks (treat as call end)
        if (this.currentCallId != null && onCallEndedCallback != null) {
            onCallEndedCallback.accept(this.currentCallId);
        }

        // Ensure cleanup
        cleanup();
    }

    // ===============================
    // Outgoing Call Flow
    // ===============================

    /**
     * Start outgoing call
     */
    public CompletableFuture<String> startCall(String targetUsername, boolean audioEnabled, boolean videoEnabled) {
        if (currentState != CallState.IDLE) {
            logger.error("Cannot start call - current state: " + currentState, null);
            return CompletableFuture.failedFuture(new IllegalStateException("Already in a call"));
        }

        logger.info(String.format("📞 Starting call to %s (audio=%b, video=%b)",
                targetUsername, audioEnabled, videoEnabled));

        this.remoteUsername = targetUsername;
        this.isOutgoingCall = true;
        this.currentState = CallState.RINGING;

        // Send CALL_REQUEST to server
        return signalingClient.sendCallRequest(targetUsername, audioEnabled, videoEnabled)
                .thenApply(callId -> {
                    this.currentCallId = callId;
                    logger.info("✅ Call request sent, callId: " + callId);

                    // Create WebRTC peer connection
                    webrtcClient = new WebRTCClient(callId, targetUsername);
                    webrtcClient.createPeerConnection(audioEnabled, videoEnabled);
                    ensureScreenShareController();

                    // Set up callbacks
                    setupWebRTCCallbacks();

                    // Collect track futures
                    List<CompletableFuture<Void>> trackFutures = new ArrayList<>();

                    // 🎤 Add audio track if audio enabled (Async)
                    if (audioEnabled) {
                        logger.info("🎤 Adding audio track for outgoing call...");
                        trackFutures.add(webrtcClient.addAudioTrack());
                    }

                    // 📹 Add video track if video enabled (Async)
                    if (videoEnabled) {
                        logger.info("📹 Adding video track for outgoing call...");
                        trackFutures.add(webrtcClient.addVideoTrack()
                                .orTimeout(15, TimeUnit.SECONDS) // FIX: Increased from 5s for Mac camera init
                                .thenRun(() -> {
                                    registerCameraWithScreenShareController();
                                }).exceptionally(e -> {
                                    logger.error("Failed to add video track (timeout or error): " + e.getMessage(), e);
                                    return null;
                                }));
                    }

                    // Wait for tracks, then notify GUI and generate offer
                    CompletableFuture.allOf(trackFutures.toArray(new CompletableFuture[0]))
                            .thenCompose(v -> {
                                // FIX: Notify GUI AFTER tracks are ready (not before!)
                                if (onLocalTracksReadyCallback != null) {
                                    logger.info("🎥 Local tracks ready (caller) - notifying GUI");
                                    onLocalTracksReadyCallback.run();
                                }

                                // ⚡ FAST P2P: Generate OFFER now (during RINGING) so it's ready instantly
                                logger.info("⚡ Generating Early Offer during RINGING...");
                                return webrtcClient.createOffer()
                                        .orTimeout(5, TimeUnit.SECONDS) // Fix: Timeout
                                        .thenAccept(sdp -> {
                                            this.preGeneratedOffer = sdp;
                                            logger.info("⚡ Early Offer generated and ready!");
                                        });
                            })
                            .exceptionally(ex -> {
                                handleAsyncCallError("Call setup failed (media/offer)", ex);
                                return null;
                            });

                    return callId;
                })
                .exceptionally(e -> {
                    logger.error("Failed to start call: " + e.getMessage(), e);
                    this.currentState = CallState.IDLE;
                    return null;
                });
    }

    /**
     * Cancel outgoing call (before accepted)
     */
    public void cancelCall() {
        if (!isOutgoingCall || currentState != CallState.RINGING) {
            logger.warn("No outgoing call to cancel");
            return;
        }

        logger.info("🚫 Cancelling call: " + currentCallId);

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
     * 
     * Create peer connection but DO NOT add tracks yet!
     * Tracks will be added in handleOffer() AFTER setRemoteDescription()
     * This ensures correct transceiver direction (SEND_RECV not SEND_ONLY)
     */
    public void acceptCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            logger.warn("No incoming call to accept");
            return;
        }

        logger.info("✅ Accepting call: " + callId);

        // ═══════════════════════════════════════════════════════════════
        // Create peer connection but DO NOT add tracks yet!
        // Tracks must be added AFTER setRemoteDescription in handleOffer()
        // to ensure proper transceiver direction matching
        // ═══════════════════════════════════════════════════════════════
        logger.info("🔧 Creating peer connection (tracks will be added after OFFER)...");

        webrtcClient = new WebRTCClient(currentCallId, remoteUsername);
        webrtcClient.createPeerConnection(pendingAudioEnabled, pendingVideoEnabled);
        ensureScreenShareController();
        setupWebRTCCallbacks();

        // Reset flag for track addition
        tracksAddedForIncomingCall = false;

        // ═══════════════════════════════════════════════════════════════
        // 🔥 MAC FIX: Pre-warm video track in parallel with CALL_ACCEPT
        // Camera starts initializing NOW, during network round-trip.
        // By the time OFFER arrives, camera is ready at 640x480!
        // ═══════════════════════════════════════════════════════════════
        if (pendingVideoEnabled) {
            logger.info("Pre-warming camera (parallel with CALL_ACCEPT)...");
            // FIX: Store future to await in handleOffer before addVideoTrack
            preWarmFuture = webrtcClient.preWarmVideoTrack();
        }

        // Send CALL_ACCEPT (network round-trip starts)
        boolean success = signalingClient.sendCallAccept(callId, remoteUsername);

        if (success) {
            this.currentState = CallState.CONNECTING;

            // 🔧 DON'T add tracks or create answer here!
            // Wait for OFFER → setRemoteDescription → addTracks → createAnswer
            logger.info("⏳ Waiting for SDP offer from caller...");
            logger.info("📋 Media will start when OFFER is received");

            if (onCallAcceptedCallback != null) {
                onCallAcceptedCallback.accept(callId);
            }
        } else {
            logger.error("Failed to accept call", null);
            cleanup();
        }
    }

    /**
     * Reject incoming call
     */
    public void rejectCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            logger.warn("No incoming call to reject");
            return;
        }

        logger.info("❌ Rejecting call: " + callId);

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
            logger.warn("No active call to end");
            return;
        }

        logger.info("📴 Ending call: " + currentCallId);

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
            logger.info(String.format("Routing %s to P2PConnectionManager (callId: %s)", type, callId));
            try {
                com.saferoom.p2p.P2PConnectionManager p2pManager = com.saferoom.p2p.P2PConnectionManager.getInstance();

                // Call handleIncomingSignal via reflection
                java.lang.reflect.Method method = com.saferoom.p2p.P2PConnectionManager.class
                        .getDeclaredMethod("handleIncomingSignal", WebRTCSignal.class);
                method.setAccessible(true);
                method.invoke(p2pManager, signal);

                logger.info("Routed " + type + " to P2PConnectionManager");
            } catch (Exception e) {
                logger.error("Failed to route P2P signal: " + e.getMessage(), e);
            }
            return; // Don't process P2P signals in CallManager
        }

        // Handle voice/video call signals normally
        logger.info(String.format("Received %s from %s (callId: %s, currentState: %s)",
                type, from, callId, currentState));

        switch (type) {
            case CALL_REQUEST:
                logger.info("Processing CALL_REQUEST...");
                handleIncomingCallRequest(signal);
                break;

            case CALL_ACCEPT:
                logger.info("Processing CALL_ACCEPT...");
                handleCallAccepted(signal);
                break;

            case CALL_REJECT:
            case CALL_CANCEL:
                logger.info("Processing CALL_REJECT/CANCEL...");
                handleCallRejected(signal);
                break;

            case CALL_END:
                logger.info("Processing CALL_END...");
                handleCallEnded(signal);
                break;

            case OFFER:
                logger.info("Processing OFFER...");
                handleOffer(signal);
                break;

            case ANSWER:
                logger.info("Processing ANSWER...");
                handleAnswer(signal);
                break;

            case ICE_CANDIDATE:
                logger.info("Processing ICE_CANDIDATE...");
                handleIceCandidate(signal);
                break;

            case SCREEN_SHARE_OFFER:
                logger.info("Processing SCREEN_SHARE_OFFER...");
                handleScreenShareOffer(signal);
                break;

            case SCREEN_SHARE_STOP:
                logger.info("Processing SCREEN_SHARE_STOP...");
                handleScreenShareStop(signal);
                break;

            default:
                logger.warn("Unknown signal type: " + type);
        }
    }

    public void setOnLocalTracksReadyCallback(Runnable callback) {
        this.onLocalTracksReadyCallback = callback;
    }

    // Store incoming call media settings for later use when accepted
    private boolean pendingAudioEnabled = false;
    private boolean pendingVideoEnabled = false;
    private boolean tracksAddedForIncomingCall = false;

    /**
     * Handle incoming call request
     * 
     * IMPORTANT: Do NOT start camera/mic here!
     * Media capture should only start AFTER user accepts the call.
     */
    private void handleIncomingCallRequest(WebRTCSignal signal) {
        logger.info(String.format("Incoming call from %s (audio=%b, video=%b)",
                signal.getFrom(), signal.getAudioEnabled(), signal.getVideoEnabled()));

        if (currentState != CallState.IDLE) {
            logger.warn("Already in a call (state: " + currentState + ") - rejecting");
            signalingClient.sendCallReject(signal.getCallId(), signal.getFrom());
            return;
        }

        this.currentCallId = signal.getCallId();
        this.remoteUsername = signal.getFrom();
        this.isOutgoingCall = false;
        this.currentState = CallState.RINGING;

        // Store media settings for when call is accepted
        this.pendingAudioEnabled = signal.getAudioEnabled();
        this.pendingVideoEnabled = signal.getVideoEnabled();

        logger.info(String.format("Call state updated: RINGING (callId: %s)", currentCallId));
        logger.info("⏸️ Media capture DEFERRED until user accepts call");

        // ═══════════════════════════════════════════════════════════════
        // DO NOT create peer connection or add tracks here!
        // Wait for user to accept the call first.
        // This prevents camera from opening before user consent.
        // ═══════════════════════════════════════════════════════════════

        // Notify GUI to show incoming call dialog
        if (onIncomingCallCallback != null) {
            logger.info("Triggering incoming call callback for GUI...");
            IncomingCallInfo info = new IncomingCallInfo(
                    signal.getCallId(),
                    signal.getFrom(),
                    signal.getAudioEnabled(),
                    signal.getVideoEnabled(),
                    signal.getTimestamp());
            onIncomingCallCallback.accept(info);
            logger.info("Incoming call callback triggered successfully");
        } else {
            logger.warn("WARNING: onIncomingCallCallback is NULL! Dialog won't show!");
        }
    }

    /**
     * Handle call accepted (for outgoing call)
     */
    private void handleCallAccepted(WebRTCSignal signal) {
        if (!isOutgoingCall)
            return;

        logger.info("Call accepted by remote user");

        this.currentState = CallState.CONNECTING;

        this.currentState = CallState.CONNECTING;

        // ⚡ FAST P2P: Send pre-generated offer if available
        if (preGeneratedOffer != null) {
            logger.info("⚡ Sending Early Offer immediately!");
            signalingClient.sendOffer(currentCallId, remoteUsername, preGeneratedOffer);
            preGeneratedOffer = null; // Consume it
        } else {
            // Fallback (race condition where answer happened before offer gen finished?)
            logger.warn("⚠️ Early offer not ready, generating now...");
            webrtcClient.createOffer()
                    .orTimeout(5, TimeUnit.SECONDS) // Fix: Timeout
                    .thenAccept(sdp -> {
                        signalingClient.sendOffer(currentCallId, remoteUsername, sdp);
                        logger.info("Offer sent");
                    })
                    .exceptionally(ex -> {
                        logger.error("Failed to generate Offer (Fallback): " + ex.getMessage(), ex);
                        return null;
                    });
        }

        if (onCallAcceptedCallback != null) {
            onCallAcceptedCallback.accept(currentCallId);
        }
    }

    /**
     * Handle call rejected/cancelled
     */
    private void handleCallRejected(WebRTCSignal signal) {
        logger.info("Call rejected/cancelled");

        if (onCallRejectedCallback != null) {
            onCallRejectedCallback.accept(currentCallId);
        }

        cleanup();
    }

    /**
     * Handle call ended
     */
    private void handleCallEnded(WebRTCSignal signal) {
        logger.info("Call ended by remote user");

        if (onCallEndedCallback != null) {
            onCallEndedCallback.accept(currentCallId);
        }

        cleanup();
    }

    /**
     * Handle SDP offer
     * 
     * CRITICAL: For callee, add tracks AFTER setRemoteDescription
     * This ensures proper transceiver direction matching (SEND_RECV not SEND_ONLY)
     */
    private void handleOffer(WebRTCSignal signal) {
        // Run on virtual thread with lock to ensure sequential state updates
        executor.submit(() -> {
            signalingLock.lock();
            try {
                logger.info("Received SDP offer");

                // Set remote description FIRST
                webrtcClient.setRemoteDescription("offer", signal.getSdp());

                // 🔧 If we're the callee (incoming call accepted), create answer now
                if (!isOutgoingCall && currentState == CallState.CONNECTING) {
                    // ═══════════════════════════════════════════════════════════════
                    // CRITICAL FIX: Add tracks AFTER setRemoteDescription
                    // This ensures transceivers are properly matched for SEND_RECV
                    // If tracks are added BEFORE, they become SEND_ONLY and can't receive
                    // ═══════════════════════════════════════════════════════════════

                    if (!tracksAddedForIncomingCall) {
                        logger.info("Adding media tracks AFTER remote offer (correct order)...");

                        // FIX: Wait for pre-warm to complete before addVideoTrack
                        // This prevents the race condition where addVideoTrack creates
                        // a second camera track because videoPreWarmed is still false
                        if (preWarmFuture != null) {
                            try {
                                logger.info("Waiting for camera pre-warm to complete...");
                                preWarmFuture.get(10, TimeUnit.SECONDS);
                                logger.info("Pre-warm complete, proceeding with addVideoTrack");
                            } catch (java.util.concurrent.TimeoutException e) {
                                logger.warn("Pre-warm timed out after 10s, falling back to normal path");
                            } catch (Exception e) {
                                logger.warn("Pre-warm failed: " + e.getMessage() + ", falling back to normal path");
                            } finally {
                                preWarmFuture = null;
                            }
                        }

                        List<CompletableFuture<Void>> trackFutures = new ArrayList<>();

                        if (pendingAudioEnabled) {
                            logger.info("Adding audio track...");
                            trackFutures.add(webrtcClient.addAudioTrack());
                        }

                        if (pendingVideoEnabled) {
                            logger.info("Adding video track...");
                            trackFutures.add(webrtcClient.addVideoTrack()
                                    .orTimeout(15, TimeUnit.SECONDS) // FIX: Increased from 5s for Mac camera init
                                    .thenRun(() -> {
                                        registerCameraWithScreenShareController();
                                    }).exceptionally(e -> {
                                        logger.error("Failed to add video track (timeout or error): " + e.getMessage(),
                                                e);
                                        return null;
                                    }));
                        }

                        tracksAddedForIncomingCall = true;

                        // Wait for tracks, then notify GUI and create answer
                        CompletableFuture.allOf(trackFutures.toArray(new CompletableFuture[0]))
                                .thenRun(() -> {
                                    logger.info(String.format("Media setup complete. Audio track: %s, Video track: %s",
                                            webrtcClient.getLocalAudioTrack() != null ? "READY" : "NONE",
                                            webrtcClient.getLocalVideoTrack() != null ? "READY" : "NONE"));

                                    // 🎥 Notify GUI that local tracks are ready (for CALLEE)
                                    // CRITICAL: Must fire AFTER tracks are created, not before!
                                    if (onLocalTracksReadyCallback != null) {
                                        logger.info("🎥 Local tracks ready (callee) - notifying GUI");
                                        onLocalTracksReadyCallback.run();
                                    }

                                    logger.info("Creating SDP answer (after tracks added)...");
                                    webrtcClient.createAnswer()
                                            .orTimeout(5, TimeUnit.SECONDS) // Fix: Timeout
                                            .thenAccept(sdp -> {
                                                // Send ANSWER to caller
                                                signalingClient.sendAnswer(currentCallId, remoteUsername, sdp);
                                                logger.info("Answer sent to caller");
                                            }).exceptionally(ex -> {
                                                logger.error("Failed to create answer: " + ex.getMessage(), ex);
                                                return null;
                                            });
                                });
                    }
                }

                // 🧊 Replay any buffered ICE candidates that arrived before OFFER
                drainPendingIceCandidates();
            } finally {
                signalingLock.unlock();
            }
        });
    }

    /**
     * Handle SDP answer
     */
    private void handleAnswer(WebRTCSignal signal) {
        executor.submit(() -> {
            signalingLock.lock();
            try {
                logger.info("Received SDP answer");

                // Set remote description
                webrtcClient.setRemoteDescription("answer", signal.getSdp());

                // Mark as connected
                this.currentState = CallState.CONNECTED;

                if (onCallConnectedCallback != null) {
                    onCallConnectedCallback.run();
                }

                // 🧊 Replay any buffered ICE candidates that arrived before ANSWER
                drainPendingIceCandidates();
            } finally {
                signalingLock.unlock();
            }
        });
    }

    /**
     * Handle ICE candidate
     */
    // 🛑 REMOVED DUPLICATE METHOD
    // public void addIceCandidate(WebRTCSignal signal) is defined below
    // private void handleIceCandidate(WebRTCSignal signal) delegates to it

    /**
     * Handle ICE candidate
     * (Delegates to addIceCandidate which handles locking and buffering)
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        logger.info("Received ICE candidate");
        addIceCandidate(signal);
    }

    /**
     * Add ICE Candidate (handle incoming signal)
     * Public method for external use or delegate from handleIceCandidate
     */
    public void addIceCandidate(WebRTCSignal signal) {
        signalingLock.lock();
        try {
            boolean ready = webrtcClient != null &&
                    webrtcClient.getPeerConnection() != null &&
                    webrtcClient.getPeerConnection().getRemoteDescription() != null;

            if (!ready) {
                System.out.println(
                        "[CallManager] 🧊 Remote description not set yet (or client null), buffering ICE candidate");
                // 🛡️ MEMORY LEAK FIX: Cap the buffer size
                if (pendingIceCandidates.size() >= 100) {
                    logger.warn("⚠️ ICE candidate buffer full (100). Dropping oldest candidate.");
                    pendingIceCandidates.remove(0);
                }
                pendingIceCandidates.add(signal);
                return;
            }

            try {
                webrtcClient.addIceCandidate(
                        signal.getCandidate(),
                        signal.getSdpMid(),
                        signal.getSdpMLineIndex());
            } catch (Exception e) {
                logger.error("Failed to add ICE candidate: " + e.getMessage(), e);
            }
        } finally {
            signalingLock.unlock();
        }
    }

    /**
     * Replay buffered ICE candidates
     */
    private void drainPendingIceCandidates() {
        signalingLock.lock();
        try {
            if (pendingIceCandidates.isEmpty())
                return;

            logger.info("🧊 Replaying " + pendingIceCandidates.size() + " buffered ICE candidates...");

            for (WebRTCSignal signal : pendingIceCandidates) {
                try {
                    // Check again just in case (though we should be ready if this method is called)
                    if (webrtcClient != null) {
                        webrtcClient.addIceCandidate(
                                signal.getCandidate(),
                                signal.getSdpMid(),
                                signal.getSdpMLineIndex());
                    }
                } catch (Exception e) {
                    logger.error("Failed to replay ICE candidate: " + e.getMessage(), e);
                }
            }
            pendingIceCandidates.clear();
        } finally {
            signalingLock.unlock();
        }
    }

    /**
     * Handle screen share offer (renegotiation)
     */
    private void handleScreenShareOffer(WebRTCSignal signal) {
        logger.info("Received screen share offer - remote peer started sharing");

        if (webrtcClient == null) {
            logger.error("WebRTC client not initialized", null);
            return;
        }

        String remoteSdp = signal.getSdp();

        // Set remote description (this is a renegotiation)
        webrtcClient.setRemoteDescription("offer", remoteSdp);
        logger.info("Screen share offer set as remote description");

        // Create answer for renegotiation
        webrtcClient.createAnswer()
                .thenAccept(answerSdp -> {
                    logger.info("Sending answer for screen share");

                    // Send answer back
                    signalingClient.sendAnswer(currentCallId, remoteUsername, answerSdp);

                    logger.info("Screen share renegotiation complete");
                })
                .exceptionally(e -> {
                    logger.error("Failed to handle screen share offer: " + e.getMessage(), e);
                    return null;
                });
    }

    /**
     * Handle screen share stop notification
     */
    private void handleScreenShareStop(WebRTCSignal signal) {
        logger.info("🛑 Remote peer stopped screen sharing");

        // Notify GUI that remote screen share ended
        if (onRemoteScreenShareStoppedCallback != null) {
            onRemoteScreenShareStoppedCallback.run();
        }

        logger.info("✅ Remote screen share stop handled");
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
            logger.info("Local SDP generated");
            // SDP is sent in createOffer/createAnswer methods
        });

        // ICE candidate callback
        webrtcClient.setOnIceCandidateCallback(candidate -> {
            logger.debug("ICE candidate generated: " + candidate.sdp);

            // Send ICE candidate to remote peer via signaling
            signalingClient.sendIceCandidate(
                    currentCallId,
                    remoteUsername,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex);
        });

        // Connection established callback
        webrtcClient.setOnConnectionEstablishedCallback(() -> {
            logger.info("WebRTC connection established");
            this.currentState = CallState.CONNECTED;

            if (onCallConnectedCallback != null) {
                onCallConnectedCallback.run();
            }
        });

        // Connection closed callback
        webrtcClient.setOnConnectionClosedCallback(() -> {
            logger.info("WebRTC connection closed");
            cleanup();
        });

        // Remote track callback (for video/audio tracks)
        webrtcClient.setOnRemoteTrackCallback(track -> {
            logger.info(String.format("Remote track received: %s (kind=%s)",
                    track.getId(), track.getKind()));

            if (onRemoteTrackCallback != null) {
                onRemoteTrackCallback.accept(track);
            } else {
                // Buffer track for later - UI callback not registered yet
                // This happens when callee receives remote track before ActiveCallDialog is
                // shown
                logger.info("Buffering remote track (UI callback not ready): " + track.getId());
                synchronized (pendingRemoteTracks) {
                    pendingRemoteTracks.add(track);
                }
            }
        });
    }

    private void ensureScreenShareController() {
        if (screenShareController != null || webrtcClient == null) {
            return;
        }
        PeerConnectionFactory factory = WebRTCClient.getFactory();
        if (factory == null) {
            logger.error("ScreenShareController unavailable: factory is null", null);
            return;
        }
        RTCPeerConnection peerConnection = webrtcClient.getPeerConnection();
        if (peerConnection == null) {
            logger.error("ScreenShareController unavailable: peer connection not ready", null);
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
        if (webrtcClient.getVideoSender() != null && webrtcClient.getLocalVideoTrack() != null
                && webrtcClient.getLocalVideoTrack().isEnabled()) {
            screenShareController.registerCameraSource(
                    webrtcClient.getVideoSender(),
                    webrtcClient.getLocalVideoTrack());
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
        logger.info("Cleaning up call resources...");

        // Prevent infinite recursion: check if already cleaning up
        if (currentState == CallState.IDLE) {
            logger.debug("Already cleaned up, skipping");
            return;
        }

        // Set state to IDLE immediately to prevent re-entry
        this.currentState = CallState.IDLE;
        this.currentCallId = null;
        this.remoteUsername = null;
        this.isOutgoingCall = false;

        // Clear pending media settings
        this.pendingAudioEnabled = false;
        this.pendingVideoEnabled = false;
        this.tracksAddedForIncomingCall = false;
        this.preWarmFuture = null; // Clear pre-warm future

        // Clear ICE buffer
        synchronized (pendingIceCandidates) {
            pendingIceCandidates.clear();
        }

        // Now close WebRTC connection (this may trigger callbacks, but state is already
        // IDLE)
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

        // Replay any buffered tracks that arrived before the callback was registered
        if (callback != null) {
            java.util.List<MediaStreamTrack> tracksToReplay;
            synchronized (pendingRemoteTracks) {
                if (pendingRemoteTracks.isEmpty()) {
                    return;
                }
                tracksToReplay = new java.util.ArrayList<>(pendingRemoteTracks);
                pendingRemoteTracks.clear();
            }
            logger.info("📺 Replaying " + tracksToReplay.size() + " buffered remote track(s)...");
            for (MediaStreamTrack track : tracksToReplay) {
                logger.info("  → Replaying track: " + track.getId() + " (" + track.getKind() + ")");
                callback.accept(track);
            }
        }
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
        logger.debug(String.format("getLocalVideoTrack called: webrtcClient=%s, track=%s",
                webrtcClient != null ? "EXISTS" : "NULL",
                track != null ? "EXISTS" : "NULL"));
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
        logger.info("Shutting down...");

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

        logger.info("Shutdown complete");
    }

    /**
     * Explicitly dispose resources (for application exit)
     */
    public void dispose() {
        cleanup();
        shutdown();
        // Force factory disposal if exists
        try {
            if (WebRTCClient.getFactory() != null) {
                WebRTCClient.getFactory().dispose();
            }
        } catch (Exception e) {
            logger.error("Error disposing factory: " + e.getMessage(), e);
        }
    }
}
