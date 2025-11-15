package com.saferoom.webrtc.relay.qos;

import com.saferoom.webrtc.relay.ConnectionMetrics;
import dev.onvoid.webrtc.media.video.VideoTrack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Adaptive Bitrate Controller for SDRT
 * 
 * Dynamically adjusts video encoding bitrate based on:
 * 1. Network bandwidth (measured via ConnectionMetrics)
 * 2. Packet loss rate
 * 3. RTT (latency)
 * 4. CPU load
 * 
 * Similar to Google Congestion Control (GCC) algorithm
 */
public class AdaptiveBitrateController {
    
    private static final Logger logger = Logger.getLogger(AdaptiveBitrateController.class.getName());
    
    // Bitrate limits (bits per second)
    private static final int MIN_BITRATE = 300_000;    // 300 kbps (minimum usable)
    private static final int MAX_BITRATE = 5_000_000;  // 5 Mbps (1080p high quality)
    private static final int DEFAULT_BITRATE = 2_500_000; // 2.5 Mbps (720p)
    
    // Adjustment parameters
    private static final double INCREASE_FACTOR = 1.08;  // +8% when network is good
    private static final double DECREASE_FACTOR = 0.85;  // -15% when congestion detected
    private static final double LOSS_THRESHOLD = 0.02;   // 2% packet loss → reduce bitrate
    private static final double RTT_THRESHOLD_MS = 150;  // 150ms RTT → reduce bitrate
    
    // State
    private int currentBitrate;
    private final ConnectionMetrics metrics;
    private final ScheduledExecutorService executor;
    private boolean isRunning = false;
    
    // Statistics
    private final AtomicLong totalAdjustments = new AtomicLong(0);
    private final AtomicLong increasesCount = new AtomicLong(0);
    private final AtomicLong decreasesCount = new AtomicLong(0);
    
    // Callback for bitrate changes
    private BitrateChangeCallback callback;
    
    @FunctionalInterface
    public interface BitrateChangeCallback {
        void onBitrateChanged(int newBitrate);
    }
    
    /**
     * Constructor
     * 
     * @param metrics ConnectionMetrics to read network state
     */
    public AdaptiveBitrateController(ConnectionMetrics metrics) {
        this.metrics = metrics;
        this.currentBitrate = DEFAULT_BITRATE;
        this.executor = Executors.newScheduledThreadPool(1);
        
        logger.info(String.format("AdaptiveBitrateController initialized: bitrate=%d bps (%.2f Mbps)",
            currentBitrate, currentBitrate / 1_000_000.0));
    }
    
    /**
     * Start adaptive bitrate control
     * Runs every 2 seconds to adjust bitrate
     */
    public void start() {
        if (isRunning) {
            logger.warning("AdaptiveBitrateController already running");
            return;
        }
        
        isRunning = true;
        logger.info("Starting adaptive bitrate control (interval: 2s)");
        
        executor.scheduleAtFixedRate(this::adjustBitrate, 2, 2, TimeUnit.SECONDS);
    }
    
    /**
     * Stop adaptive bitrate control
     */
    public void stop() {
        if (!isRunning) return;
        
        isRunning = false;
        executor.shutdown();
        
        logger.info(String.format("Stopped adaptive bitrate control. Stats: adjustments=%d, increases=%d, decreases=%d",
            totalAdjustments.get(), increasesCount.get(), decreasesCount.get()));
    }
    
    /**
     * Main adaptive bitrate logic (called every 2 seconds)
     */
    private void adjustBitrate() {
        try {
            // Get current network metrics
            double packetLoss = getAveragePacketLoss();
            double rtt = getAverageRTT();
            double bandwidth = getAvailableBandwidth();
            double cpu = getCPULoad();
            
            // Calculate target bitrate based on network conditions
            int targetBitrate = calculateTargetBitrate(packetLoss, rtt, bandwidth, cpu);
            
            // Smooth adjustment (don't change too fast)
            int newBitrate = smoothAdjustment(currentBitrate, targetBitrate);
            
            // Apply bounds
            newBitrate = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, newBitrate));
            
