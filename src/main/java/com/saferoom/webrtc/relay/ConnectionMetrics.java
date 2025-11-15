package com.saferoom.webrtc.relay;

import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCStatsReport;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * ConnectionMetrics - Collects and tracks connection quality metrics for SDRT
 * 
 * Metrics collected:
 * 1. RTT (Round-Trip Time) - via ping-pong mechanism
 * 2. Bandwidth - estimated from packet send rate
 * 3. Packet Loss - tracked per connection
 * 4. CPU Load - system CPU usage
 * 5. Overall Score - weighted combination for parent selection
 * 
 * These metrics drive the dynamic tree rebalancing algorithm.
 */
public class ConnectionMetrics {
    
    private static final Logger logger = Logger.getLogger(ConnectionMetrics.class.getName());
    
    // Metric weights for score calculation
    private static final double RTT_WEIGHT = 0.40;        // Latency most important
    private static final double BANDWIDTH_WEIGHT = 0.30;  // Upload capacity
    private static final double LOSS_WEIGHT = 0.20;       // Packet loss
    private static final double CPU_WEIGHT = 0.10;        // CPU availability
    
    // Thresholds
    private static final double OPTIMAL_RTT_MS = 50.0;           // Optimal RTT
    private static final double MAX_RTT_MS = 200.0;              // Max acceptable RTT
    private static final double OPTIMAL_BANDWIDTH_MBPS = 5.0;    // Optimal bandwidth
    private static final double MAX_LOSS_RATE = 0.05;            // 5% max loss
    
    // Measurement state
    private final Map<String, PeerMetrics> peerMetrics = new ConcurrentHashMap<>();
    private final OperatingSystemMXBean osBean;
    
    // Ping-pong for RTT measurement
    private final Map<String, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicLong pingSequence = new AtomicLong(0);
    
    // Periodic measurement scheduler
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    /**
     * Per-peer metrics
     */
    public static class PeerMetrics {
        public volatile double rttMs = 100.0;              // Current RTT
        public volatile double bandwidthMbps = 1.0;        // Upload bandwidth
        public volatile double packetLossRate = 0.0;       // Loss rate (0-1)
        public volatile double cpuLoad = 0.5;              // CPU load (0-1)
        
        public volatile long totalPacketsSent = 0;
        public volatile long totalPacketsLost = 0;
        public volatile long totalBytesSent = 0;
        public volatile long measurementStartTime = System.currentTimeMillis();
        
        public volatile long lastRttMeasurement = System.currentTimeMillis();
        public volatile long lastUpdateTime = System.currentTimeMillis();
        
        /**
         * Calculate overall quality score (0-1, higher is better)
         */
        public double calculateScore() {
            // RTT score (normalized, lower is better)
            double rttScore = Math.max(0.0, 1.0 - (rttMs / MAX_RTT_MS));
            if (rttMs <= OPTIMAL_RTT_MS) {
                rttScore = 1.0;
            }
            
            // Bandwidth score (normalized)
            double bwScore = Math.min(1.0, bandwidthMbps / OPTIMAL_BANDWIDTH_MBPS);
            
            // Loss score (inverted, lower loss is better)
            double lossScore = Math.max(0.0, 1.0 - (packetLossRate / MAX_LOSS_RATE));
            
            // CPU score (more available CPU is better)
            double cpuScore = Math.max(0.0, 1.0 - cpuLoad);
            
            // Weighted combination
            return (rttScore * RTT_WEIGHT) +
                   (bwScore * BANDWIDTH_WEIGHT) +
                   (lossScore * LOSS_WEIGHT) +
                   (cpuScore * CPU_WEIGHT);
        }
        
        /**
         * Check if connection is healthy
         */
        public boolean isHealthy() {
            return rttMs < MAX_RTT_MS && 
                   packetLossRate < MAX_LOSS_RATE &&
                   cpuLoad < 0.9;
        }
        
        /**
         * Get metrics as JSON string
         */
        public String toJson() {
            return String.format("{\"rtt\":%.1f,\"bandwidth\":%.2f,\"loss\":%.3f,\"cpu\":%.2f,\"score\":%.3f}",
                               rttMs, bandwidthMbps, packetLossRate, cpuLoad, calculateScore());
        }
    }
    
    /**
     * Constructor
     */
    public ConnectionMetrics() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Start periodic metric collection
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        
        // Collect metrics every 2 seconds
        scheduler.scheduleAtFixedRate(
            this::collectSystemMetrics,
            0, 2, TimeUnit.SECONDS
        );
        
        // Calculate bandwidth every 5 seconds
        scheduler.scheduleAtFixedRate(
            this::calculateBandwidth,
            5, 5, TimeUnit.SECONDS
        );
        
