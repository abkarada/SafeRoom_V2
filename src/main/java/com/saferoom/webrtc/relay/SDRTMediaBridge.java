package com.saferoom.webrtc.relay;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDRTMediaBridge - Simple bridge between MediaEngine and SDRT tree
 * 
 * RESPONSIBILITIES:
 * 1. Receive RTP packets from MediaEngine
 * 2. Wrap in MediaPacket and send to TreeNode
 * 3. Receive MediaPackets from TreeNode
 * 4. Forward raw RTP to MediaEngine
 * 
 * NO ENCRYPTION HERE:
 * - WebRTC DataChannel already provides DTLS encryption
 * - Relay nodes (B in A→B→C) can forward packets WITHOUT decrypt/re-encrypt
 * - This enables zero-copy forwarding (critical for latency)
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
 * - SDRTMediaBridge: Simple MediaPacket wrapping (NO ENCRYPTION)
 * - TreeNode: Tree topology + packet forwarding
 * - WebRTC DataChannel: NAT traversal + DTLS transport security
 * 
 * @deprecated This class is being replaced by media_engine/adapter/SDRTTransport
 *             New code should use the MediaEngine transport abstraction layer.
 */
@Deprecated
public class SDRTMediaBridge {
    
    private static final Logger logger = Logger.getLogger(SDRTMediaBridge.class.getName());
    
    // Core components
    private final String userId;
    private final String roomId;
    private final TreeNode treeNode;
    
    // Packet sequencing
    private final AtomicInteger outgoingSequence = new AtomicInteger(0);
    
    // Callback for MediaEngine (to receive RTP packets)
    private java.util.function.BiConsumer<byte[], String> rtpPacketCallback;
    
    /**
     * Constructor (GroupKeyManager removed - no encryption needed)
     * 
     * @deprecated Use media_engine/adapter/SDRTTransport instead
     */
    @Deprecated
    public SDRTMediaBridge(String userId, String roomId, TreeNode treeNode) {
        this.userId = userId;
        this.roomId = roomId;
        this.treeNode = treeNode;
        
        logger.warning("⚠️ SDRTMediaBridge is deprecated! Use media_engine.adapter.SDRTTransport");
        logger.info(String.format("SDRTMediaBridge created: userId=%s, roomId=%s (NO ENCRYPTION)", 
                                  userId, roomId));
    }
    
    /**
     * Backward compatibility constructor (accepts but ignores GroupKeyManager)
     * 
     * @deprecated GroupKeyManager is no longer used - WebRTC DTLS provides security
     */
    @Deprecated
    public SDRTMediaBridge(String userId, String roomId, TreeNode treeNode, 
                           Object ignoredKeyManager) {
        this(userId, roomId, treeNode);
        logger.warning("⚠️ GroupKeyManager passed but ignored - WebRTC DTLS handles encryption");
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
     * NO ENCRYPTION - WebRTC DTLS already secures the channel
     * 
     * @param rtpPacket Raw RTP packet bytes from GStreamer
     */
    public void sendRtpPacket(byte[] rtpPacket) {
        try {
            // NO ENCRYPTION - just wrap in MediaPacket
            // WebRTC DataChannel DTLS provides transport security
            
            // Create MediaPacket with raw RTP payload
            MediaPacket packet = new MediaPacket(
                MediaPacket.PacketType.VIDEO,
                userId,
                System.currentTimeMillis() * 1000, // Microseconds
                outgoingSequence.incrementAndGet(),
                rtpPacket  // Raw RTP packet, no encryption
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
     * NO DECRYPTION - packet is already in plaintext RTP format
     * (WebRTC DTLS decrypts at DataChannel level)
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
            
            // Ignore own packets (loopback prevention)
            if (packet.getSourceId().equals(userId)) {
                return;
            }
            
            // NO DECRYPTION - payload is already raw RTP
            // WebRTC DataChannel DTLS handles decryption at transport layer
            byte[] rtpPacket = packet.getPayload();
            
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
