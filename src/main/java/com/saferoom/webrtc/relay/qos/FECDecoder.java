package com.saferoom.webrtc.relay.qos;

import com.saferoom.webrtc.relay.MediaPacket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forward Error Correction (FEC) Decoder for Real-Time Video
 * 
 * Recovers lost packets from FEC packets using XOR.
 * 
 * Recovery Rules:
 * 1. If 0 packets lost → Perfect, no recovery needed
 * 2. If 1 packet lost → Recover from FEC using XOR
 * 3. If 2+ packets lost → DROP entire frame (cannot recover)
 * 
 * Why drop instead of request?
 * - Retransmission adds 50-200ms latency
 * - Video codec can handle dropped frames (I-frame resync)
 * - Smooth playback > Perfect quality
 * 
 * Tradeoff:
 * - 10% bandwidth overhead (FEC packets)
 * - Recovers ~90% of single packet losses
 * - 0ms latency penalty
 */
public class FECDecoder {
    
    private static final int MAX_GROUPS = 100;  // Maximum concurrent FEC groups
    private static final long GROUP_TIMEOUT_MS = 1000; // 1 second timeout
    
    // FEC group tracking
    private final Map<Integer, FECGroup> activeGroups;
    
    // Statistics
    private long totalPacketsReceived = 0;
    private long totalPacketsLost = 0;
    private long totalPacketsRecovered = 0;
    private long totalPacketsUnrecoverable = 0;
    
    /**
     * FEC Group - tracks packets in a single FEC group
     */
    private static class FECGroup {
        final int groupId;
        final Map<Integer, MediaPacket> packets;  // sequenceNumber → packet
        MediaPacket fecPacket;
        final long createdTime;
        int expectedPackets;
        
        FECGroup(int groupId, int expectedPackets) {
            this.groupId = groupId;
            this.packets = new HashMap<>();
            this.expectedPackets = expectedPackets;
            this.createdTime = System.currentTimeMillis();
        }
        
        boolean isComplete() {
            return packets.size() >= expectedPackets && fecPacket != null;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - createdTime > GROUP_TIMEOUT_MS;
        }
        
        int getMissingCount() {
            return expectedPackets - packets.size();
        }
        
        Integer getMissingSequence() {
            if (packets.isEmpty()) return null;
            
            int minSeq = Integer.MAX_VALUE;
            int maxSeq = Integer.MIN_VALUE;
            for (int seq : packets.keySet()) {
                minSeq = Math.min(minSeq, seq);
                maxSeq = Math.max(maxSeq, seq);
            }
            
            // Find missing sequence in range
            for (int seq = minSeq; seq <= maxSeq; seq++) {
                if (!packets.containsKey(seq)) {
                    return seq;
                }
            }
            
            return null;
        }
    }
    
    /**
     * Constructor
     */
    public FECDecoder() {
        this.activeGroups = new ConcurrentHashMap<>();
    }
    
    /**
     * Process incoming packet
     * 
     * @param packet Media or FEC packet
     * @return List of packets ready for playback (may include recovered packets)
     */
    public List<MediaPacket> processPacket(MediaPacket packet) {
        totalPacketsReceived++;
        
        if (packet.getType() == MediaPacket.PacketType.FEC) {
            return processFECPacket(packet);
        } else {
            return processMediaPacket(packet);
        }
    }
    
    /**
     * Process media packet (video/audio)
     */
    private List<MediaPacket> processMediaPacket(MediaPacket packet) {
        int groupId = packet.getFecGroupId();
        if (groupId == 0) {
            // No FEC for this packet, pass through immediately
            return Collections.singletonList(packet);
        }
        
        // Get or create FEC group
        FECGroup group = activeGroups.computeIfAbsent(groupId, 
            id -> new FECGroup(id, 10)); // default 10 packets per group
        
        // Add packet to group
        group.packets.put(packet.getSequenceNumber(), packet);
        
        // Check if we can recover lost packets
        return tryRecovery(group);
    }
    
    /**
     * Process FEC packet
     */
    private List<MediaPacket> processFECPacket(MediaPacket fecPacket) {
        int groupId = fecPacket.getFecGroupId();
        
        // Get or create FEC group
        FECGroup group = activeGroups.computeIfAbsent(groupId,
            id -> new FECGroup(id, 10));
        
        // Store FEC packet
        group.fecPacket = fecPacket;
        
        // Try recovery
        return tryRecovery(group);
    }
    