            // Only update if change is significant (>5%)
            if (Math.abs(newBitrate - currentBitrate) > currentBitrate * 0.05) {
                String reason = String.format("loss=%.2f%%, rtt=%.0fms, bw=%.2fMbps, cpu=%.0f%%",
                    packetLoss * 100, rtt, bandwidth, cpu * 100);
                
                applyBitrateChange(newBitrate, reason);
            }
            
        } catch (Exception e) {
            logger.warning("Error in adaptive bitrate adjustment: " + e.getMessage());
        }
    }
    
    /**
     * Calculate target bitrate based on network metrics
     */
    private int calculateTargetBitrate(double packetLoss, double rtt, double bandwidth, double cpu) {
        int target = currentBitrate;
        
        // 1. Packet loss check (most important)
        if (packetLoss > LOSS_THRESHOLD) {
            // High packet loss → decrease aggressively
            target = (int) (target * DECREASE_FACTOR);
            logger.fine(String.format("High packet loss (%.2f%%) → decrease to %d bps",
                packetLoss * 100, target));
        }
        // 2. RTT check (congestion indicator)
        else if (rtt > RTT_THRESHOLD_MS) {
            // High latency → slight decrease
            target = (int) (target * 0.95);
            logger.fine(String.format("High RTT (%.0fms) → decrease to %d bps", rtt, target));
        }
        // 3. Good conditions → increase gradually
        else if (packetLoss < 0.005 && rtt < 100) {
            // Network is good → increase bitrate
            target = (int) (target * INCREASE_FACTOR);
            logger.fine(String.format("Good network conditions → increase to %d bps", target));
        }
        
        // 4. Bandwidth limit (don't exceed 80% of available bandwidth)
        int bandwidthLimit = (int) (bandwidth * 1_000_000 * 0.8); // Mbps → bps, 80%
        if (bandwidthLimit > 0 && target > bandwidthLimit) {
            target = bandwidthLimit;
            logger.fine(String.format("Bandwidth limit (%.2f Mbps) → cap at %d bps",
                bandwidth, target));
        }
        
        // 5. CPU limit (if CPU is high, reduce bitrate)
        if (cpu > 0.8) {
            target = (int) (target * 0.9);
            logger.fine(String.format("High CPU (%.0f%%) → decrease to %d bps", cpu * 100, target));
        }
        
        return target;
    }
    
    /**
     * Smooth bitrate adjustment (avoid sudden jumps)
     */
    private int smoothAdjustment(int current, int target) {
        // Maximum change per adjustment: 20%
        int maxChange = (int) (current * 0.2);
        
        if (target > current) {
            // Increasing
            return Math.min(target, current + maxChange);
        } else {
            // Decreasing
            return Math.max(target, current - maxChange);
        }
    }
    
    /**
     * Apply bitrate change and notify callback
     */
    private void applyBitrateChange(int newBitrate, String reason) {
        int oldBitrate = currentBitrate;
        currentBitrate = newBitrate;
        
        totalAdjustments.incrementAndGet();
        if (newBitrate > oldBitrate) {
            increasesCount.incrementAndGet();
        } else {
            decreasesCount.incrementAndGet();
        }
        
        logger.info(String.format("Bitrate adjusted: %.2f → %.2f Mbps (%s)",
            oldBitrate / 1_000_000.0, newBitrate / 1_000_000.0, reason));
        
        // Notify callback
        if (callback != null) {
            callback.onBitrateChanged(newBitrate);
        }
    }
    
    // ===============================
    // Metrics Getters (from ConnectionMetrics)
    // ===============================
    
    private double getAveragePacketLoss() {
        // TODO: Get from ConnectionMetrics.PeerMetrics
        // For now, return placeholder
        return 0.01; // 1% loss
    }
    
    private double getAverageRTT() {
        // TODO: Get from ConnectionMetrics.PeerMetrics
        return 50.0; // 50ms
    }
    
    private double getAvailableBandwidth() {
        // TODO: Get from ConnectionMetrics
        return 5.0; // 5 Mbps
    }
    
    private double getCPULoad() {
        // TODO: Get from ConnectionMetrics
        return 0.3; // 30%
    }
    
    // ===============================
    // Getters & Setters
    // ===============================
    
    public int getCurrentBitrate() {
        return currentBitrate;
    }
    
    public void setCallback(BitrateChangeCallback callback) {
        this.callback = callback;
    }
    
    public void setBitrateCallback(BitrateChangeCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Manually set bitrate (for testing or override)
     */
    public void setTargetBitrate(int bitrate) {
        bitrate = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, bitrate));
        applyBitrateChange(bitrate, "manual override");
    }
    
    /**
     * Get statistics
     */
    public String getStatistics() {
        return String.format("Bitrate: %.2f Mbps | Adjustments: %d (↑%d ↓%d)",
            currentBitrate / 1_000_000.0,
            totalAdjustments.get(),
            increasesCount.get(),
            decreasesCount.get());
    }
}
