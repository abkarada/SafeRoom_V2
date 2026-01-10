package com.saferoom.webrtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpCapabilities;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-specific WebRTC tuning.
 * 
 * - macOS: Prioritize H264 for VideoToolbox hardware acceleration
 * - Windows: Prioritize H264 for Intel QuickSync / NVIDIA NVENC / AMD VCE
 * - Linux: Use VP8/VP9 (better software encoding support)
 */
final class WebRTCPlatformConfig {

    private static final WebRTCPlatformConfig EMPTY = new WebRTCPlatformConfig(false, false, List.of(), "Unknown");

    private final boolean preferH264;
    private final boolean isWindows;
    private final List<RTCRtpCodecCapability> videoCodecPreferences;
    private final String platformName;

    private WebRTCPlatformConfig(boolean preferH264, boolean isWindows,
            List<RTCRtpCodecCapability> videoCodecPreferences,
            String platformName) {
        this.preferH264 = preferH264;
        this.isWindows = isWindows;
        this.videoCodecPreferences = videoCodecPreferences;
        this.platformName = platformName;
    }

    static WebRTCPlatformConfig detect(PeerConnectionFactory factory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac");
        boolean windows = os.contains("win");
        boolean linux = os.contains("linux");

        String platformName = mac ? "macOS" : windows ? "Windows" : linux ? "Linux" : "Unknown";
        System.out.printf("[WebRTC] Platform detected: %s%n", platformName);

        if (factory == null) {
            System.out.println("[WebRTC] Factory null - using default codec order");
            return new WebRTCPlatformConfig(false, windows, List.of(), platformName);
        }

        RTCRtpCapabilities senderCaps = factory.getRtpSenderCapabilities(MediaType.VIDEO);

        // ═══════════════════════════════════════════════════════════════
        // PLATFORM-SPECIFIC CODEC STRATEGY:
        // - Windows/macOS: H.264 only (hardware accelerated via QuickSync/VideoToolbox)
        // - Linux: VP8/VP9 preferred (better software codec support), H.264 fallback
        // ═══════════════════════════════════════════════════════════════

        List<RTCRtpCodecCapability> filtered;
        boolean preferH264;

        if (linux) {
            // Linux: VP8/VP9 have better software support than H.264
            filtered = filterCodecsForLinux(senderCaps);
            preferH264 = false;
            System.out.printf("[WebRTC] %s → Using VP8/VP9 preferred codec list%n", platformName);
        } else {
            // Windows/macOS: H.264 has hardware acceleration
            filtered = filterCodecsStrict(senderCaps);
            preferH264 = true;
            System.out.printf("[WebRTC] %s → Enforcing strict codec list (H.264)%n", platformName);
        }

        if (!filtered.isEmpty()) {
            System.out.println("[WebRTC] Allowed codecs:");
            for (RTCRtpCodecCapability codec : filtered) {
                System.out.printf("  - %s%n", codec.getName());
            }
        } else {
            System.out.printf("[WebRTC] %s detected but NO usable video codecs found!%n", platformName);
        }

        return new WebRTCPlatformConfig(preferH264, windows, filtered, platformName);
    }

    static WebRTCPlatformConfig empty() {
        return EMPTY;
    }

    private static void printAvailableCodecs(List<RTCRtpCodecCapability> codecs) {
        if (codecs.isEmpty())
            return;
        System.out.println("[WebRTC] Available video codecs (priority order):");
        for (int i = 0; i < Math.min(5, codecs.size()); i++) {
            RTCRtpCodecCapability codec = codecs.get(i);
            System.out.printf("  [%d] %s%n", i + 1, codec.getName());
        }
    }

    void applyVideoCodecPreferences(RTCPeerConnection peerConnection) {
        if (peerConnection == null || videoCodecPreferences.isEmpty()) {
            System.out.println("[WebRTC] Skipping codec preferences (empty or no connection)");
            return;
        }

        RTCRtpTransceiver[] transceivers = peerConnection.getTransceivers();
        if (transceivers == null || transceivers.length == 0) {
            System.out.println("[WebRTC] No transceivers found for codec configuration");
            return;
        }

        int applied = 0;
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver == null)
                continue;

            RTCRtpSender sender = transceiver.getSender();
            if (sender == null)
                continue;

            // Start with Sender track
            MediaStreamTrack track = sender.getTrack();

            // Fallback: Check Receiver track if Sender track is null (RecvOnly mode)
            if (track == null) {
                if (transceiver.getReceiver() != null) {
                    track = transceiver.getReceiver().getTrack();
                }
            }

            if (track == null || !"video".equalsIgnoreCase(track.getKind()))
                continue;