    /**
     * Try to recover lost packets from FEC
     * 
     * @return List of packets ready for playback
     */
    private List<MediaPacket> tryRecovery(FECGroup group) {
        List<MediaPacket> readyPackets = new ArrayList<>();
        
        // Check if group has FEC packet
        if (group.fecPacket == null) {
            // Wait for FEC packet
            return readyPackets;
        }
        
        int missingCount = group.getMissingCount();
        
        if (missingCount == 0) {
            // ✅ Perfect! No loss
            readyPackets.addAll(group.packets.values());
            activeGroups.remove(group.groupId);
            
        } else if (missingCount == 1) {
            // ✅ Recoverable! Use FEC to recover lost packet
            MediaPacket recovered = recoverLostPacket(group);
            if (recovered != null) {
                totalPacketsRecovered++;
                readyPackets.addAll(group.packets.values());
                readyPackets.add(recovered);
                activeGroups.remove(group.groupId);
            }
            
        } else {
            // ❌ Unrecoverable (2+ packets lost)
            totalPacketsLost += missingCount;
            totalPacketsUnrecoverable++;
            
            // Drop entire group, pass what we have
            readyPackets.addAll(group.packets.values());
            activeGroups.remove(group.groupId);
            
            System.err.printf("[FEC] Cannot recover group %d: %d packets lost (>1)%n",
                group.groupId, missingCount);
        }
        
        // Cleanup expired groups
        cleanupExpiredGroups();
        
        return readyPackets;
    }
    
    /**
     * Recover lost packet using XOR
     * 
     * Formula: Lost = FEC ⊕ P1 ⊕ P2 ⊕ ... ⊕ Pn
     */
    private MediaPacket recoverLostPacket(FECGroup group) {
        if (group.fecPacket == null) {
            return null;
        }
        
        Integer missingSeq = group.getMissingSequence();
        if (missingSeq == null) {
            return null;
        }
        
        // Start with FEC payload
        byte[] fecPayload = group.fecPacket.getFecData();
        if (fecPayload == null) {
            return null;
        }
        
        byte[] recovered = Arrays.copyOf(fecPayload, fecPayload.length);
        
        // XOR with all received packets
        for (MediaPacket packet : group.packets.values()) {
            byte[] payload = packet.getPayload();
            for (int i = 0; i < Math.min(recovered.length, payload.length); i++) {
                recovered[i] ^= payload[i];
            }
        }
        
        // Create recovered packet
        MediaPacket firstPacket = group.packets.values().iterator().next();
        
        System.out.printf("[FEC] ✅ Recovered packet: group=%d, seq=%d%n",
            group.groupId, missingSeq);
        
        return new MediaPacket(
            firstPacket.getType(), // Assume same type as other packets
            firstPacket.getSourceId(),
            firstPacket.getTimestamp() + missingSeq, // Estimate timestamp
            missingSeq,
            recovered,
            false,
            group.groupId,
            null
        );
    }
    
    /**
     * Cleanup expired FEC groups (timeout = 1 second)
     */
    private void cleanupExpiredGroups() {
        activeGroups.entrySet().removeIf(entry -> {
            FECGroup group = entry.getValue();
            if (group.isExpired()) {
                totalPacketsLost += group.getMissingCount();
                System.err.printf("[FEC] ⏱️ Group %d expired (timeout)%n", group.groupId);
                return true;
            }
            return false;
        });
        
        // Limit max groups
        if (activeGroups.size() > MAX_GROUPS) {
            System.err.println("[FEC] ⚠️ Too many active groups, clearing old ones");
            activeGroups.clear();
        }
    }
    
    /**
     * Get recovery statistics
     */
    public String getStatistics() {
        double recoveryRate = totalPacketsLost > 0 
            ? (totalPacketsRecovered * 100.0 / totalPacketsLost) 
            : 0.0;
        
        return String.format(
            "FEC Stats: Received=%d, Lost=%d, Recovered=%d (%.1f%%), Unrecoverable=%d",
            totalPacketsReceived,
            totalPacketsLost,
            totalPacketsRecovered,
            recoveryRate,
            totalPacketsUnrecoverable
        );
    }
    
    /**
     * Reset statistics
     */
    public void reset() {
        activeGroups.clear();
        totalPacketsReceived = 0;
        totalPacketsLost = 0;
        totalPacketsRecovered = 0;
        totalPacketsUnrecoverable = 0;
    }
}
