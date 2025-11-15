package com.saferoom.webrtc.relay.qos;

import com.saferoom.webrtc.relay.MediaPacket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Forward Error Correction (FEC) Encoder for Real-Time Video
 * 
 * Uses XOR-based FEC for packet loss recovery WITHOUT retransmission.
 * This is critical for real-time video where latency is more important than perfect quality.
 * 
 * Strategy:
 * - Group N packets (default: 10)
 * - Generate 1 FEC packet = XOR(P1, P2, ..., P10)
 * - If any 1 packet is lost, recover from FEC
 * - If 2+ packets lost, skip frame (better than waiting)
 * 
 * Overhead: ~10% (1 FEC packet per 10 data packets)
 * Recovery: Can recover from 1 packet loss per group
 * Latency: 0ms (no retransmission!)
 * 
 * Note: For video, dropping frames is BETTER than adding latency.
 * Users prefer smooth playback over perfect quality.
 */
public class FECEncoder {
    
    private static final int DEFAULT_GROUP_SIZE = 10; // 10 packets per FEC group
    private static final int MAX_PACKET_SIZE = 1500;  // MTU size
    
    private final int groupSize;
    private final List<MediaPacket> currentGroup;
    private int currentGroupId;
    
    /**
     * Constructor
     */
    public FECEncoder() {
        this(DEFAULT_GROUP_SIZE);
    }
    
    /**
     * Constructor with custom group size
     * 
     * @param groupSize Number of packets per FEC group (recommended: 5-20)
     */
    public FECEncoder(int groupSize) {
        if (groupSize < 2 || groupSize > 50) {
            throw new IllegalArgumentException("Group size must be between 2 and 50");
        }
        
        this.groupSize = groupSize;
        this.currentGroup = new ArrayList<>(groupSize);
        this.currentGroupId = 1;
    }
    
    /**
     * Add packet to current FEC group
     * Returns FEC packet when group is complete
     * 
     * @param packet Media packet to add
     * @return FEC packet if group complete, null otherwise
     */
    public MediaPacket addPacket(MediaPacket packet) {
        // Skip if already a FEC packet
        if (packet.getType() == MediaPacket.PacketType.FEC) {
            return null;
        }
        
        currentGroup.add(packet);
        
        // Check if group is complete
        if (currentGroup.size() >= groupSize) {
            MediaPacket fecPacket = generateFECPacket();
            currentGroup.clear();
            currentGroupId++;
            return fecPacket;
        }
        
        return null;
    }
    
    /**
     * Generate FEC packet for current group using XOR
     * 
     * XOR Algorithm:
     * FEC = P1 ⊕ P2 ⊕ P3 ⊕ ... ⊕ Pn
     * Recovery: If Pi is lost, Pi = FEC ⊕ P1 ⊕ ... ⊕ P(i-1) ⊕ P(i+1) ⊕ ... ⊕ Pn
     */
    private MediaPacket generateFECPacket() {
        if (currentGroup.isEmpty()) {
            return null;
        }
        
        // Find maximum payload size in group
        int maxPayloadSize = 0;
        for (MediaPacket packet : currentGroup) {
            maxPayloadSize = Math.max(maxPayloadSize, packet.getPayload().length);
        }
        
        // Create FEC payload (XOR of all packets)
        byte[] fecPayload = new byte[maxPayloadSize];
        Arrays.fill(fecPayload, (byte) 0);
        
        // XOR all packet payloads
        for (MediaPacket packet : currentGroup) {
            byte[] payload = packet.getPayload();
            for (int i = 0; i < payload.length; i++) {
                fecPayload[i] ^= payload[i];
            }
        }
        
        // Create FEC packet metadata
        String sourceId = currentGroup.get(0).getSourceId();
        long timestamp = currentGroup.get(0).getTimestamp();
        int sequenceNumber = currentGroup.get(0).getSequenceNumber(); // Base sequence
        
        // Create FEC packet
        return new MediaPacket(
            MediaPacket.PacketType.FEC,
            sourceId,
            timestamp,
            sequenceNumber,
            fecPayload,
            true,              // isFECPacket = true
            currentGroupId,    // fecGroupId
            fecPayload         // fecData
        );
    }
    
    /**
     * Flush current group and generate FEC packet (even if incomplete)
     * Call this at end of stream or when switching groups
     * 
     * @return FEC packet or null if no packets in group
     */
    public MediaPacket flush() {
        if (currentGroup.isEmpty()) {
            return null;
        }
        
        MediaPacket fecPacket = generateFECPacket();
        currentGroup.clear();
        currentGroupId++;
        return fecPacket;
    }
    
    /**
     * Reset encoder state
     */
    public void reset() {
        currentGroup.clear();
        currentGroupId = 1;
    }
    
    /**
     * Get current group size
     */
    public int getGroupSize() {
        return groupSize;
    }
    
    /**
     * Get current group fill count
     */
    public int getCurrentGroupCount() {
        return currentGroup.size();
    }
}
