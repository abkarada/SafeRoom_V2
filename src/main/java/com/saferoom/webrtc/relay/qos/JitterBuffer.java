package com.saferoom.webrtc.relay.qos;

import com.saferoom.webrtc.relay.MediaPacket;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Jitter Buffer for Real-Time Video Playback
 * 
 * Problems solved:
 * 1. Packets arrive out-of-order due to network jitter
 * 2. Variable network delay causes uneven packet arrival
 * 3. Missing packets need to be detected quickly
 * 
 * Solution:
 * - Buffer incoming packets for short duration (40-100ms)
 * - Reorder by sequence number
 * - Output packets in correct order at steady rate
 * - Drop packets that arrive too late
 * 
 * Tradeoff:
 * - Adds 40-100ms latency (configurable)
 * - Provides smooth playback
 * - Hides network jitter (±20-50ms)
 * 
 * Video Frame Types:
 * - I-frame (keyframe): Can decode independently
 * - P-frame: Depends on previous frame
 * - If P-frame is lost → skip until next I-frame
 */
public class JitterBuffer {
    
    // Buffer settings
    private static final int DEFAULT_BUFFER_MS = 60;      // 60ms buffer (2-3 frames at 30fps)
    private static final int MIN_BUFFER_MS = 20;          // Minimum buffer
    private static final int MAX_BUFFER_MS = 200;         // Maximum buffer
    private static final int PACKET_TIMEOUT_MS = 1000;    // Drop packets older than 1s
    
    // Buffer state
    private final int bufferDurationMs;
    private final SortedMap<Integer, BufferedPacket> buffer;  // sequence → packet
    private final AtomicInteger lastOutputSequence;
    private final AtomicInteger highestSequence;
    
    // Statistics
    private long totalPacketsIn = 0;
    private long totalPacketsOut = 0;
    private long totalPacketsDroppedLate = 0;
    private long totalPacketsDroppedDuplicate = 0;
    private long totalReorderings = 0;
    
    /**
     * Buffered packet with timestamp
     */
    private static class BufferedPacket {
        final MediaPacket packet;
        final long arrivalTime;
        boolean outputReady;
        
        BufferedPacket(MediaPacket packet) {
            this.packet = packet;
            this.arrivalTime = System.currentTimeMillis();
            this.outputReady = false;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - arrivalTime > PACKET_TIMEOUT_MS;
        }
        
        long getAge() {
            return System.currentTimeMillis() - arrivalTime;
        }
    }
    
    /**
     * Constructor with default buffer (60ms)
     */
    public JitterBuffer() {
        this(DEFAULT_BUFFER_MS);
    }
    
    /**
     * Constructor with custom buffer duration
     * 
     * @param bufferDurationMs Buffer duration in milliseconds (20-200ms)
     */
    public JitterBuffer(int bufferDurationMs) {
        if (bufferDurationMs < MIN_BUFFER_MS || bufferDurationMs > MAX_BUFFER_MS) {
            throw new IllegalArgumentException(
                String.format("Buffer duration must be between %d and %d ms", MIN_BUFFER_MS, MAX_BUFFER_MS));
        }
        
        this.bufferDurationMs = bufferDurationMs;
        this.buffer = new ConcurrentSkipListMap<>();
        this.lastOutputSequence = new AtomicInteger(-1);
        this.highestSequence = new AtomicInteger(-1);
    }
    
    /**
     * Add packet to jitter buffer
     * 
     * @param packet Incoming media packet
     */
    public synchronized void addPacket(MediaPacket packet) {
        totalPacketsIn++;
        
        int sequence = packet.getSequenceNumber();
        
        // Check for duplicate
        if (buffer.containsKey(sequence)) {
            totalPacketsDroppedDuplicate++;
            return;
        }
        
        // Check if packet is too old (arrived after we already output that sequence)
        int lastOut = lastOutputSequence.get();
        if (lastOut >= 0 && sequence <= lastOut) {
            totalPacketsDroppedLate++;
            // System.err.printf("[JitterBuffer] Dropped late packet: seq=%d (last output=%d)%n", 
            //     sequence, lastOut);
            return;
        }
        
        // Update highest sequence seen
        int prevHighest = highestSequence.get();
        if (sequence > prevHighest) {
            highestSequence.set(sequence);
            
            // Check for reordering
            if (prevHighest >= 0 && sequence != prevHighest + 1) {
                totalReorderings++;
            }
        }
        
        // Add to buffer
        BufferedPacket buffered = new BufferedPacket(packet);
        buffer.put(sequence, buffered);
    }
    
