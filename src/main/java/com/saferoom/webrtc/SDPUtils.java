package com.saferoom.webrtc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for optimizing SDP generation with platform-aware codec preference.
 * 
 * Key principle: NEVER strip VP8/VP9/H264 - they are all needed for
 * cross-platform
 * compatibility. Instead, REORDER codecs based on platform preference.
 * 
 * - Windows: Prefers H.264 (hardware encoding via QuickSync/NVENC/VCE)
 * - Linux/Mac: Prefers VP8 (better software/VAAPI support)
 */
public class SDPUtils {

    // Platform detection (same as WebRTCClient)
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    // Codec payload type patterns
    private static final Pattern RTPMAP_PATTERN = Pattern.compile("a=rtpmap:(\\d+)\\s+(\\S+)");
    private static final Pattern MLINE_VIDEO_PATTERN = Pattern.compile("m=video\\s+\\d+\\s+\\S+\\s+(.+)");

    /**
     * Optimize SDP by removing unused audio codecs (Opus is universal).
     * DOES NOT remove video codecs VP8/VP9/H264 - they're essential for
     * cross-platform.
     */
    public static String mungeSDP(String sdp) {
        if (sdp == null || sdp.isEmpty())
            return sdp;

        StringBuilder sb = new StringBuilder();
        String[] lines = sdp.split("\r\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // 1. Remove specific unwanted lines entirely
            if (shouldRemoveLine(trimmed)) {
                continue;
            }

            // 2. Filter attribute lines (a=)
            if (trimmed.startsWith("a=")) {
                if (shouldRemoveAttribute(trimmed)) {
                    continue;
                }
            }

            // 3. Keep line
            sb.append(line).append("\r\n");
        }

        // 4. Reorder codec preference based on platform
        return reorderCodecPreference(sb.toString());
    }

    private static boolean shouldRemoveLine(String line) {
        // Remove known useless or heavy lines
        return false; // Aggressive filtering handled in attributes
    }

    private static boolean shouldRemoveAttribute(String line) {
        // ONLY remove uncommon audio codecs - Opus is universally supported
        // DO NOT remove VP8/VP9/H264 - they're essential for cross-platform video

        if (line.contains("AV1") || // AV1 has limited hardware support
                line.contains("PCMU") || line.contains("PCMA") || // G.711 variants
                line.contains("ISAC") || line.contains("G722")) { // Less common audio
            return true;
        }

        // Keep everything else (including VP8, VP9, H264)
        return false;
    }

    /**
     * Force 'sendrecv' direction on a specific media section.
     * Often needed when creating an Offer before the local track is fully live.
     */
    public static String enforceSendRecv(String sdp, String mediaType) {
        if (sdp == null || !sdp.contains("m=" + mediaType)) {
            return sdp;
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = sdp.split("\r\n");
        boolean inMediaSection = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("m=")) {
                inMediaSection = trimmed.startsWith("m=" + mediaType);
            }

            if (inMediaSection) {
                // If we see an existing direction, replace it
                if (trimmed.equals("a=sendonly") || trimmed.equals("a=recvonly") || trimmed.equals("a=inactive")) {
                    sb.append("a=sendrecv").append("\r\n");
                    continue;
                }
            }

            sb.append(line).append("\r\n");
        }

