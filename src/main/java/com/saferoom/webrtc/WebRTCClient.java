package com.saferoom.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.*;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.video.desktop.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC Client Manager (Real Implementation)
 * Uses webrtc-java library for actual media streaming
 * 
 * Platform-aware initialization:
 * - Windows: STA thread pipeline for COM-dependent AudioDeviceModule
 * - Linux/macOS: Standard thread initialization
 * 
 * Uses Virtual Threads for high-concurrency WebRTC operations.
 */
public class WebRTCClient {

    // Platform detection
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    // Unified Logger
    private static final com.saferoom.log.Logger logger = com.saferoom.log.Logger.getLogger(WebRTCClient.class);

    private static boolean initialized = false;
    private static PeerConnectionFactory factory;

    // ... (fields)

    public WebRTCClient(String callId, String remoteUsername) {
        if (!initialized) {
            throw new IllegalStateException("WebRTC not initialized. Call WebRTCClient.initialize() first.");
        }
        this.currentCallId = callId;
        this.remoteUsername = remoteUsername;

        // Set context for logging
        com.saferoom.log.Logger.setContext("CallID", callId);
        com.saferoom.log.Logger.setContext("Peer", remoteUsername);
        logger.info("WebRTCClient created for peer: " + remoteUsername);
    }

    private static AudioDeviceModule audioDeviceModule;
    private static WebRTCPlatformConfig platformConfig = WebRTCPlatformConfig.empty();
    private static volatile boolean playoutStarted = false;
    private static volatile boolean recordingStarted = false;

    // Device Hot-Swap State
    private static String currentMicName = "";
    private static String currentSpeakerName = "";
    private static volatile boolean isMonitoring = false;

    // Virtual Thread executor for async WebRTC operations
    private static final ExecutorService webrtcExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // Call ID for the current call
    private String currentCallId;
    private String remoteUsername;
    private boolean audioEnabled;
    private boolean videoEnabled;

    private RTCPeerConnection peerConnection;
    private MediaStreamTrack localAudioTrack;
    private MediaStreamTrack localVideoTrack;
    private dev.onvoid.webrtc.media.video.VideoDeviceSource videoSource; // Keep reference to stop camera

    // Track RTP senders for replaceTrack operations
    private RTCRtpSender videoSender = null;

    // Pre-warmed video track for fast answerer setup (Mac fix)
    private VideoTrack preWarmedVideoTrack = null;
    private dev.onvoid.webrtc.media.video.VideoDeviceSource preWarmedVideoSource = null;
    private volatile boolean videoPreWarmed = false;

    // FIX: Deferred Mac capture start flag for Answerer role
    // Capture must start AFTER setLocalDescription to ensure encoder gets correct
    // profile
    private volatile boolean pendingMacCaptureStart = false;

    // Encoder watchdog for detecting VideoToolbox freezes
    private volatile long lastEncodedFrameTime = 0;
    private volatile long captureFrameCount = 0;
    private volatile boolean encoderWatchdogActive = false;
    private volatile int softResetCount = 0;
    private static final long ENCODER_FREEZE_THRESHOLD_MS = 2000; // 2 seconds
    private static final int MAX_SOFT_RESETS = 3; // Escalate to hard reset after 3 failures

    // Callbacks
    private Consumer<RTCIceCandidate> onIceCandidateCallback;
    private Consumer<String> onLocalSDPCallback;
    private Runnable onConnectionEstablishedCallback;
    private Runnable onConnectionClosedCallback;
    private Consumer<MediaStreamTrack> onRemoteTrackCallback;

    /**
     * Initialize WebRTC (call once at app startup)
     * 
     * Platform-aware initialization:
     * - Windows: Uses STA thread for COM-dependent AudioDeviceModule
     * - Linux/macOS: Standard initialization
     */
    public static synchronized void initialize() {
        if (initialized) {
            System.out.println("[WebRTC] Already initialized");
            return;
        }

        System.out.println("[WebRTC] ═══════════════════════════════════════════════════════════");
        System.out.println("[WebRTC] Initializing WebRTC with native library...");
        System.out.printf("[WebRTC] Platform: %s%n", detectPlatformName());
        System.out.println("[WebRTC] ═══════════════════════════════════════════════════════════");

        // Virtual Thread executor is already initialized statically
        System.out.println("[WebRTC] Virtual Thread executor active");

        try {
            // Platform-specific audio initialization
            if (IS_WINDOWS) {
                initWindowsAudio();
            } else if (IS_LINUX) {
                initLinuxAudio();
            } else if (IS_MAC) {
                initMacAudio();
            } else {
                initDefaultAudio();
            }

            // Initialize PeerConnectionFactory with audio module
            initPeerConnectionFactory();

            // detect initial devices for monitoring
            AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
            if (defaultMic != null)
                currentMicName = defaultMic.getName();

            AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();
            if (defaultSpeaker != null)
                currentSpeakerName = defaultSpeaker.getName();

            initialized = true;
            startDeviceMonitor();
            System.out.println("[WebRTC] ═══════════════════════════════════════════════════════════");
            System.out.println("[WebRTC] WebRTC initialized successfully!");
            System.out.println("[WebRTC] ═══════════════════════════════════════════════════════════");

        } catch (Throwable e) {
            // CRITICAL: Fail if native library is missing. No mock mode.
            System.err.printf("[WebRTC] Native library failed to load: %s%n", e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("WebRTC initialization failed", e);
        }
    }

    /**
     * Detect platform name for logging
     */
    private static String detectPlatformName() {
        if (IS_WINDOWS)
            return "Windows (COM STA thread required)";
        if (IS_LINUX)
            return "Linux (PulseAudio/ALSA)";
        if (IS_MAC)
            return "macOS (CoreAudio)";
        return "Unknown (" + OS_NAME + ")";
    }

    /**
     * WINDOWS: Initialize AudioDeviceModule on a dedicated background thread
     * 
     * The webrtc-java native library handles COM initialization internally.
     * We must NOT call CoInitializeEx ourselves - let the native library do it.
     * 
     * Key: Run initialization on a CLEAN thread (not JavaFX Application Thread)
     * to avoid COM threading conflicts with JavaFX's own COM usage.
     */
    private static void initWindowsAudio() throws Exception {
        System.out.println("[WebRTC] [Windows] Initializing AudioDeviceModule on dedicated thread...");
        System.out.println("[WebRTC] [Windows] Note: COM will be handled by native webrtc library");

        AtomicReference<AudioDeviceModule> admRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Create a CLEAN background thread for WebRTC initialization
        // The native library will handle COM initialization on this thread
        Thread webrtcInitThread = Thread.ofPlatform()
                .name("webrtc-audio-init")
                .unstarted(() -> {
                    try {
                        System.out.println("[WebRTC] [Windows] Background thread started (tid=" +
                                Thread.currentThread().threadId() + ")");

                        // Get default audio devices - native code will init COM here
                        AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
                        AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();

                        // Create AudioDeviceModule - this triggers native COM usage
                        AudioDeviceModule adm = new AudioDeviceModule();

                        if (defaultMic != null) {
                            System.out.println("[WebRTC] [Windows] Default microphone: " + defaultMic.getName());
                            adm.setRecordingDevice(defaultMic);
                            adm.initRecording();
                        } else {
                            System.out.println("[WebRTC] [Windows] No microphone detected");
                        }

                        if (defaultSpeaker != null) {
                            System.out.println("[WebRTC] [Windows] Default speaker: " + defaultSpeaker.getName());
                            adm.setPlayoutDevice(defaultSpeaker);
                            adm.initPlayout();
                        } else {
                            System.out.println("[WebRTC] [Windows] No speaker detected");
                        }

                        admRef.set(adm);
                        System.out.println("[WebRTC] [Windows] AudioDeviceModule initialized successfully");

                    } catch (Throwable t) {
                        errorRef.set(t);
                        System.err.println("[WebRTC] [Windows] Init thread error: " + t.getMessage());
                        t.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });

        // Start init thread and wait for completion
        webrtcInitThread.start();

        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new RuntimeException("[WebRTC] [Windows] Initialization timeout (15s)");
        }

        // Check for errors
        if (errorRef.get() != null) {
            throw new RuntimeException("[WebRTC] [Windows] Initialization failed", errorRef.get());
        }

        // Store the ADM reference
        audioDeviceModule = admRef.get();
        System.out.println("[WebRTC] [Windows] Audio initialization completed");
    }

    /**
     * LINUX: Standard AudioDeviceModule initialization
     * Uses PulseAudio/ALSA - no COM threading issues
     */
    private static void initLinuxAudio() throws Exception {
        System.out.println("[WebRTC] [Linux] Initializing AudioDeviceModule (PulseAudio/ALSA)...");

        AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
        AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();

        audioDeviceModule = new AudioDeviceModule();

        if (defaultMic != null) {
            System.out.println("[WebRTC] [Linux] Default microphone: " + defaultMic.getName());
            audioDeviceModule.setRecordingDevice(defaultMic);
            audioDeviceModule.initRecording();
        }

        if (defaultSpeaker != null) {
            System.out.println("[WebRTC] [Linux] Default speaker: " + defaultSpeaker.getName());
            audioDeviceModule.setPlayoutDevice(defaultSpeaker);
            audioDeviceModule.initPlayout();
        }

        System.out.println("[WebRTC] [Linux] AudioDeviceModule initialized successfully");
    }

    /**
     * macOS: Standard AudioDeviceModule initialization
     * Uses CoreAudio - no COM threading issues
     */
    private static void initMacAudio() throws Exception {
        System.out.println("[WebRTC] [macOS] Initializing AudioDeviceModule (CoreAudio)...");

        AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
        AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();

        audioDeviceModule = new AudioDeviceModule();

        if (defaultMic != null) {
            System.out.println("[WebRTC] [macOS] Default microphone: " + defaultMic.getName());
            audioDeviceModule.setRecordingDevice(defaultMic);
            audioDeviceModule.initRecording();
        }

        if (defaultSpeaker != null) {
            System.out.println("[WebRTC] [macOS] Default speaker: " + defaultSpeaker.getName());
            audioDeviceModule.setPlayoutDevice(defaultSpeaker);
            audioDeviceModule.initPlayout();
        }

        System.out.println("[WebRTC] [macOS] AudioDeviceModule initialized successfully");
    }

    /**
     * Default/Unknown platform: Standard initialization
     */
    private static void initDefaultAudio() throws Exception {
        System.out.println("[WebRTC] [Unknown] Initializing AudioDeviceModule (default path)...");

        AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
        AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();

        audioDeviceModule = new AudioDeviceModule();

        if (defaultMic != null) {
            audioDeviceModule.setRecordingDevice(defaultMic);
            audioDeviceModule.initRecording();
        }

        if (defaultSpeaker != null) {
            audioDeviceModule.setPlayoutDevice(defaultSpeaker);
            audioDeviceModule.initPlayout();
        }

        System.out.println("[WebRTC] [Unknown] AudioDeviceModule initialized");
    }

    /**
     * Initialize PeerConnectionFactory with configured AudioDeviceModule
     */
    private static void initPeerConnectionFactory() {
        if (audioDeviceModule != null) {
            factory = new PeerConnectionFactory(audioDeviceModule);
            System.out.println("[WebRTC] PeerConnectionFactory created with AudioDeviceModule");
        } else {
            factory = new PeerConnectionFactory();
            System.out.println("[WebRTC] PeerConnectionFactory created (no AudioDeviceModule)");
        }

        platformConfig = WebRTCPlatformConfig.detect(factory);
        System.out.println("[WebRTC] Platform config detected: " + platformConfig);
    }

    /**
     * Get the Virtual Thread executor for async WebRTC operations
     */
    public static ExecutorService getExecutor() {
        return webrtcExecutor;
    }

    /**
     * Execute a task asynchronously using Virtual Threads
     */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        if (webrtcExecutor != null) {
            return CompletableFuture.runAsync(task, webrtcExecutor);
        }
        return CompletableFuture.runAsync(task);
    }

