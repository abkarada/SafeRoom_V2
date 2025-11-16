package com.saferoom.media_engine.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Transport layer statistics
 * 
 * Collects real-time metrics about packet transmission and reception.
 * Used for adaptive bitrate control, network quality estimation, and debugging.
 * 
 * Thread Safety: All fields use atomic operations for lock-free updates.
 */
public class TransportStats {
    // Send statistics
    private final AtomicLong audioPacketsSent = new AtomicLong(0);
    private final AtomicLong videoPacketsSent = new AtomicLong(0);
    private final AtomicLong audioBytesSent = new AtomicLong(0);
    private final AtomicLong videoBytesSent = new AtomicLong(0);
    
    // Receive statistics
    private final AtomicLong audioPacketsReceived = new AtomicLong(0);
    private final AtomicLong videoPacketsReceived = new AtomicLong(0);
    private final AtomicLong audioBytesReceived = new AtomicLong(0);
    private final AtomicLong videoBytesReceived = new AtomicLong(0);
    
    // Error statistics
    private final AtomicLong packetsLost = new AtomicLong(0);
    private final AtomicLong packetsOutOfOrder = new AtomicLong(0);
    private final AtomicLong packetsDuplicate = new AtomicLong(0);
    
    // Timing statistics
    private volatile long lastAudioPacketTime = 0;
    private volatile long lastVideoPacketTime = 0;
    private volatile double averageJitter = 0.0;  // Milliseconds
    
    // --- Send Statistics ---
    
    public void recordAudioPacketSent(int bytes) {
        audioPacketsSent.incrementAndGet();
        audioBytesSent.addAndGet(bytes);
    }
    
    public void recordVideoPacketSent(int bytes) {
        videoPacketsSent.incrementAndGet();
        videoBytesSent.addAndGet(bytes);
    }
    
    public long getAudioPacketsSent() {
        return audioPacketsSent.get();
    }
    
    public long getVideoPacketsSent() {
        return videoPacketsSent.get();
    }
    
    public long getAudioBytesSent() {
        return audioBytesSent.get();
    }
    
    public long getVideoBytesSent() {
        return videoBytesSent.get();
    }
    
    // --- Receive Statistics ---
    
    public void recordAudioPacketReceived(int bytes) {
        audioPacketsReceived.incrementAndGet();
        audioBytesReceived.addAndGet(bytes);
        lastAudioPacketTime = System.currentTimeMillis();
    }
    
    public void recordVideoPacketReceived(int bytes) {
        videoPacketsReceived.incrementAndGet();
        videoBytesReceived.addAndGet(bytes);
        lastVideoPacketTime = System.currentTimeMillis();
    }
    
    public long getAudioPacketsReceived() {
        return audioPacketsReceived.get();
    }
    
    public long getVideoPacketsReceived() {
        return videoPacketsReceived.get();
    }
    
    public long getAudioBytesReceived() {
        return audioBytesReceived.get();
    }
    
    public long getVideoBytesReceived() {
        return videoBytesReceived.get();
    }
    
    // --- Error Statistics ---
    
    public void recordPacketLost() {
        packetsLost.incrementAndGet();
    }
    
    public void recordPacketOutOfOrder() {
        packetsOutOfOrder.incrementAndGet();
    }
    
    public void recordPacketDuplicate() {
        packetsDuplicate.incrementAndGet();
    }
    
    public long getPacketsLost() {
        return packetsLost.get();
    }
    
    public long getPacketsOutOfOrder() {
        return packetsOutOfOrder.get();
    }
    
    public long getPacketsDuplicate() {
        return packetsDuplicate.get();
    }
    
    // --- Timing Statistics ---
    
    public long getLastAudioPacketTime() {
        return lastAudioPacketTime;
    }
    
    public long getLastVideoPacketTime() {
        return lastVideoPacketTime;
    }
    
    /**
     * Update jitter calculation using exponential moving average
     * 
     * @param packetJitter Jitter for this packet in milliseconds
     */
    public void updateJitter(double packetJitter) {
        // RFC 3550 jitter calculation: J(i) = J(i-1) + (|D(i-1,i)| - J(i-1))/16
        averageJitter = averageJitter + (Math.abs(packetJitter) - averageJitter) / 16.0;
    }
    
    public double getAverageJitter() {
        return averageJitter;
    }
    
    // --- Derived Metrics ---
    
    /**
     * Calculate packet loss rate (0.0 to 1.0)
     * 
     * @return Loss rate (e.g., 0.05 = 5% loss)
     */
    public double getPacketLossRate() {
        long totalReceived = audioPacketsReceived.get() + videoPacketsReceived.get();
        long totalLost = packetsLost.get();
        
        if (totalReceived + totalLost == 0) return 0.0;
        
        return (double) totalLost / (totalReceived + totalLost);
    }
    
    /**
     * Calculate current audio bitrate in kbps
     * 
     * @param windowMs Time window for calculation (e.g., 1000ms)
     * @return Bitrate in kilobits per second
     */
    public double getAudioBitrate(long windowMs) {
        // Simplified: actual implementation would track bytes over time window
        long bytes = audioBytesSent.get();
        return (bytes * 8.0) / (windowMs / 1000.0) / 1000.0;  // kbps
    }
    
    /**
     * Calculate current video bitrate in kbps
     * 
     * @param windowMs Time window for calculation
     * @return Bitrate in kilobits per second
     */
    public double getVideoBitrate(long windowMs) {
        long bytes = videoBytesSent.get();
        return (bytes * 8.0) / (windowMs / 1000.0) / 1000.0;  // kbps
    }
    
    /**
     * Reset all statistics to zero
     */
    public void reset() {
        audioPacketsSent.set(0);
        videoPacketsSent.set(0);
        audioBytesSent.set(0);
        videoBytesSent.set(0);
        audioPacketsReceived.set(0);
        videoPacketsReceived.set(0);
        audioBytesReceived.set(0);
        videoBytesReceived.set(0);
        packetsLost.set(0);
        packetsOutOfOrder.set(0);
        packetsDuplicate.set(0);
        lastAudioPacketTime = 0;
        lastVideoPacketTime = 0;
        averageJitter = 0.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "TransportStats{audioTx=%d, videoTx=%d, audioRx=%d, videoRx=%d, lost=%d, jitter=%.2fms, lossRate=%.2f%%}",
            audioPacketsSent.get(),
            videoPacketsSent.get(),
            audioPacketsReceived.get(),
            videoPacketsReceived.get(),
            packetsLost.get(),
            averageJitter,
            getPacketLossRate() * 100.0
        );
    }
}