        return sb.toString();
    }

    /**
     * Reorder video codec preference based on platform.
     * 
     * This ensures the PREFERRED codec is listed first in the m=video line,
     * but ALL codecs remain available for negotiation.
     * 
     * Windows: H264 first (hardware encoding support)
     * Linux/Mac: VP8 first (better software/VAAPI support)
     */
    public static String reorderCodecPreference(String sdp) {
        if (sdp == null || !sdp.contains("m=video")) {
            return sdp;
        }

        // Determine preferred codec based on platform
        String preferredCodec = IS_WINDOWS ? "H264" : "VP8";

        System.out.printf("[SDPUtils] Platform: %s → Preferred codec: %s%n",
                IS_WINDOWS ? "Windows" : (IS_LINUX ? "Linux" : (IS_MAC ? "macOS" : "Unknown")),
                preferredCodec);

        // Parse payload types for each codec
        List<String> h264Payloads = new ArrayList<>();
        List<String> vp8Payloads = new ArrayList<>();
        List<String> vp9Payloads = new ArrayList<>();
        List<String> otherPayloads = new ArrayList<>();

        String[] lines = sdp.split("\r\n");

        // First pass: collect payload types for each codec
        for (String line : lines) {
            Matcher m = RTPMAP_PATTERN.matcher(line);
            if (m.find()) {
                String payloadType = m.group(1);
                String codecName = m.group(2).toUpperCase();

                if (codecName.contains("H264")) {
                    h264Payloads.add(payloadType);
                } else if (codecName.contains("VP8")) {
                    vp8Payloads.add(payloadType);
                } else if (codecName.contains("VP9")) {
                    vp9Payloads.add(payloadType);
                }
            }
        }

        // Build ordered payload list based on platform preference
        List<String> orderedPayloads = new ArrayList<>();

        if (IS_WINDOWS) {
            // Windows: H264 → VP8 → VP9
            orderedPayloads.addAll(h264Payloads);
            orderedPayloads.addAll(vp8Payloads);
            orderedPayloads.addAll(vp9Payloads);
        } else {
            // Linux/Mac: VP8 → H264 → VP9
            orderedPayloads.addAll(vp8Payloads);
            orderedPayloads.addAll(h264Payloads);
            orderedPayloads.addAll(vp9Payloads);
        }

        // Second pass: reorder payloads in m=video line
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("m=video")) {
                Matcher m = MLINE_VIDEO_PATTERN.matcher(line);
                if (m.find()) {
                    String originalPayloads = m.group(1);
                    String[] allPayloads = originalPayloads.split("\\s+");

                    // Collect payloads not in our video codec list (RTX, RED, ULPFEC, etc.)
                    for (String pt : allPayloads) {
                        if (!orderedPayloads.contains(pt) && !otherPayloads.contains(pt)) {
                            otherPayloads.add(pt);
                        }
                    }

                    // Build new m= line with reordered payloads
                    StringBuilder newPayloads = new StringBuilder();
                    for (String pt : orderedPayloads) {
                        newPayloads.append(pt).append(" ");
                    }
                    for (String pt : otherPayloads) {
                        newPayloads.append(pt).append(" ");
                    }

                    String newMLine = line.substring(0, line.indexOf(originalPayloads)) +
                            newPayloads.toString().trim();
                    sb.append(newMLine).append("\r\n");

                    System.out.printf("[SDPUtils] Reordered video payloads: %s%n", newPayloads.toString().trim());
                    continue;
                }
            }
            sb.append(line).append("\r\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAC VIDEOTOOLBOX FIX: SDP Profile Enforcement (The Gatekeeper)
    // ═══════════════════════════════════════════════════════════════════════════

    // Pattern to match profile-level-id in fmtp lines
    private static final Pattern PROFILE_PATTERN = Pattern.compile("(profile-level-id=)([0-9a-fA-F]{6})");

    // Pattern to match packetization-mode in fmtp lines
    private static final Pattern PACKETIZATION_MODE_PATTERN = Pattern.compile("(packetization-mode=)(\\d)");

    /**
     * ═══════════════════════════════════════════════════════════════════
     * SDP PROFILE MUNGING: The Gatekeeper (STRICT MODE)
     * ═══════════════════════════════════════════════════════════════════
     *
     * Forces all H.264 fmtp lines to use STRICTLY Constrained Baseline (42e01f).
     * Also enforces packetization-mode=1 for macOS VideoToolbox compatibility.
     * This prevents the native VideoToolbox encoder from receiving a profile
     * it cannot handle during mid-session renegotiation.
     *
     * CRITICAL: 42001f (Baseline) also causes freezes on Mac!
     * Only 42e01f (Constrained Baseline) is safe.
     *
     * High Profile (640c1f) -> Constrained Baseline (42e01f)
     * Main Profile (4d001f) -> Constrained Baseline (42e01f)
     * Plain Baseline (42001f) -> Constrained Baseline (42e01f)
     * packetization-mode=0 -> packetization-mode=1 (Mac compatibility)
     *
     * This is the "nuclear option" that guarantees stability at the cost of
     * encoding efficiency. Constrained Baseline is universally supported.
     *
     * @param sdp The SDP to process
     * @return SDP with all H.264 profiles normalized to Constrained Baseline
     */
    public static String enforceBaselineH264Profile(String sdp) {
        if (sdp == null)
            return null;

        // The ONLY safe profile for Mac VideoToolbox
        final String SAFE_PROFILE = "42e01f";
        final String SAFE_PACKETIZATION_MODE = "1";

        StringBuilder result = new StringBuilder();
        String[] lines = sdp.split("\r\n");
        boolean profileModified = false;
        boolean packetModeModified = false;

        for (String line : lines) {
            String processedLine = line;

            // Check and fix profile-level-id
            if (processedLine.contains("profile-level-id=")) {
                Matcher m = PROFILE_PATTERN.matcher(processedLine);
                if (m.find()) {
                    String originalProfile = m.group(2).toLowerCase();

                    // STRICT: Only 42e01f is allowed (not just any 42xx)
                    if (!originalProfile.equals(SAFE_PROFILE)) {
                        processedLine = m.replaceFirst("$1" + SAFE_PROFILE);
                        System.out.printf("[SDPUtils] PROFILE MUNGED: %s -> %s (%s -> Constrained Baseline)%n",
                                originalProfile, SAFE_PROFILE, decodeH264Profile(originalProfile));
                        profileModified = true;
                    }
                }
            }

            // Check and fix packetization-mode (Mac requires mode=1)
            if (processedLine.contains("packetization-mode=")) {
                Matcher pm = PACKETIZATION_MODE_PATTERN.matcher(processedLine);
                if (pm.find()) {
                    String originalMode = pm.group(2);
                    if (!originalMode.equals(SAFE_PACKETIZATION_MODE)) {
                        processedLine = pm.replaceFirst("$1" + SAFE_PACKETIZATION_MODE);
                        System.out.printf(
                                "[SDPUtils] PACKETIZATION MODE MUNGED: %s -> %s (Mac VideoToolbox compatibility)%n",
                                originalMode, SAFE_PACKETIZATION_MODE);
                        packetModeModified = true;
                    }
                }
            }

            result.append(processedLine).append("\r\n");
        }

        if (profileModified || packetModeModified) {
            System.out.println("[SDPUtils] MUNGING STATUS: ENFORCING 42E01F FOR HARDWARE SAFETY"
                    + (packetModeModified ? " + PACKETIZATION-MODE=1" : ""));
        } else {
            System.out.println("[SDPUtils] SDP already uses safe configuration (42e01f, mode=1)");
        }

        return result.toString();
    }

    /**
     * Analyze and log all video codec information in an SDP.
     * Strategic logging for debugging cross-platform issues.
     * 
     * @param sdp   The SDP to analyze
     * @param label A label for the log output (e.g., "REMOTE OFFER")
     */
    public static void logVideoCodecAnalysis(String sdp, String label) {
        if (sdp == null)
            return;

        System.out.println("╔═══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║ VIDEO CODEC ANALYSIS: %-44s ║%n", label);
        System.out.println("╠═══════════════════════════════════════════════════════════════════╣");

        // Extract all rtpmap entries for video
        Pattern rtpmapPattern = Pattern.compile("a=rtpmap:(\\d+) ([^/]+)");
        Pattern fmtpPattern = Pattern.compile("a=fmtp:(\\d+) (.+)");

        Map<String, String> codecNames = new HashMap<>();
        Map<String, String> codecParams = new HashMap<>();

        for (String line : sdp.split("\r\n")) {
            Matcher rm = rtpmapPattern.matcher(line);
            if (rm.find()) {
                codecNames.put(rm.group(1), rm.group(2));
            }

            Matcher fm = fmtpPattern.matcher(line);
            if (fm.find()) {
                codecParams.put(fm.group(1), fm.group(2));
            }
        }

        // Print codec analysis
        int codecCount = 0;
        for (var entry : codecNames.entrySet()) {
            String pt = entry.getKey();
            String name = entry.getValue();
            String params = codecParams.getOrDefault(pt, "");

            if (name.toUpperCase().contains("H264") ||
                    name.toUpperCase().contains("VP8") ||
                    name.toUpperCase().contains("VP9")) {

                codecCount++;
                String profile = "";
                if (params.contains("profile-level-id=")) {
                    int idx = params.indexOf("profile-level-id=") + 17;
                    profile = params.substring(idx, Math.min(idx + 6, params.length()));

                    // Decode profile meaning
                    String meaning = decodeH264Profile(profile);
                    System.out.printf("║  PT %-3s: %-8s Profile: %-6s %-20s ║%n",
                            pt, name, profile, "(" + meaning + ")");
                } else {
                    System.out.printf("║  PT %-3s: %-8s (no profile)                           ║%n", pt, name);
                }
            }
        }

        if (codecCount == 0) {
            System.out.println("║  No video codecs found                                            ║");
        }

        System.out.println("╚═══════════════════════════════════════════════════════════════════╝");
    }

    /**
     * Decode H.264 profile byte to human-readable name.
     */
    private static String decodeH264Profile(String profileHex) {
        if (profileHex == null || profileHex.length() < 2)
            return "unknown";

        String profileByte = profileHex.substring(0, 2).toLowerCase();
        return switch (profileByte) {
            case "42" -> "Baseline ✅";
            case "4d" -> "Main";
            case "58" -> "Extended";
            case "64" -> "High ⚠️";
            case "6e" -> "High 10";
            case "7a" -> "High 4:2:2";
            case "f4" -> "High 4:4:4";
            default -> "unknown";
        };
    }

}
