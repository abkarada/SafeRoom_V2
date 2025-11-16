package com.saferoom.webrtc.relay;

import com.saferoom.crypto.GroupKeyManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDRTMediaBridge - Simple bridge between MediaEngine and SDRT tree
 * 
 * RESPONSIBILITIES:
 * 1. Receive RTP packets from MediaEngine
 * 2. Encrypt with group key
 * 3. Wrap in MediaPacket and send to TreeNode
 * 4. Receive MediaPackets from TreeNode
 * 5. Decrypt and forward to MediaEngine
 * 
 * WHAT IT DOES NOT DO (MediaEngine's job):
 * - FEC encoding/decoding
 * - Jitter buffering
 * - Bitrate adaptation
 * - RTP packet parsing
 * - Codec handling
 * 
 * ARCHITECTURE:
 * - MediaEngine: All media processing (capture, encode, decode, playback, FEC, jitter)
 * - SDRTMediaBridge: Encryption + MediaPacket wrapping
 * - TreeNode: Tree topology + packet forwarding
 * - WebRTC DataChannel: NAT traversal + DTLS transport
 */
public class SDRTMediaBridge {
    
    private static final Logger logger = Logger.getLogger(SDRTMediaBridge.class.getName());
    
    // Core components
    private final String userId;
    private final String roomId;
    private final TreeNode treeNode;
    private final GroupKeyManager keyManager;
    
    // Packet sequencing
    private final AtomicInteger outgoingSequence = new AtomicInteger(0);
    
    // Callback for MediaEngine (to receive decrypted RTP packets)
    private java.util.function.BiConsumer<byte[], String> rtpPacketCallback;
    
    /**
     * Constructor
     */
    public SDRTMediaBridge(String userId, String roomId, TreeNode treeNode, 
                           GroupKeyManager keyManager) {
        this.userId = userId;
        this.roomId = roomId;
        this.treeNode = treeNode;
        this.keyManager = keyManager;
        
        logger.info(String.format("SDRTMediaBridge created: userId=%s, roomId=%s", 
                                  userId, roomId));
    }
    
    /**
     * Set callback for receiving decrypted RTP packets
     * Called by MediaEngine to register itself
     * 
     * @param callback (rtpPacket, sourceUserId) -> void
     */
    public void setRtpPacketCallback(java.util.function.BiConsumer<byte[], String> callback) {
        this.rtpPacketCallback = callback;
    }
    
    /**
     * Process outgoing RTP packet from MediaEngine
     * 
     * Called by MediaEngine when RTP packet is ready to send
     * 
     * @param rtpPacket Raw RTP packet bytes from GStreamer
     */
    public void sendRtpPacket(byte[] rtpPacket) {
        try {
            // Encrypt packet
            byte[] encryptedPayload = keyManager.encrypt(roomId, rtpPacket);
            
            // Create MediaPacket
            MediaPacket packet = new MediaPacket(
                MediaPacket.PacketType.VIDEO,
                userId,
                System.currentTimeMillis() * 1000, // Microseconds
                outgoingSequence.incrementAndGet(),
                encryptedPayload
            );
            packet.setRoomId(roomId);
            
            // Send to tree
            sendPacketToTree(packet);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send RTP packet", e);
        }
    }
    
    /**
     * Send packet to tree based on node role
     */
    private void sendPacketToTree(MediaPacket packet) {
        switch (treeNode.getRole()) {
            case LEAF:
                // LEAF: Send to parent only
                treeNode.sendToParent(packet);
                break;
                
            case RELAY:
            case ROOT:
                // RELAY/ROOT: Send to parent (if exists) and all children
                if (treeNode.getParent() != null) {
                    treeNode.sendToParent(packet);
                }
                // TreeNode will broadcast to children
                break;
        }
    }
    
    /**
     * Process incoming MediaPacket from tree
     * 
     * Called by TreeNode when packet arrives
     * 
     * @param packet Received MediaPacket
     */
    public void processIncomingPacket(MediaPacket packet) {
        try {
            // Validate room
            if (!roomId.equals(packet.getRoomId())) {
                logger.warning("Packet from wrong room: " + packet.getRoomId());
                return;
            }
            
            // Ignore own packets
            if (packet.getSourceId().equals(userId)) {
                return;
            }
            
            // Decrypt payload
            byte[] rtpPacket = keyManager.decrypt(roomId, packet.getPayload());
            
            // Forward to MediaEngine
            if (rtpPacketCallback != null) {
                rtpPacketCallback.accept(rtpPacket, packet.getSourceId());
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to process incoming packet", e);
        }
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        logger.info("SDRTMediaBridge cleanup complete");
    }
}