    /**
     * Get packets ready for output (buffered for sufficient time)
     * 
     * Call this periodically (e.g., every 10-20ms) to drain buffer
     * 
     * @return List of packets in sequence order, ready for playback
     */
    public synchronized List<MediaPacket> getReadyPackets() {
        List<MediaPacket> ready = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        // Find packets that have been buffered long enough
        Iterator<Map.Entry<Integer, BufferedPacket>> it = buffer.entrySet().iterator();
        
        while (it.hasNext()) {
            Map.Entry<Integer, BufferedPacket> entry = it.next();
            int sequence = entry.getKey();
            BufferedPacket buffered = entry.getValue();
            
            // Check if expired (too old)
            if (buffered.isExpired()) {
                it.remove();
                totalPacketsDroppedLate++;
                continue;
            }
            
            // Check if buffered long enough
            long age = buffered.getAge();
            if (age >= bufferDurationMs) {
                buffered.outputReady = true;
            }
            
            // Output packets in sequence order
            int expectedSeq = lastOutputSequence.get() + 1;
            if (buffered.outputReady && sequence == expectedSeq) {
                ready.add(buffered.packet);
                lastOutputSequence.set(sequence);
                totalPacketsOut++;
                it.remove();
            } else if (buffered.outputReady && sequence < expectedSeq) {
                // Late packet that somehow stayed in buffer
                it.remove();
                totalPacketsDroppedLate++;
            } else {
                // Not ready yet, or out of sequence
                break;
            }
        }
        
        return ready;
    }
    
    /**
     * Force output all buffered packets (flush buffer)
     * Use when stream ends or switching sources
     * 
     * @return All packets in buffer, in sequence order
     */
    public synchronized List<MediaPacket> flush() {
        List<MediaPacket> all = new ArrayList<>();
        
        for (BufferedPacket buffered : buffer.values()) {
            all.add(buffered.packet);
            totalPacketsOut++;
        }
        
        buffer.clear();
        return all;
    }
    
    /**
     * Get current buffer size (number of packets)
     */
    public int getBufferSize() {
        return buffer.size();
    }
    
    /**
     * Get buffer fill percentage (0-100%)
     * Estimated based on sequence gaps
     */
    public double getBufferFillPercentage() {
        if (buffer.isEmpty()) return 0.0;
        
        int min = buffer.firstKey();
        int max = buffer.lastKey();
        int range = max - min + 1;
        int actual = buffer.size();
        
        return (actual * 100.0) / range;
    }
    
    /**
     * Get average packet age in buffer (milliseconds)
     */
    public double getAveragePacketAge() {
        if (buffer.isEmpty()) return 0.0;
        
        long totalAge = 0;
        for (BufferedPacket buffered : buffer.values()) {
            totalAge += buffered.getAge();
        }
        
        return totalAge / (double) buffer.size();
    }
    
    /**
     * Check if buffer is underrun (too few packets)
     * Indicates network congestion or packet loss
     */
    public boolean isUnderrun() {
        return buffer.size() < 2; // Less than 2 packets = underrun
    }
    
    /**
     * Check if buffer is overrun (too many packets)
     * Indicates slow playback or processing
     */
    public boolean isOverrun() {
        int maxPackets = (int) (bufferDurationMs / 33.0 * 3); // 3x expected
        return buffer.size() > maxPackets;
    }
    
    /**
     * Get statistics
     */
    public String getStatistics() {
        double lossRate = totalPacketsIn > 0 
            ? (totalPacketsDroppedLate * 100.0 / totalPacketsIn) 
            : 0.0;
        
        double reorderRate = totalPacketsIn > 0
            ? (totalReorderings * 100.0 / totalPacketsIn)
            : 0.0;
        
        return String.format(
            "JitterBuffer: In=%d, Out=%d, Buffered=%d, Dropped=%d (%.1f%%), Duplicates=%d, Reorderings=%d (%.1f%%), AvgAge=%.1fms",
            totalPacketsIn,
            totalPacketsOut,
            buffer.size(),
            totalPacketsDroppedLate,
            lossRate,
            totalPacketsDroppedDuplicate,
            totalReorderings,
            reorderRate,
            getAveragePacketAge()
        );
    }
    
    /**
     * Reset buffer and statistics
     */
    public void reset() {
        buffer.clear();
        lastOutputSequence.set(-1);
        highestSequence.set(-1);
        totalPacketsIn = 0;
        totalPacketsOut = 0;
        totalPacketsDroppedLate = 0;
        totalPacketsDroppedDuplicate = 0;
        totalReorderings = 0;
    }
    
    /**
     * Get buffer duration setting
     */
    public int getBufferDurationMs() {
        return bufferDurationMs;
    }
}