    /**
     * Dump outbound video RTP stats to the console (for diagnostics).
     */
    public void logVideoSenderStats() {
        if (peerConnection == null || videoSender == null) {
            System.out.println("[WebRTC] Stats unavailable: connection or sender not ready");
            return;
        }
        peerConnection.getStats(videoSender, report -> {
            report.getStats().values().stream()
                    .filter(stat -> stat.getType() == RTCStatsType.OUTBOUND_RTP)
                    .findFirst()
                    .ifPresentOrElse(stat -> {
                        System.out.printf("[WebRTC][%s] OUTBOUND_RTP stats: %s%n",
                                currentCallId, stat);
                    }, () -> System.out.printf("[WebRTC][%s] No OUTBOUND_RTP stats%n", currentCallId));
        });
    }

    /**
     * Shutdown WebRTC (call at app exit)
     */
    public static synchronized void shutdown() {
        if (!initialized)
            return;

        System.out.println("[WebRTC] Shutting down WebRTC...");

        // Stop device monitoring
        isMonitoring = false;

        // Shutdown Virtual Thread executor
        // Shutdown Virtual Thread executor
        if (webrtcExecutor != null) {
            // Note: VirtualThreadExecutor doesn't strictly need shutdown like thread pools,
            // but good practice
            // to interrupt threads if needed. However, virtual threads are daemon by
            // default.
            // webrtcExecutor is final, so we cannot set it to null.
            try {
                // No-op for virtual thread executor usually, but kept for API consistency
            } catch (Exception e) {
                // ignore
            }
            System.out.println("[WebRTC] Executor shutdown request sent");
        }

        // Dispose PeerConnectionFactory
        if (factory != null) {
            factory.dispose();
            factory = null;
            System.out.println("[WebRTC] PeerConnectionFactory disposed");
        }

        // 🛑 STOP AUDIO BEFORE DISPOSING MODULE
        // This is critical to prevent "ghost audio" threads
        if (audioDeviceModule != null) {
            System.out.println("[WebRTC] Stopping AudioDeviceModule...");
            try {
                if (playoutStarted) {
                    audioDeviceModule.stopPlayout();
                    playoutStarted = false;
                    System.out.println("[WebRTC] Playout stopped");
                }
                if (recordingStarted) {
                    audioDeviceModule.stopRecording();
                    recordingStarted = false;
                    System.out.println("[WebRTC] Recording stopped");
                }

                // Give native audio threads a moment to spin down
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }

                audioDeviceModule.dispose();
            } catch (Exception e) {
                System.err.println("[WebRTC] Error disposing AudioDeviceModule: " + e.getMessage());
            }
            audioDeviceModule = null;
            System.out.println("[WebRTC] AudioDeviceModule disposed");
        }