            try {
                transceiver.setCodecPreferences(videoCodecPreferences);
                applied++;
                System.out.printf("[WebRTC] Codec preferences applied to transceiver (track: %s)%n",
                        track.getId());
            } catch (Exception ex) {
                System.err.printf("[WebRTC] Failed to set codec preferences: %s%n", ex.getMessage());
            }
        }

        if (applied > 0) {
            System.out.printf("[WebRTC] Codec preferences applied to %d transceiver(s) on %s%n",
                    applied, platformName);
        } else {
            System.out.println(
                    "[WebRTC] Warning: Codec preferences NOT applied (no matching transceivers could be configured)");
        }

    }

    /**
     * Filter codecs for Windows/macOS - H.264 preferred, VP8/VP9 fallback.
     * This allows hardware acceleration on Windows/Mac while still enabling
     * cross-platform compatibility with Linux (which may not decode H.264 well).
     * 
     * CRITICAL: We MUST include VP8/VP9 in offers so that Linux answerers
     * can choose a codec they can decode. Otherwise, Linux sees black video.
     * 
     * CROSS-PLATFORM FIX: Profile filtering now happens at SDP level in
     * WebRTCClient.
     * setRemoteDescription() validates incoming profile-level-id and warns about
     * High Profile (640c1f) which causes Mac VideoToolbox deadlocks.
     * 
     * NOTE: webrtc-java 0.14.0 doesn't expose sdpFmtpLine on RTCRtpCodecCapability,
     * so we cannot filter profiles at codec preference level. The encoder watchdog
     * in WebRTCClient provides backup protection against freezes.
     */
    private static List<RTCRtpCodecCapability> filterCodecsStrict(RTCRtpCapabilities capabilities) {
        if (capabilities == null || capabilities.getCodecs() == null) {
            return List.of();
        }

        // Log that we're using cross-platform compatible settings
        System.out.println("[WebRTC] 📊 Using Constrained Baseline profile preference for Mac compatibility");

        // Collect H264 codecs (profile filtering happens at SDP level in WebRTCClient)
        List<RTCRtpCodecCapability> h264Codecs = capabilities.getCodecs().stream()
                .filter(Objects::nonNull)
                .filter(codec -> {
                    String name = codec.getName();
                    return name != null && name.toUpperCase(Locale.ROOT).contains("H264");
                })
                .toList();

        List<RTCRtpCodecCapability> vpCodecs = capabilities.getCodecs().stream()
                .filter(Objects::nonNull)
                .filter(codec -> {
                    String name = codec.getName();
                    if (name == null)
                        return false;
                    String upper = name.toUpperCase(Locale.ROOT);
                    return upper.contains("VP8") || upper.contains("VP9");
                })
                .toList();

        // Combine: H264 first (hardware accelerated), then VP8/VP9 as fallback
        java.util.List<RTCRtpCodecCapability> combined = new java.util.ArrayList<>(h264Codecs);
        combined.addAll(vpCodecs);

        System.out.printf("[WebRTC] Codec list: %d H264 + %d VP8/VP9 = %d total%n",
                h264Codecs.size(), vpCodecs.size(), combined.size());

        return combined;
    }

    /**
     * Filter codecs for Linux - VP8/VP9 preferred, H.264 fallback.
     * Linux generally has better software support for VP8/VP9 than H.264.
     */
    private static List<RTCRtpCodecCapability> filterCodecsForLinux(RTCRtpCapabilities capabilities) {
        if (capabilities == null || capabilities.getCodecs() == null) {
            return List.of();
        }

        // Separate VP8/VP9 and H264 codecs
        List<RTCRtpCodecCapability> vpCodecs = capabilities.getCodecs().stream()
                .filter(Objects::nonNull)
                .filter(codec -> {
                    String name = codec.getName();
                    if (name == null)
                        return false;
                    String upper = name.toUpperCase(Locale.ROOT);
                    return upper.contains("VP8") || upper.contains("VP9");
                })
                .toList();

        List<RTCRtpCodecCapability> h264Codecs = capabilities.getCodecs().stream()
                .filter(Objects::nonNull)
                .filter(codec -> {
                    String name = codec.getName();
                    return name != null && name.toUpperCase(Locale.ROOT).contains("H264");
                })
                .toList();

        // Combine: VP8/VP9 first, then H264 as fallback
        java.util.List<RTCRtpCodecCapability> combined = new java.util.ArrayList<>(vpCodecs);
        combined.addAll(h264Codecs);
        return combined;
    }

    @Override
    public String toString() {
        return String.format("WebRTCPlatformConfig[platform=%s, preferH264=%b, codecs=%d]",
                platformName, preferH264, videoCodecPreferences.size());
    }
}