        logger.info("ConnectionMetrics started");
    }
    
    /**
     * Stop metric collection
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("ConnectionMetrics stopped");
    }
    
    /**
     * Register a peer connection for monitoring
     */
    public void registerPeer(String peerId) {
        peerMetrics.putIfAbsent(peerId, new PeerMetrics());
        logger.fine("Registered peer for metrics: " + peerId);
    }
    
    /**
     * Unregister a peer
     */
    public void unregisterPeer(String peerId) {
        peerMetrics.remove(peerId);
        pendingPings.remove(peerId);
        logger.fine("Unregistered peer: " + peerId);
    }
    
    /**
     * Record packet sent to peer
     */
    public void recordPacketSent(String peerId, int packetSize) {
        PeerMetrics metrics = peerMetrics.get(peerId);
        if (metrics != null) {
            metrics.totalPacketsSent++;
            metrics.totalBytesSent += packetSize;
            metrics.lastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Record packet lost
     */
    public void recordPacketLost(String peerId) {
        PeerMetrics metrics = peerMetrics.get(peerId);
        if (metrics != null) {
            metrics.totalPacketsLost++;
            updatePacketLossRate(peerId);
        }
    }
    
    /**
     * Start RTT measurement (send ping)
     * 
     * @param peerId Peer to ping
     * @return Ping sequence number
     */
    public long startRttMeasurement(String peerId) {
        long sequence = pingSequence.incrementAndGet();
        pendingPings.put(peerId + ":" + sequence, System.nanoTime());
        return sequence;
    }
    
    /**
     * Complete RTT measurement (received pong)
     * 
     * @param peerId Peer that responded
     * @param sequence Ping sequence number
     */
    public void completeRttMeasurement(String peerId, long sequence) {
        String key = peerId + ":" + sequence;
        Long startTime = pendingPings.remove(key);
        
        if (startTime != null) {
            long rttNanos = System.nanoTime() - startTime;
            double rttMs = rttNanos / 1_000_000.0;
            
            PeerMetrics metrics = peerMetrics.get(peerId);
            if (metrics != null) {
                // Exponential moving average for smoothing
                metrics.rttMs = (metrics.rttMs * 0.7) + (rttMs * 0.3);
                metrics.lastRttMeasurement = System.currentTimeMillis();
                
                logger.fine(String.format("RTT to %s: %.1f ms", peerId, metrics.rttMs));
            }
        }
    }
    
    /**
     * Update packet loss rate for peer
     */
    private void updatePacketLossRate(String peerId) {
        PeerMetrics metrics = peerMetrics.get(peerId);
        if (metrics != null && metrics.totalPacketsSent > 0) {
            metrics.packetLossRate = (double) metrics.totalPacketsLost / 
                                     metrics.totalPacketsSent;
        }
    }
    
    /**
     * Calculate bandwidth from send statistics
     */
    private void calculateBandwidth() {
        for (Map.Entry<String, PeerMetrics> entry : peerMetrics.entrySet()) {
            PeerMetrics metrics = entry.getValue();
            
            long elapsedMs = System.currentTimeMillis() - metrics.measurementStartTime;
            if (elapsedMs > 0) {
                // Calculate Mbps
                double seconds = elapsedMs / 1000.0;
                double megabits = (metrics.totalBytesSent * 8) / 1_000_000.0;
                metrics.bandwidthMbps = megabits / seconds;
                
                logger.fine(String.format("Bandwidth to %s: %.2f Mbps", 
                                         entry.getKey(), metrics.bandwidthMbps));
            }
        }
    }
    
    /**
     * Collect system-wide metrics (CPU)
     */
    private void collectSystemMetrics() {
        // Get system CPU load
        double cpuLoad = osBean.getSystemLoadAverage();
        
        // Normalize to 0-1 range (assume 4 cores)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        if (cpuLoad >= 0) {
            cpuLoad = cpuLoad / availableProcessors;
        } else {
            cpuLoad = 0.5; // Unknown, assume 50%
        }
        
        // Update all peer metrics with current CPU
        for (PeerMetrics metrics : peerMetrics.values()) {
            metrics.cpuLoad = Math.max(0.0, Math.min(1.0, cpuLoad));
        }
    }
    
    /**
     * Get metrics for a specific peer
     */
    public PeerMetrics getMetrics(String peerId) {
        return peerMetrics.get(peerId);
    }
    
    /**
     * Get all peer metrics
     */
    public Map<String, PeerMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(peerMetrics);
    }
    
    /**
     * Get best peer based on score
     */
    public String getBestPeer() {
        return peerMetrics.entrySet().stream()
            .max((a, b) -> Double.compare(
                a.getValue().calculateScore(),
                b.getValue().calculateScore()
            ))
            .map(Map.Entry::getKey)
            .orElse(null);
    }
    
    /**
     * Check if peer metrics are stale (no updates in 10 seconds)
     */
    public boolean areMetricsStale(String peerId) {
        PeerMetrics metrics = peerMetrics.get(peerId);
        if (metrics == null) {
            return true;
        }
        
        long age = System.currentTimeMillis() - metrics.lastUpdateTime;
        return age > 10_000; // 10 seconds
    }
    
    /**
     * Reset metrics for a peer (for testing)
     */
    public void resetPeerMetrics(String peerId) {
        PeerMetrics metrics = peerMetrics.get(peerId);
        if (metrics != null) {
            metrics.totalPacketsSent = 0;
            metrics.totalPacketsLost = 0;
            metrics.totalBytesSent = 0;
            metrics.measurementStartTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Get summary of all metrics (for debugging)
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("Connection Metrics Summary:\n");
        
        for (Map.Entry<String, PeerMetrics> entry : peerMetrics.entrySet()) {
            PeerMetrics m = entry.getValue();
            sb.append(String.format("  %s: RTT=%.1fms BW=%.2fMbps Loss=%.1f%% CPU=%.0f%% Score=%.3f %s\n",
                                   entry.getKey(), m.rttMs, m.bandwidthMbps, 
                                   m.packetLossRate * 100, m.cpuLoad * 100,
                                   m.calculateScore(),
                                   m.isHealthy() ? "✓" : "⚠"));
        }
        
        return sb.toString();
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        stop();
        peerMetrics.clear();
        pendingPings.clear();
        logger.info("ConnectionMetrics cleanup complete");
    }
}