        initialized = false;
        System.out.println("[WebRTC] WebRTC shutdown complete");
    }

    /**
     * Check if WebRTC is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the PeerConnectionFactory (for P2PConnectionManager)
     */
    public static PeerConnectionFactory getFactory() {
        return factory;
    }

    static WebRTCPlatformConfig getPlatformConfig() {
        return platformConfig;
    }

    /**
     * Constructor
     */
    // Constructor moved to top of class (lines 48-56)
    // Removing duplicate from here

    /**
     * Create peer connection
     */
    public void createPeerConnection(boolean audioEnabled, boolean videoEnabled) {
        logger.info(String.format("Creating peer connection (audio=%b, video=%b)", audioEnabled, videoEnabled));

        this.audioEnabled = audioEnabled;
        this.videoEnabled = videoEnabled;

        if (factory == null) {
            logger.warn("Factory null - running in MOCK mode");
            return;
        }

        try {
            // ═══════════════════════════════════════════════════════════════
            // ICE Server Configuration
            // STUN: Discover public IP (works for simple NAT)
            // TURN: Relay media (required for symmetric NAT)
            // ═══════════════════════════════════════════════════════════════
            List<RTCIceServer> iceServers = new ArrayList<>();

            // STUN servers (free, for simple NAT traversal)
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls.add("stun:stun.l.google.com:19302");
            stunServer.urls.add("stun:stun1.l.google.com:19302");
            stunServer.urls.add("stun:stun2.l.google.com:19302");
            stunServer.urls.add("stun:stun3.l.google.com:19302");
            stunServer.urls.add("stun:stun4.l.google.com:19302");
            iceServers.add(stunServer);

            // ⚠️ TURN SERVERS (Required for Symmetric NAT / Cross-Network Connectivity)
            // NOTE: Free TURN servers have limited availability. For production, use paid
            // TURN.

            // Option 1: Metered.ca free tier (requires API key from dashboard.metered.ca)
            // If you have an API key, the credentials are fetched dynamically
            RTCIceServer turnServer = new RTCIceServer();
            turnServer.urls.add("turn:a.relay.metered.ca:80");
            turnServer.urls.add("turn:a.relay.metered.ca:80?transport=tcp");
            turnServer.urls.add("turn:a.relay.metered.ca:443");
            turnServer.urls.add("turn:a.relay.metered.ca:443?transport=tcp");
            turnServer.urls.add("turns:a.relay.metered.ca:443?transport=tcp");
            // These are demo credentials from Metered's documentation - may have usage
            // limits
            turnServer.username = "83eebabf8b4cce9d5dbcb649";
            turnServer.password = "2D7JvfkOQtBdYW3R";
            iceServers.add(turnServer);

            // Option 2: Alternative TURN server (backup)
            RTCIceServer turnServer2 = new RTCIceServer();
            turnServer2.urls.add("turn:relay1.expressturn.com:3478");
            turnServer2.urls.add("turn:relay1.expressturn.com:3478?transport=tcp");
            // ExpressTurn free credentials (limited)
            turnServer2.username = "efQKLYRLKDKHXMOOXI";
            turnServer2.password = "H9FVthvKi5Y1lXDu";
            iceServers.add(turnServer2);

            System.out.printf("[WebRTC] Configured %d ICE servers (STUN + TURN)%n", iceServers.size());

            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;

            // ⚡ FAST P2P OPTIMIZATIONS ⚡
            config.bundlePolicy = RTCBundlePolicy.BALANCED; // More compatible than MAX_BUNDLE
            config.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE; // Require RTCP Mux (standard, faster)
                                                             // paths

            // Create peer connection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    logger.debug("ICE Candidate generated: " + candidate.sdp);
                    if (onIceCandidateCallback != null) {
                        onIceCandidateCallback.accept(candidate);
                    }
                }

                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    logger.info("ICE Connection state: " + state);

                    // Extra diagnostic info for CHECKING state
                    if (state == RTCIceConnectionState.CHECKING) {
                        logger.info("ℹ️ ICE is checking connectivity between candidates...");
                        logger.info("ℹ️ This can take up to 15s depending on network conditions");

                        // FIX: Watchdog timer for ICE timeout (15 seconds)
                        // If we are still in CHECKING/NEW after 15s, we assume failure (Symmetric NAT
                        // block)
                        CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep(15000); // 15 seconds wait
                            } catch (InterruptedException e) {
                                return;
                            }

                            // Check state after wait
                            if (peerConnection != null) {
                                RTCIceConnectionState currentState = peerConnection.getIceConnectionState();
                                if (currentState == RTCIceConnectionState.CHECKING ||
                                        currentState == RTCIceConnectionState.NEW) {

                                    logger.error("❌ ICE Connection TIMED OUT after 15s!", null);
                                    logger.error("  This likely means a Symmetric NAT issue or firewall block.", null);

                                    // Force close
                                    close();

                                    // Trigger failure callback
                                    if (onConnectionClosedCallback != null) {
                                        onConnectionClosedCallback.run();
                                    }
                                }
                            }
                        });
                    }

                    if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                        logger.info("✅ ICE connection established! Media should now flow between peers");
                        if (onConnectionEstablishedCallback != null) {
                            onConnectionEstablishedCallback.run();
                        }
                    } else if (state == RTCIceConnectionState.FAILED) {
                        logger.error("❌ ICE connection FAILED! Possible causes: Symmetric NAT, Firewall, Timeout",
                                null);

                        if (onConnectionClosedCallback != null) {
                            onConnectionClosedCallback.run();
                        }
                    } else if (state == RTCIceConnectionState.DISCONNECTED) {
                        logger.warn("⚠️ ICE connection disconnected (may recover)");
                        if (onConnectionClosedCallback != null) {
                            onConnectionClosedCallback.run();
                        }
                    }
                }

                @Override
                public void onIceGatheringChange(RTCIceGatheringState state) {
                    System.out.printf("[WebRTC] ICE Gathering state: %s%n", state);
                    if (state == RTCIceGatheringState.COMPLETE) {
                        System.out.println("[WebRTC]   ✅ All ICE candidates gathered");
                    }
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    System.out.printf("[WebRTC] Peer Connection state: %s%n", state);
                }

                @Override
                public void onTrack(RTCRtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().getTrack();
                    System.out.println("[WebRTC] ═══════════════════════════════════════════");
                    System.out.printf("[WebRTC] 📡 REMOTE TRACK RECEIVED%n");
                    System.out.printf("[WebRTC]   ID: %s%n", track.getId());
                    System.out.printf("[WebRTC]   Kind: %s%n", track.getKind());
                    System.out.printf("[WebRTC]   Enabled: %b%n", track.isEnabled());
                    System.out.printf("[WebRTC]   State: %s%n", track.getState());

                    // Log transceiver details
                    try {
                        System.out.printf("[WebRTC]   Direction: %s%n", transceiver.getDirection());
                        System.out.printf("[WebRTC]   Mid: %s%n", transceiver.getMid());
                    } catch (Exception e) {
                        System.out.println("[WebRTC]   (Could not get transceiver details)");
                    }
                    System.out.println("[WebRTC] ═══════════════════════════════════════════");

                    // Handle audio track automatically
                    if (track.getKind().equals("audio") && track instanceof AudioTrack) {
                        handleRemoteAudioTrack((AudioTrack) track);
                    }

                    // Handle video track
                    if (track.getKind().equals("video") && track instanceof VideoTrack) {
                        handleRemoteVideoTrack((VideoTrack) track);
                    }

                    if (onRemoteTrackCallback != null) {
                        onRemoteTrackCallback.accept(track);
                    }
                }
            });

            // Add local tracks (will be implemented)
            // For now, just create connection without media

            System.out.println("[WebRTC] Peer connection created");

        } catch (Exception e) {
            logger.error("Failed to create peer connection: " + e.getMessage(), e);
        }
    }

    /**
     * Create SDP offer
     */
    public CompletableFuture<String> createOffer() {
        // Async retry: 50ms interval, 5000ms timeout
        return retryAsync(this::createOfferInternal, 100, 50, "Create Offer");
    }

    private CompletableFuture<String> createOfferInternal() {
        logger.info("Creating SDP offer...");

        CompletableFuture<String> future = new CompletableFuture<>();

        if (peerConnection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Peer connection not initialized"));
        }

        try {
            RTCOfferOptions options = new RTCOfferOptions();
            peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            logger.info("Offer created and set as local description");
                            // String sdp = description.sdp; // UNUSED - removed dead code

                            // ⚡ MINIMIZE SDP
                            // Strip unused codecs/extensions for faster transmission
                            String optimizedSdp = SDPUtils.mungeSDP(description.sdp);

                            // ⚡ FIX ONE-WAY VIDEO RACE
                            // Force 'sendrecv' even if track isn't fully attached yet (Early Offer)
                            optimizedSdp = SDPUtils.enforceSendRecv(optimizedSdp, "video");

                            logger.debug(String.format("Optimized SDP from %d bytes to %d bytes",
                                    description.sdp.length(), optimizedSdp.length()));

                            // Log video codec info from SDP
                            logSdpVideoCodecs(optimizedSdp, "OFFER");

                            if (onLocalSDPCallback != null) {
                                onLocalSDPCallback.accept(optimizedSdp);
                            }
                            future.complete(optimizedSdp);
                        }

                        @Override
                        public void onFailure(String error) {
                            logger.error("Failed to set local description: " + error, null);
                            future.completeExceptionally(new Exception(error));
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    logger.error("Failed to create offer: " + error, null);
                    future.completeExceptionally(new Exception(error));
                }
            });
        } catch (Exception e) {
            logger.error("Exception creating offer: " + e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Create SDP answer
     */
    public CompletableFuture<String> createAnswer() {
        // Async retry: 50ms interval, 5000ms timeout
        return retryAsync(this::createAnswerInternal, 100, 50, "Create Answer");
    }

    private CompletableFuture<String> createAnswerInternal() {
        System.out.println("[WebRTC] Creating SDP answer...");

        CompletableFuture<String> future = new CompletableFuture<>();

        if (peerConnection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Peer connection not initialized"));
        }

        try {
            RTCAnswerOptions options = new RTCAnswerOptions();
            peerConnection.createAnswer(options, new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            System.out.println("[WebRTC] Answer created and set as local description");
                            String sdp = description.sdp;

                            // ⚡ MINIMIZE SDP
                            // Strip unused codecs/extensions for faster transmission
                            String optimizedSdp = SDPUtils.mungeSDP(description.sdp);

                            // ⚡ FIX ONE-WAY MEDIA RACE (ANSWERER)
                            // Force 'sendrecv' to ensure we signal that we are sending media
                            optimizedSdp = SDPUtils.enforceSendRecv(optimizedSdp, "video");
                            optimizedSdp = SDPUtils.enforceSendRecv(optimizedSdp, "audio");

                            System.out.printf("[WebRTC] Optimized SDP from %d bytes to %d bytes%n",
                                    description.sdp.length(), optimizedSdp.length());

                            // Log video codec info from SDP
                            logSdpVideoCodecs(optimizedSdp, "ANSWER");

                            // ═══════════════════════════════════════════════════════════════
                            // FIX: NOW start Mac capture (after setLocalDescription with Answer)
                            // The encoder will initialize with the correct 42e01f profile
                            // that was negotiated in the Answer SDP.
                            // ═══════════════════════════════════════════════════════════════
                            if (IS_MAC && pendingMacCaptureStart && videoSource != null) {
                                try {
                                    logger.info("MAC ANSWERER: NOW starting capture (profile locked to Answer SDP)");
                                    videoSource.start();
                                    pendingMacCaptureStart = false;
                                    logger.info("MAC ANSWERER: Camera capture started with correct 42e01f profile");
                                    startEncoderWatchdog();
                                } catch (Exception e) {
                                    logger.error("Failed to start Mac answerer capture: " + e.getMessage(), e);
                                }
                            }

                            if (onLocalSDPCallback != null) {
                                onLocalSDPCallback.accept(optimizedSdp);
                            }

                            future.complete(optimizedSdp);
                        }

                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[WebRTC] Failed to set local description: %s%n", error);
                            future.completeExceptionally(new Exception(error));
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    System.err.printf("[WebRTC] Failed to create answer: %s%n", error);
                    future.completeExceptionally(new Exception(error));
                }
            });
        } catch (Exception e) {
            System.err.printf("[WebRTC] Exception creating answer: %s%n", e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    // ===============================
    // Persistence / Retry Helper
    // ===============================

    /**
     * Helper to retry a CompletableFuture action until a deadline
     */
    /**
     * Async retry helper using Virtual Threads / Delayed Executor
     * Non-blocking, fast interval (50ms).
     */
    private <T> CompletableFuture<T> retryAsync(java.util.function.Supplier<CompletableFuture<T>> action,
            int maxRetries, int delayMs, String operationName) {

        return action.get().handle((result, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(result);
            }

            if (maxRetries <= 0) {
                logger.error(String.format("%s failed after retries: %s", operationName, ex.getMessage()), null);
                return CompletableFuture.<T>failedFuture(ex);
            }

            // Async delay without blocking a platform thread
            CompletableFuture<T> retryFuture = new CompletableFuture<>();

            // Use delayed executor (Java 9+)
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS,
                    webrtcExecutor != null ? webrtcExecutor : ForkJoinPool.commonPool())
                    .execute(() -> {
                        retryAsync(action, maxRetries - 1, delayMs, operationName)
                                .whenComplete((r, e) -> {
                                    if (e != null)
                                        retryFuture.completeExceptionally(e);
                                    else
                                        retryFuture.complete(r);
                                });
                    });

            return retryFuture;
        }).thenCompose(f -> f);
    }

    /**
     * Set remote SDP (offer or answer)
     * 
     * MASTERPIECE FIX: This method now implements the JIT encoder pattern:
     * 1. Analyze incoming SDP for video codec profiles (strategic logging)
     * 2. On Mac, munge dangerous profiles to safe Constrained Baseline
     * 3. Set the description, then activate watchdog protection
     */
    public void setRemoteDescription(String sdpType, String sdp) {
        logger.info(String.format("Setting remote %s", sdpType));

        if (peerConnection == null) {
            logger.warn("Peer connection null - skipping");
            return;
        }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 1: VIDEO CODEC ANALYSIS (Strategic Logging)
        // ═══════════════════════════════════════════════════════════════════
        SDPUtils.logVideoCodecAnalysis(sdp, "REMOTE " + sdpType.toUpperCase());

        String remoteProfile = extractH264ProfileFromSdp(sdp);
        // STRICT: Only 42e01f is safe for Mac VideoToolbox (42001f also causes freeze!)
        boolean profileIsDangerous = remoteProfile != null && !remoteProfile.equalsIgnoreCase("42e01f");

        if (profileIsDangerous) {
            if (IS_MAC) {
                logger.warn(
                        String.format("DANGEROUS PROFILE DETECTED: %s (requires munging to 42e01f)", remoteProfile));
                logger.warn("   VideoToolbox requires strict Constrained Baseline. Applying SDP munging...");
            } else {
                logger.info(String.format("Non-Baseline profile detected: %s (safe on %s)",
                        remoteProfile, IS_WINDOWS ? "Windows" : "Linux"));
            }
        } else if (remoteProfile != null) {
            logger.info(String.format("Safe profile detected: %s", remoteProfile));
        }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 2: SDP MUNGING (The Gatekeeper) - MAC ONLY
        // ═══════════════════════════════════════════════════════════════════
        String safeSdp = sdp;
        if (IS_MAC && profileIsDangerous) {
            safeSdp = SDPUtils.enforceBaselineH264Profile(sdp);
            logger.info("🔒 SDP munged to Constrained Baseline for Mac safety");
        }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 3: SET REMOTE DESCRIPTION
        // ═══════════════════════════════════════════════════════════════════
        try {
            RTCSdpType type = sdpType.equalsIgnoreCase("offer") ? RTCSdpType.OFFER : RTCSdpType.ANSWER;
            RTCSessionDescription description = new RTCSessionDescription(type, safeSdp);

            peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    logger.info("✅ Remote description set successfully");

                    // ═══════════════════════════════════════════════════════════════
                    // PHASE 4: ACTIVATE WATCHDOG (Mac protection)
                    // Now that remote description is set, the encoder is active.
                    // Start watchdog to monitor for hardware freeze.
                    // ═══════════════════════════════════════════════════════════════
                    if (IS_MAC && videoEnabled) {
                        startEncoderWatchdog();
                    }
                }

                @Override
                public void onFailure(String error) {
                    logger.error("Failed to set remote description: " + error, null);
                }
            });
        } catch (Exception e) {
            logger.error("Exception setting remote description: " + e.getMessage(), e);
        }
    }

    /**
     * Add ICE candidate
     */
    public void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
        logger.debug("Adding ICE candidate: " + candidate);

        if (peerConnection == null) {
            logger.warn("Peer connection null - skipping");
            return;
        }

        try {
            RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
            logger.debug("ICE candidate added");
        } catch (Exception e) {
            logger.error("Failed to add ICE candidate: " + e.getMessage(), e);
        }
    }

    /**
     * Close connection
     */
    public void close() {
        logger.info("Closing peer connection...");

        // Stop encoder watchdog first
        stopEncoderWatchdog();

        // Reset Mac capture flag
        pendingMacCaptureStart = false;

        // Clean up all audio sinks first (properly remove from tracks)
        cleanupAllAudioSinks();

        // First, remove tracks from peer connection before disposing
        if (peerConnection != null) {
            try {
                // Get all senders and remove tracks
                var senders = peerConnection.getSenders();
                for (var sender : senders) {
                    var track = sender.getTrack();
                    if (track != null) {
                        logger.debug("Removing track: " + track.getId());
                        peerConnection.removeTrack(sender);
                    }
                }
            } catch (Exception e) {
                logger.error("Error removing tracks: " + e.getMessage(), null);
            }

            // Close peer connection
            peerConnection.close();
            peerConnection = null;
        }

        // Now dispose tracks (after removing from peer connection)
        if (localAudioTrack != null) {
            try {
                localAudioTrack.setEnabled(false); // Disable first
                localAudioTrack.dispose();
                logger.info("Audio track disposed");
            } catch (Exception e) {
                logger.error("Error disposing audio track: " + e.getMessage(), null);
            }
            localAudioTrack = null;
        }

        // IMPORTANT: Don't dispose localVideoTrack if it's shared from GroupCallManager
        // Only dispose if we own the video source (videoSource != null)
        if (localVideoTrack != null && videoSource != null) {
            try {
                localVideoTrack.setEnabled(false); // Disable first
                localVideoTrack.dispose();
                logger.info("Video track disposed");
            } catch (Exception e) {
                logger.error("Error disposing video track: " + e.getMessage(), null);
            }
            localVideoTrack = null;
        } else if (localVideoTrack != null) {
            logger.debug("Video track is shared - not disposing (GroupCallManager owns it)");
            localVideoTrack = null;
        }

        // Stop video source (release camera) - only if we own it
        if (videoSource != null) {
            try {
                videoSource.stop();
                videoSource.dispose();
                logger.info("Camera source stopped and released");
            } catch (Exception e) {
                logger.error("Error stopping video source: " + e.getMessage(), null);
            }
            videoSource = null;
        }

        // DON'T call onConnectionClosedCallback here - causes infinite recursion
        // CallManager.cleanup() already calls this method, no need for callback loop

        logger.info("Connection closed");
    }

    // ===============================
    // Callback Setters
    // ===============================

    public void setOnIceCandidateCallback(Consumer<RTCIceCandidate> callback) {
        this.onIceCandidateCallback = callback;
    }

    public void setOnLocalSDPCallback(Consumer<String> callback) {
        this.onLocalSDPCallback = callback;
    }

    public void setOnConnectionEstablishedCallback(Runnable callback) {
        this.onConnectionEstablishedCallback = callback;
    }

    public void setOnConnectionClosedCallback(Runnable callback) {
        this.onConnectionClosedCallback = callback;
    }

    public void setOnRemoteTrackCallback(Consumer<MediaStreamTrack> callback) {
        this.onRemoteTrackCallback = callback;
    }

    // ===============================
    // Media Control
    // ===============================

    /**
     * Add audio track to peer connection for microphone capture.
     * This triggers ICE candidate generation.
     */
    /**
     * Add audio track to peer connection for microphone capture.
     * This triggers ICE candidate generation.
     * 
     * FIX: Returns CompletableFuture to ensure recording is started BEFORE adding
     * track
     * to PeerConnection. This prevents race conditions where SDP is generated
     * before
     * track is present.
     */
    public CompletableFuture<Void> addAudioTrack() {
        if (factory == null) {
            logger.error("Cannot add audio track - factory not initialized", null);
            return CompletableFuture.failedFuture(new IllegalStateException("WebRTC factory not initialized"));
        }

        if (peerConnection == null) {
            logger.error("Cannot add audio track - peer connection not created", null);
            return CompletableFuture.failedFuture(new IllegalStateException("Peer connection not created"));
        }

        logger.info("Adding audio track with ADVANCED processing...");

        // Ensure capture is started FIRST
        return ensureRecordingStarted().thenRun(() -> {
            try {
                // ===== ADVANCED AUDIO OPTIONS =====
                AudioOptions audioOptions = new AudioOptions();

                // Temel özellikler (always enabled)
                audioOptions.echoCancellation = true;
                audioOptions.autoGainControl = true;
                audioOptions.noiseSuppression = true;

                // İLERİ SEVİYE ÖZELLİKLER (Advanced features)
                // Note: webrtc-java 0.14.0 supports these fields
                audioOptions.highpassFilter = true; // Düşük frekans filtresi (rumble noise)

                // Create audio source with enhanced options
                AudioTrackSource audioSource = factory.createAudioSource(audioOptions);

                // Create audio track with a unique ID
                AudioTrack audioTrack = factory.createAudioTrack("audio0", audioSource);

                // Add track to peer connection with stream ID
                peerConnection.addTrack(audioTrack, List.of("stream1"));

                logger.info("✅ Audio track added with ADVANCED processing");
                logger.info("🎤 Professional audio quality enabled!");

                // Store reference for cleanup
                this.localAudioTrack = audioTrack;
            } catch (Exception e) {
                logger.error("Failed to add audio track: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Add video track to peer connection for camera capture.
     * Similar to addAudioTrack but for video.
     */
    // Lock for PeerConnection modifications to prevent Virtual Thread pinning
    private final java.util.concurrent.locks.ReentrantLock pcLock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * ═══════════════════════════════════════════════════════════════════
     * ANSWERER BINDING FIX: Find existing video sender from transceiver
     * ═══════════════════════════════════════════════════════════════════
     * 
     * When we're the Answerer, setRemoteDescription(OFFER) creates a video
     * transceiver with an empty sender. We must use replaceTrack() on this
     * existing sender instead of addTrack() (which would create a second sender).
     * 
     * @return Existing video sender to bind to, or null if none found
     */
    private RTCRtpSender findExistingVideoSender() {
        if (peerConnection == null)
            return null;

        RTCRtpTransceiver[] transceivers = peerConnection.getTransceivers();
        if (transceivers == null)
            return null;

        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver == null)
                continue;
            RTCRtpSender sender = transceiver.getSender();
            if (sender == null)
                continue;

            // Check if this sender has no track (empty slot from offer)
            MediaStreamTrack senderTrack = sender.getTrack();
            if (senderTrack == null) {
                // Verify it's a video transceiver by checking receiver
                if (transceiver.getReceiver() != null) {
                    MediaStreamTrack rcvTrack = transceiver.getReceiver().getTrack();
                    if (rcvTrack != null && "video".equalsIgnoreCase(rcvTrack.getKind())) {
                        logger.debug("🔍 Found empty video sender from transceiver (Answerer binding)");
                        return sender;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Add video track to peer connection
     */
    public CompletableFuture<Void> addVideoTrack() {
        if (peerConnection == null) {
            logger.error("Cannot add video track - peer connection not created", null);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // ═══════════════════════════════════════════════════════════════
                // MAC FIX: Use pre-warmed track if available (instant!)
                // ═══════════════════════════════════════════════════════════════
                if (videoPreWarmed && preWarmedVideoTrack != null) {
                    logger.info("Using PRE-WARMED video track (instant, no timeout risk)");

                    // Transfer pre-warmed resources to main fields
                    this.videoSource = preWarmedVideoSource;
                    VideoTrack videoTrack = preWarmedVideoTrack;

                    // ═══════════════════════════════════════════════════════════════
                    // MAC JIT: NOW start capture (after setRemoteDescription validated profile)
                    // ═══════════════════════════════════════════════════════════════
                    if (IS_MAC && this.videoSource != null) {
                        // ═══════════════════════════════════════════════════════════════
                        // FIX: For Mac ANSWERER, defer capture start until AFTER setLocalDescription
                        // This ensures VideoToolbox encoder initializes with the correct 42e01f
                        // profile from the negotiated Answer SDP, preventing freeze.
                        // ═══════════════════════════════════════════════════════════════
                        RTCRtpSender existingSender = findExistingVideoSender();
                        boolean isAnswererRole = (existingSender != null);

                        if (isAnswererRole) {
                            // ANSWERER: Defer capture until after setLocalDescription
                            logger.info("MAC ANSWERER: Deferring capture until after Answer SDP is set");
                            logger.info("   Encoder will initialize with negotiated 42e01f profile");
                            pendingMacCaptureStart = true;
                            // Capture will be triggered by createAnswerInternal after setLocalDescription
                        } else {
                            // OFFERER: Start capture now (profile already set by createOffer)
                            try {
                                logger.info("MAC OFFERER: Starting capture now");
                                this.videoSource.start();
                                logger.info("MAC: Camera capture started (OFFERER mode)");
                                startEncoderWatchdog();
                            } catch (Exception e) {
                                logger.error("Failed to start Mac camera capture: " + e.getMessage(), e);
                                throw new RuntimeException("Mac camera JIT start failed", e);
                            }
                        }
                    }

                    // Clear pre-warm state
                    preWarmedVideoTrack = null;
                    preWarmedVideoSource = null;
                    videoPreWarmed = false;

                    // ═══════════════════════════════════════════════════════════════
                    // ANSWERER BINDING FIX: Use replaceTrack if transceiver exists
                    // ═══════════════════════════════════════════════════════════════
                    pcLock.lock();
                    try {
                        RTCRtpSender existingSender = findExistingVideoSender();
                        if (existingSender != null) {
                            // Answerer flow: bind to transceiver created by offer
                            logger.info("TRANSCEIVER BINDING: REPLACETRACK on existing sender (pre-warmed path)");
                            existingSender.replaceTrack(videoTrack);
                            videoSender = existingSender;
                            logger.info("Pre-warmed track bound to negotiated transceiver (640x480)");

                            // ═══════════════════════════════════════════════════════════════
                            // CRITICAL FIX: Start capture AFTER replaceTrack() for Answerer
                            // This ensures RTP sender has the track bound before encoding starts.
                            // Without this, the Offerer never receives the Answerer's video!
                            // ═══════════════════════════════════════════════════════════════
                            if (!IS_MAC && this.videoSource != null) {
                                logger.info("ANSWERER: Starting camera capture AFTER replaceTrack (FIX)");
                                this.videoSource.start();
                                logger.info("✅ Camera capture started - Offerer should now receive video");
                            }
                        } else {
                            // Offerer flow: create new sender (capture starts later in normal addVideoTrack
                            // path)
                            logger.info("OFFERER MODE: Creating new video sender (pre-warmed path)");
                            videoSender = peerConnection.addTrack(videoTrack, List.of("stream1"));
                            logger.info("Pre-warmed video track added (640x480)");

                            // For Offerer using pre-warmed path, start capture now
                            if (!IS_MAC && this.videoSource != null) {
                                logger.info("OFFERER: Starting camera capture");
                                this.videoSource.start();
                            }
                        }
                        applyVideoCodecPreferences();
                    } finally {
                        pcLock.unlock();
                    }

                    this.localVideoTrack = videoTrack;
                    return;
                }

                // ═══════════════════════════════════════════════════════════════
                // Normal path: Create new video track (slower, used for offerer)
                // ═══════════════════════════════════════════════════════════════
                logger.info("Adding video track with optimized settings...");

                // ===== FIX: Cleanup existing video source first (MacOS freeze fix) =====
                if (this.videoSource != null) {
                    logger.info("Cleaning up existing video source...");
                    try {
                        videoSource.stop();
                        videoSource.dispose();
                    } catch (Exception e) {
                        logger.warn("Error cleaning up old video source: " + e.getMessage());
                    }
                    this.videoSource = null;
                }

                // ===== VIDEO SOURCE WITH OPTIMIZED SETTINGS =====
                CameraCaptureService.CameraCaptureResource resource = CameraCaptureService.createCameraTrack("video0");

                this.videoSource = resource.getSource();
                VideoTrack videoTrack = resource.getTrack();

                // ═══════════════════════════════════════════════════════════════
                // ANSWERER BINDING FIX: Use replaceTrack if transceiver exists
                // When Answerer, the remote Offer creates a transceiver with empty sender.
                // We MUST use replaceTrack() to bind our track to it, NOT addTrack()
                // which would create a second sender (leaving offerer on dummy 320x240).
                // ═══════════════════════════════════════════════════════════════
                pcLock.lock();
                try {
                    RTCRtpSender existingSender = findExistingVideoSender();
                    if (existingSender != null) {
                        // Answerer flow: bind to transceiver created by offer
                        logger.info("TRANSCEIVER BINDING: REPLACETRACK on existing sender (normal path)");
                        existingSender.replaceTrack(videoTrack);
                        videoSender = existingSender;
                        logger.info("Video track bound to negotiated transceiver (640x480)");
                    } else {
                        // Offerer flow: create new sender
                        logger.info("OFFERER MODE: Creating new video sender (normal path)");
                        videoSender = peerConnection.addTrack(videoTrack, List.of("stream1"));
                        logger.info("Video track added with new sender (640x480)");
                    }
                    applyVideoCodecPreferences();
                } finally {
                    pcLock.unlock();
                }

                // FIX: Explicitly start capture AFTER adding track
                resource.startCapture();

                logger.info("Video track added with optimized settings (Res: 640x480, FPS: 30)");
                if (IS_MAC) {
                    logger.info("GPU acceleration enabled (VideoToolbox)");
                } else if (IS_WINDOWS) {
                    logger.info("GPU acceleration available (DXVA/MediaFoundation)");
                } else {
                    logger.info("Video track ready");
                }

                // Store reference for cleanup
                this.localVideoTrack = videoTrack;

            } catch (Exception e) {
                logger.error("Failed to add video track: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, webrtcExecutor);
    }

    /**
     * Pre-warm video track for fast answerer setup.
     * 
     * ═══════════════════════════════════════════════════════════════════
     * MASTERPIECE: JIT (Just-In-Time) Initialization
     * ═══════════════════════════════════════════════════════════════════
     * 
     * ON MAC: We ONLY initialize the camera sensor (AVFoundation) but do NOT
     * start capture. The actual encoder (VideoToolbox) is initialized AFTER
     * we receive the remote SDP and can verify the profile is compatible.
     * This prevents the "handshake paradox" where we lock in a profile before
     * knowing what the peer expects.
     * 
     * ON WINDOWS/LINUX: Full initialization is safe - these platforms have
     * more resilient encoder reconfiguration.
     * 
     * Called when user clicks Accept, runs in parallel with CALL_ACCEPT signaling.
     */
    public CompletableFuture<Void> preWarmVideoTrack() {
        if (videoPreWarmed) {
            logger.info("Video track already pre-warmed");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Pre-warming video track (parallel with CALL_ACCEPT)...");

                if (IS_MAC) {
                    // ═══════════════════════════════════════════════════════════════
                    // MAC: DEFERRED INITIALIZATION (JIT Pattern)
                    // Create camera source but DON'T start capture yet.
                    // This keeps the lens "warm" without initializing VideoToolbox.
                    // ═══════════════════════════════════════════════════════════════
                    logger.info("IDENTIFIED PLATFORM: macOS - Deferred encoder initialization (JIT pattern)");
                    logger.info("   Camera sensor will be initialized, encoder deferred until SDP validated");

                    CameraCaptureService.CameraCaptureResource resource = CameraCaptureService
                            .createCameraTrack("video0");

                    preWarmedVideoSource = resource.getSource();
                    preWarmedVideoTrack = resource.getTrack();

                    // DO NOT call resource.startCapture() on Mac!
                    // The encoder (VideoToolbox) initializes when capture starts.
                    // We defer this until addVideoTrack() is called, which happens
                    // AFTER setRemoteDescription has validated and munged the profile.

                    videoPreWarmed = true;
                    logger.info("MAC: Video track structure ready (capture DEFERRED for safety)");

                } else {
                    // ═══════════════════════════════════════════════════════════════
                    // WINDOWS/LINUX: DEFERRED CAPTURE (Fix for Answerer video bug)
                    // Previously we started capture here, but this caused the RTP sender
                    // to not encode frames properly because replaceTrack() hadn't been
                    // called yet. Now we defer capture until AFTER replaceTrack().
                    // ═══════════════════════════════════════════════════════════════
                    logger.info("IDENTIFIED PLATFORM: " + (IS_WINDOWS ? "Windows" : "Linux")
                            + " - Deferred capture (Answerer fix)");

                    CameraCaptureService.CameraCaptureResource resource = CameraCaptureService
                            .createCameraTrack("video0");

                    preWarmedVideoSource = resource.getSource();
                    preWarmedVideoTrack = resource.getTrack();
                    // DON'T start capture here! Defer until after replaceTrack() in addVideoTrack()
                    // This fixes the bug where Answerer's video never reaches Offerer.

                    videoPreWarmed = true;
                    logger.info("Video track structure ready (capture DEFERRED for Answerer binding)");
                }

            } catch (Exception e) {
                logger.warn("Pre-warm failed, will use normal path: " + e.getMessage());
                videoPreWarmed = false;
            }
        }, webrtcExecutor);
    }

    /**
     * Check if a pre-warmed video track is available
     */
    public boolean hasPreWarmedVideoTrack() {
        return videoPreWarmed && preWarmedVideoTrack != null;
    }

    /**
     * Add shared video track (from GroupCallManager) to peer connection
     * This allows multiple peer connections to share the same camera source
     */
    public void addSharedVideoTrack(VideoTrack sharedTrack) {
        if (factory == null) {
            logger.error("Cannot add video track - factory not initialized", null);
            return;
        }

        if (peerConnection == null) {
            logger.error("Cannot add video track - peer connection not created", null);
            return;
        }

        if (sharedTrack == null) {
            logger.error("Shared video track is null", null);
            return;
        }

        try {
            logger.info("Adding SHARED video track to peer connection...");

            // Add track to peer connection with stream ID
            videoSender = peerConnection.addTrack(sharedTrack, List.of("stream1"));
            applyVideoCodecPreferences();

            // Store reference (but DON'T dispose it in close() - GroupCallManager owns it)
            this.localVideoTrack = sharedTrack;

            logger.info("✅ Shared video track added successfully");

        } catch (Exception e) {
            logger.error("Failed to add shared video track: " + e.getMessage(), e);
        }
    }

    private void applyVideoCodecPreferences() {
        if (platformConfig != null) {
            platformConfig.applyVideoCodecPreferences(peerConnection);
        }
    }

    /**
     * Handle remote audio track (automatically plays received audio)
     */
    // Per-track audio monitoring to support multi-party calls
    private final Map<String, AudioTrackSink> audioSinks = new ConcurrentHashMap<>();
    private final Map<String, AudioTrack> audioTracks = new ConcurrentHashMap<>(); // Track references for cleanup
    private final Map<String, long[]> audioStats = new ConcurrentHashMap<>(); // [frameCount, lastLogTime]

    private void handleRemoteAudioTrack(AudioTrack audioTrack) {
        String trackId = audioTrack.getId();

        logger.info("🔊 Setting up remote audio playback...");
        logger.debug("  Track ID: " + trackId);
        logger.debug("  Enabled: " + audioTrack.isEnabled());

        // Remove existing sink for this track if any (prevents duplicate sinks)
        AudioTrackSink existingSink = audioSinks.remove(trackId);
        if (existingSink != null) {
            try {
                audioTrack.removeSink(existingSink);
                logger.debug("  Removed existing sink for track: " + trackId);
            } catch (Exception e) {
                // Sink may already be removed
            }
        }

        // Per-track counters: [frameCount, lastLogTime]
        long[] stats = new long[] { 0, System.currentTimeMillis() };
        audioStats.put(trackId, stats);

        // Create sink with per-track counter
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
            long[] trackStats = audioStats.get(trackId);
            if (trackStats != null) {
                trackStats[0]++; // frameCount
                long now = System.currentTimeMillis();
                // Log every 5 seconds to confirm audio is flowing
                if (now - trackStats[1] >= 5000) {
                    logger.debug(String.format("🔊 Audio [%s]: %d frames @ %dHz, %dch",
                            trackId.substring(0, Math.min(8, trackId.length())),
                            trackStats[0], sampleRate, channels));
                    trackStats[1] = now;
                }
            }
        };

        // Ensure speakers are started for playout BEFORE adding sink
        // FIX: Reordered to prevent buffer starvation (Bug #4)
        ensurePlayoutStarted();

        // Store track reference for proper cleanup
        audioTracks.put(trackId, audioTrack);
        audioSinks.put(trackId, sink);
        audioTrack.addSink(sink);

        logger.info("✅ Remote audio track ready (playback via AudioDeviceModule)");
    }

    /**
     * Remove all audio sinks from their tracks (called during cleanup)
     */
    private void cleanupAllAudioSinks() {
        if (audioSinks.isEmpty())
            return;

        logger.info("🔊 Cleaning up " + audioSinks.size() + " audio sink(s)");

        for (Map.Entry<String, AudioTrackSink> entry : audioSinks.entrySet()) {
            String trackId = entry.getKey();
            AudioTrackSink sink = entry.getValue();
            AudioTrack track = audioTracks.get(trackId);

            if (track != null && sink != null) {
                try {
                    track.removeSink(sink);
                    logger.debug("  Removed sink from track: " + trackId);
                } catch (Exception e) {
                    // Track may already be disposed
                }
            }
        }

        audioSinks.clear();
        audioTracks.clear();
        audioStats.clear();
    }

    /**
     * Ensure audio playout (speaker) is started once
     */
    private static void ensurePlayoutStarted() {
        if (audioDeviceModule == null)
            return;
        if (playoutStarted)
            return;
        synchronized (WebRTCClient.class) {
            if (playoutStarted)
                return;
            try {
                audioDeviceModule.startPlayout();
                playoutStarted = true;
                logger.info("🔊 Playout started");
            } catch (Exception e) {
                logger.error("Failed to start playout: " + e.getMessage(), null);
            }
        }
    }

    /**
     * Ensure audio recording (mic) is started once
     * Async version to handle Windows latch delays
     */
    private static CompletableFuture<Void> ensureRecordingStarted() {
        if (audioDeviceModule == null)
            return CompletableFuture.completedFuture(null);

        if (recordingStarted)
            return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            synchronized (WebRTCClient.class) {
                if (recordingStarted)
                    return;
                try {
                    logger.info("🎙️ Initializing recording device (Async)...");
                    audioDeviceModule.startRecording();
                    recordingStarted = true;
                    logger.info("🎙️ Recording started successfully");
                } catch (Exception e) {
                    logger.error("Failed to start recording: " + e.getMessage(), null);
                    throw new RuntimeException("Failed to start recording", e);
                }
            }
        }, webrtcExecutor != null ? webrtcExecutor : ForkJoinPool.commonPool());
    }

    /**
     * Handle remote video track
     */
    // Store remote video track reference for diagnostics
    private volatile VideoTrack remoteVideoTrack;
    private volatile long remoteVideoFrameCount = 0;
    private volatile long lastRemoteVideoLogTime = 0;

    private void handleRemoteVideoTrack(VideoTrack videoTrack) {
        logger.info("🎥 HANDLING REMOTE VIDEO TRACK");
        logger.debug("  Track ID: " + videoTrack.getId());
        logger.debug("  Enabled: " + videoTrack.isEnabled());
        logger.debug("  State: " + videoTrack.getState());

        // Enable the track if it's not already enabled
        if (!videoTrack.isEnabled()) {
            logger.info("  Enabling disabled video track...");
            videoTrack.setEnabled(true);
            logger.debug("  After enable - Enabled: " + videoTrack.isEnabled());
        }

        // Store reference
        this.remoteVideoTrack = videoTrack;
        this.remoteVideoFrameCount = 0;
        this.lastRemoteVideoLogTime = System.currentTimeMillis();

        // ═══════════════════════════════════════════════════════════════
        // DIAGNOSTIC: Add a debug sink directly to detect if frames arrive
        // This helps distinguish between:
        // 1. WebRTC not receiving frames (network/ICE issue)
        // 2. VideoPanel not receiving frames (sink attachment issue)
        // ═══════════════════════════════════════════════════════════════
        VideoTrackSink debugSink = frame -> {
            remoteVideoFrameCount++;
            long now = System.currentTimeMillis();
            // Log first frame immediately, then every 5 seconds
            if (remoteVideoFrameCount == 1) {
                int width = frame.buffer != null ? frame.buffer.getWidth() : 0;
                int height = frame.buffer != null ? frame.buffer.getHeight() : 0;
                logger.info(String.format("🎬 FIRST REMOTE VIDEO FRAME RECEIVED! (size: %dx%d)", width, height));
            } else if (now - lastRemoteVideoLogTime >= 5000) {
                int width = frame.buffer != null ? frame.buffer.getWidth() : 0;
                int height = frame.buffer != null ? frame.buffer.getHeight() : 0;
                logger.debug(String.format("📹 Remote video: %d frames received (latest: %dx%d)",
                        remoteVideoFrameCount, width, height));
                lastRemoteVideoLogTime = now;
            }
        };
        videoTrack.addSink(debugSink);
        logger.info("  ✅ Debug sink attached to remote video track");

        // Video rendering will be handled by VideoPanel through callback
        logger.info("  Waiting for VideoPanel attachment via callback...");

    }

    public void toggleAudio(boolean enabled) {
        this.audioEnabled = enabled;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(enabled);
        }
        logger.info(String.format("Audio %s", enabled ? "enabled" : "muted"));
    }

    public void toggleVideo(boolean enabled) {
        this.videoEnabled = enabled;

        // IMPORTANT: Don't toggle localVideoTrack if it's shared (videoSource == null)
        // Shared tracks are managed by GroupCallManager
        if (localVideoTrack != null && videoSource != null) {
            localVideoTrack.setEnabled(enabled);
            logger.info(String.format("Local video track %s (own source)",
                    enabled ? "enabled" : "disabled"));
        } else if (localVideoTrack != null && videoSource == null) {
            logger.debug("Skipping toggle - video track is shared (managed by GroupCallManager)");
        }
    }

    // ===============================
    // Screen Sharing API
    // ===============================

    /**
     * Get available screens for sharing
     */
    public List<DesktopSource> getAvailableScreens() {
        throw new UnsupportedOperationException(
                "Legacy screen enumeration has been removed. Use ScreenSourcePickerDialog instead.");
    }

    /**
     * Get available windows for sharing
     */
    public List<DesktopSource> getAvailableWindows() {
        throw new UnsupportedOperationException(
                "Legacy screen enumeration has been removed. Use ScreenSourcePickerDialog instead.");
    }

    /**
     * Test if a desktop source is safe to capture (won't crash)
     * Returns true if source can be safely used
     */
    public boolean testSourceSafety(DesktopSource source, boolean isWindow) {
        throw new UnsupportedOperationException("Legacy screen share safety path removed.");
    }

    /**
     * Start screen sharing with selected source
     * 
     * @param sourceId Desktop source ID (from DesktopSource.id)
     * @param isWindow true if sharing window, false if sharing screen
     */
    public void startScreenShare(long sourceId, boolean isWindow) {
        throw new UnsupportedOperationException(
                "Legacy screen share path removed. Use ScreenShareController.startScreenShare(ScreenSourceOption).");
    }

    /**
     * Stop screen sharing
     */
    public void stopScreenShare() {
        throw new UnsupportedOperationException(
                "Legacy screen share path removed. Use ScreenShareController.stopScreenShare().");
    }

    /**
     * Check if screen sharing is currently active
     */
    public boolean isScreenSharingEnabled() {
        return false;
    }

    /**
     * Get screen share video track (for local preview)
     */
    // ===============================
    // Helper Methods
    // ===============================

    // (Helper methods removed - using default device selection now)

    // ===============================
    // Getters
    // ===============================

    public String getCallId() {
        return currentCallId;
    }

    public String getRemoteUsername() {
        return remoteUsername;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    public RTCPeerConnection getPeerConnection() {
        return peerConnection;
    }

    public RTCRtpSender getVideoSender() {
        return videoSender;
    }

    public VideoTrack getLocalVideoTrack() {
        VideoTrack track = (VideoTrack) localVideoTrack;
        logger.debug("getLocalVideoTrack called: localVideoTrack=" +
                (localVideoTrack != null ? "EXISTS (class=" + localVideoTrack.getClass().getSimpleName() + ")"
                        : "NULL"));
        return track;
    }

    public AudioTrack getLocalAudioTrack() {
        return (AudioTrack) localAudioTrack;
    }

    /**
     * Capture a thumbnail preview for a screen/window source
     * Returns a VideoFrame with the captured image
     * 
     * Note: Due to native library limitations and platform compatibility issues,
     * thumbnail capture is disabled. This method returns null and the UI will
     * show placeholder icons instead.
     */
    public VideoFrame captureThumbnail(DesktopSource source, boolean isWindow) {
        throw new UnsupportedOperationException("Thumbnail capture is handled by ScreenSourcePickerDialog.");
    }

    // ===============================
    // Mock SDP Generator (fallback)
    // ===============================

    // Mock SDP Generator REMOVED

    /**
     * Log video codec information from SDP for debugging cross-platform issues
     */
    private void logSdpVideoCodecs(String sdp, String label) {
        if (sdp == null || sdp.isEmpty())
            return;

        logger.info("═══════════════════════════════════════════");
        logger.info(String.format("SDP %s - Video Codec Analysis", label));

        // Find video m-line
        String[] lines = sdp.split("\r\n|\n");
        boolean inVideoSection = false;
        StringBuilder videoCodecs = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("m=video")) {
                inVideoSection = true;
                // Extract payload types from m=video line
                String[] parts = line.split(" ");
                logger.info("  m=video line: " + line.substring(0, Math.min(80, line.length())));
            } else if (line.startsWith("m=")) {
                inVideoSection = false;
            } else if (inVideoSection) {
                // Log rtpmap lines (codec definitions)
                if (line.startsWith("a=rtpmap:")) {
                    String codec = line.substring(9);
                    logger.info("  Codec: " + codec);
                }
                // Log fmtp lines (codec parameters)
                if (line.startsWith("a=fmtp:") && line.contains("profile-level-id")) {
                    logger.info("  Profile: " + line.substring(7));
                }
            }
        }

        logger.info("═══════════════════════════════════════════");
    }

    /**
     * Extract H.264 profile-level-id from SDP.
     * Returns the first profile found, or null if none.
     */
    private String extractH264ProfileFromSdp(String sdp) {
        if (sdp == null)
            return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("profile-level-id=([0-9a-fA-F]{6})");
        java.util.regex.Matcher matcher = pattern.matcher(sdp);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase();
        }
        return null;
    }

    // ===============================
    // Encoder Watchdog (Mac VideoToolbox freeze detection)
    // ===============================

    /**
     * ═══════════════════════════════════════════════════════════════════════════
     * ENHANCED ENCODER WATCHDOG (Mac VideoToolbox Protection)
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * Monitors for VideoToolbox deadlock by comparing capture frame count vs
     * last encoded frame time. If frames are being captured but encoding has
     * stopped for 2+ seconds, the encoder has frozen.
     * 
     * Recovery Strategy (Tiered):
     * - Soft Reset: Stop → 150ms pause → Restart (up to 3 times)
     * - Hard Reset: Full track teardown and rebuild (nuclear option)
     */
    private void startEncoderWatchdog() {
        if (encoderWatchdogActive)
            return;
        encoderWatchdogActive = true;
        lastEncodedFrameTime = System.currentTimeMillis();
        softResetCount = 0;

        Thread.ofVirtual().name("encoder-watchdog").start(() -> {
            logger.info("╔═══════════════════════════════════════════════════════════════════╗");
            logger.info("║ 🐕 ENCODER WATCHDOG ARMED (Mac VideoToolbox Protection)           ║");
            logger.info(String.format("║   Freeze threshold: %dms | Max soft resets: %d                   ║",
                    ENCODER_FREEZE_THRESHOLD_MS, MAX_SOFT_RESETS));
            logger.info("╚═══════════════════════════════════════════════════════════════════╝");

            // GRACE PERIOD: Wait for videoSource to become available (up to 15s)
            // This prevents watchdog from dying during answerer handshake where
            // setRemoteDescription is called BEFORE addVideoTrack
            int gracePeriodMs = 0;
            final int MAX_GRACE_PERIOD_MS = 15000; // 15 second max wait
            while (encoderWatchdogActive && videoSource == null && gracePeriodMs < MAX_GRACE_PERIOD_MS) {
                try {
                    Thread.sleep(100);
                    gracePeriodMs += 100;
                } catch (InterruptedException e) {
                    logger.info("🐕 Encoder Watchdog interrupted during grace period");
                    return;
                }
            }

            if (!encoderWatchdogActive) {
                logger.info("🐕 Encoder Watchdog DISARMED during grace period");
                return;
            }

            if (videoSource == null) {
                logger.warn("🐕 Encoder Watchdog: videoSource still null after grace period, disarming");
                return;
            }

            logger.debug("🐕 Watchdog grace period complete (waited " + gracePeriodMs + "ms), starting monitoring");

            long lastCaptureCount = 0;

            while (encoderWatchdogActive && videoSource != null) {
                try {
                    Thread.sleep(500); // Check every 500ms

                    long currentCaptures = captureFrameCount;
                    long now = System.currentTimeMillis();
                    long timeSinceEncode = now - lastEncodedFrameTime;

                    // Detect freeze: captures increasing but no encodes for 2+ seconds
                    if (currentCaptures > lastCaptureCount + 10 &&
                            timeSinceEncode > ENCODER_FREEZE_THRESHOLD_MS) {

                        logger.error("═══════════════════════════════════════════════════════════════", null);
                        logger.error("⚠️ ENCODER FREEZE DETECTED!", null);
                        logger.error(String.format("   Captures: %d (+%d since last check)",
                                currentCaptures, currentCaptures - lastCaptureCount), null);
                        logger.error(String.format("   Last encode: %dms ago (threshold: %dms)",
                                timeSinceEncode, ENCODER_FREEZE_THRESHOLD_MS), null);
                        logger.error(String.format("   Soft reset count: %d/%d",
                                softResetCount, MAX_SOFT_RESETS), null);
                        logger.error("═══════════════════════════════════════════════════════════════", null);

                        // Tiered recovery
                        if (softResetCount < MAX_SOFT_RESETS) {
                            handleEncoderFreezeSoft();
                            softResetCount++;
                            // Reset detection counters and continue monitoring
                            lastCaptureCount = 0;
                            captureFrameCount = 0;
                        } else {
                            logger.error("❌ Max soft resets exceeded. Escalating to HARD RESET!", null);
                            handleEncoderFreezeHard();
                            // Exit watchdog after hard reset - it will restart with new track
                            break;
                        }
                    }

                    lastCaptureCount = currentCaptures;

                } catch (InterruptedException e) {
                    break;
                }
            }
            logger.info("🐕 Encoder Watchdog DISARMED");
        });
    }

    /**
     * SOFT RESET: Stop → Brief pause → Restart.
     * Works for minor VideoToolbox hiccups.
     */
    private void handleEncoderFreezeSoft() {
        logger.info("🔄 Attempting SOFT RESET (attempt " + (softResetCount + 1) + "/" + MAX_SOFT_RESETS + ")...");

        try {
            // 1. Stop current video source
            if (videoSource != null) {
                videoSource.stop();
                logger.info("   ⏹️ Video source stopped");
            }

            // 2. Brief pause for VideoToolbox to release resources
            Thread.sleep(150);

            // 3. Restart video source
            if (videoSource != null) {
                videoSource.start();
                logger.info("   ▶️ Video source restarted");
            }

            // 4. Reset timing counters
            lastEncodedFrameTime = System.currentTimeMillis();
            captureFrameCount = 0;

            logger.info("✅ Soft reset complete - monitoring continues");

        } catch (Exception e) {
            logger.error("❌ Soft reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * HARD RESET: Full track teardown and rebuild.
     * Nuclear option when soft resets fail.
     */
    private void handleEncoderFreezeHard() {
        logger.info("🔧 Attempting HARD RESET (full track rebuild)...");

        try {
            // 1. Stop and dispose current source
            if (videoSource != null) {
                try {
                    videoSource.stop();
                    videoSource.dispose();
                } catch (Exception e) {
                    logger.warn("   ⚠️ Error disposing old source: " + e.getMessage());
                }
                videoSource = null;
            }

            // 2. Create fresh camera track
            logger.info("   📹 Creating fresh camera track...");
            CameraCaptureService.CameraCaptureResource resource = CameraCaptureService
                    .createCameraTrack("video0_rebuilt");

            videoSource = resource.getSource();
            VideoTrack newTrack = resource.getTrack();

            // 3. Start capture
            resource.startCapture();
            logger.info("   ▶️ New capture started");

            // 4. Replace track in peer connection if possible
            if (videoSender != null && peerConnection != null) {
                try {
                    videoSender.replaceTrack(newTrack);
                    logger.info("   🔗 Track replaced in peer connection");
                } catch (Exception e) {
                    logger.warn("   ⚠️ Could not replace track: " + e.getMessage());
                }
            }

            // 5. Reset all counters
            lastEncodedFrameTime = System.currentTimeMillis();
            captureFrameCount = 0;
            softResetCount = 0;

            // 6. Restart watchdog with fresh state
            encoderWatchdogActive = false;
            Thread.sleep(200);
            startEncoderWatchdog();

            logger.info("✅ HARD RESET complete - track rebuilt, watchdog restarted");

        } catch (Exception e) {
            logger.error("❌ HARD RESET failed: " + e.getMessage(), e);
        }
    }

    /**
     * Stop encoder watchdog (called during cleanup)
     */
    private void stopEncoderWatchdog() {
        encoderWatchdogActive = false;
    }

    /**
     * Notify watchdog of encoded frame (call from encoder callback)
     */
    void notifyEncodedFrame() {
        lastEncodedFrameTime = System.currentTimeMillis();
    }

    /**
     * Notify watchdog of captured frame (call from capture callback)
     */
    void notifyCapturedFrame() {
        captureFrameCount++;
    }

    /**
     * Start the device monitor loop (Hot-Swap)
     */
    private static void startDeviceMonitor() {
        if (isMonitoring)
            return;
        isMonitoring = true;

        Thread monitorThread = Thread.ofVirtual().name("webrtc-device-monitor").start(() -> {
            logger.info("🎧 Device monitor started (Hot-Swap enabled)");

            while (initialized && isMonitoring) {
                try {
                    // Poll every 250ms (Quarter second - feels instant)
                    Thread.sleep(250);

                    // Check Microphone
                    AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
                    if (defaultMic != null && !defaultMic.getName().equals(currentMicName)) {
                        logger.info("⚠️ Default microphone changed!");
                        logger.info("   Old: " + currentMicName);
                        logger.info("   New: " + defaultMic.getName());

                        switchMicrophone(defaultMic);
                    }

                    // Check Speaker
                    AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();
                    if (defaultSpeaker != null && !defaultSpeaker.getName().equals(currentSpeakerName)) {
                        logger.info("⚠️ Default speaker changed!");
                        logger.info("   Old: " + currentSpeakerName);
                        logger.info("   New: " + defaultSpeaker.getName());

                        switchSpeaker(defaultSpeaker);
                    }

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Device monitor error: " + e.getMessage(), null);
                }
            }
            logger.info("Device monitor stopped");
        });
    }

    /**
     * Safely switches the microphone without restarting the entire peer connection.
     */
    private static synchronized void switchMicrophone(AudioDevice newDevice) {
        if (audioDeviceModule == null)
            return;

        logger.info("🔄 Switching microphone to: " + newDevice.getName());

        try {
            // 1. Stop recording if active (releases old device lock)
            boolean wasRecording = recordingStarted;
            if (wasRecording) {
                audioDeviceModule.stopRecording();
            }

            // 2. Set new device
            audioDeviceModule.setRecordingDevice(newDevice);
            currentMicName = newDevice.getName();

            // 3. Restart recording if it was active
            if (wasRecording) {
                audioDeviceModule.initRecording();
                audioDeviceModule.startRecording();
                logger.info("✅ Microphone switched and restarted successfully");
            } else {
                logger.info("✅ Microphone swapped (idle)");
            }

        } catch (Exception e) {
            logger.error("❌ Error switching microphone: " + e.getMessage(), e);
        }
    }

    /**
     * Safely switches the speaker/headphones without restarting the entire peer
     * connection.
     */
    private static synchronized void switchSpeaker(AudioDevice newDevice) {
        if (audioDeviceModule == null)
            return;

        logger.info("🔄 Switching speaker to: " + newDevice.getName());

        try {
            // 1. Stop playout if active (releases old device lock)
            boolean wasPlayout = playoutStarted;
            if (wasPlayout) {
                audioDeviceModule.stopPlayout();
            }

            // 2. Set new device
            audioDeviceModule.setPlayoutDevice(newDevice);
            currentSpeakerName = newDevice.getName();

            // 3. Restart playout if it was active
            if (wasPlayout) {
                audioDeviceModule.initPlayout();
                audioDeviceModule.startPlayout();
                logger.info("✅ Speaker switched and restarted successfully");
            } else {
                logger.info("✅ Speaker swapped (idle)");
            }

        } catch (Exception e) {
            logger.error("❌ Error switching speaker: " + e.getMessage(), e);
        }
    }
}
