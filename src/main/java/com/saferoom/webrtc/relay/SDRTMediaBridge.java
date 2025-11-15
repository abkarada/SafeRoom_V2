package com.saferoom.webrtc.relay;

import com.saferoom.crypto.GroupKeyManager;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDRTMediaBridge - Bridges WebRTC media pipeline with SDRT tree topology
 * 
 * This class handles:
 * 1. Capturing encoded RTP packets from local VideoTrack
 * 2. Encrypting packets with group key
 * 3. Wrapping in MediaPacket and sending via DataChannel
 * 4. Receiving MediaPackets from DataChannel
 * 5. Decrypting and injecting back into MediaStream for playback
 * 
 * KEY CONCEPT (Google Hangouts Model):
 * - WebRTC encoder produces RTP packets (H264/VP8)
 * - We intercept these packets AFTER encoding
 * - Send via DataChannel (no decode/re-encode at relay nodes)
 * - Receiver gets RTP packets and feeds to WebRTC decoder
 * 
 * This achieves multi-hop forwarding WITHOUT decode/re-encode overhead!
 */
public class SDRTMediaBridge {
    
    private static final Logger logger = Logger.getLogger(SDRTMediaBridge.class.getName());
    
    // Core components
    private final String userId;
    private final String roomId;
    private final TreeNode treeNode;
    private final GroupKeyManager keyManager;
    
    // DataChannel for SDRT communication
    private RTCDataChannel sdrtChannel;
    
    // Local media state
    private VideoTrack localVideoTrack;
    private boolean isCapturing = false;
    
    // Packet sequencing
    private final AtomicInteger outgoingSequence = new AtomicInteger(0);
    private final Map<String, RemoteStreamState> remoteStreams = new ConcurrentHashMap<>();
    
    // Configuration
    private static final String SDRT_CHANNEL_LABEL = "sdrt-media";
    private static final int MAX_PACKET_SIZE = 1400; // MTU-safe size
    
    /**
     * Remote stream state tracking
     */
    private static class RemoteStreamState {
        VideoTrack videoTrack;
        int lastSequence = -1;
        long lastPacketTime = 0;
        
        RemoteStreamState(VideoTrack track) {
            this.videoTrack = track;
        }
    }
    
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
     * Create SDRT DataChannel on peer connection
     * 
     * @param peerConnection WebRTC peer connection
     */
    public void createSDRTDataChannel(RTCPeerConnection peerConnection) {
        if (sdrtChannel != null) {
            logger.warning("SDRT DataChannel already exists");
            return;
        }
        
        try {
            // Configure DataChannel for media transport
            RTCDataChannelInit config = new RTCDataChannelInit();
            config.ordered = false;           // UDP-like (don't wait for retransmits)
            config.maxRetransmits = 0;        // No retransmission (real-time priority)
            config.negotiated = false;        // Use standard negotiation
            
            // Create channel
            sdrtChannel = peerConnection.createDataChannel(SDRT_CHANNEL_LABEL, config);
            
            // Set up event handlers
            sdrtChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // Monitor buffer for flow control
                    if (sdrtChannel.getBufferedAmount() > 1024 * 1024) { // 1MB
                        logger.warning("SDRT DataChannel buffer high: " + 
                                     sdrtChannel.getBufferedAmount());
                    }
                }
                
                @Override
                public void onStateChange() {
                    logger.info("SDRT DataChannel state: " + sdrtChannel.getState());
                    
                    if (sdrtChannel.getState() == RTCDataChannelState.OPEN) {
                        onSDRTChannelOpen();
                    } else if (sdrtChannel.getState() == RTCDataChannelState.CLOSED) {
                        onSDRTChannelClosed();
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    handleIncomingPacket(buffer);
                }
            });
            
            logger.info("SDRT DataChannel created successfully");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create SDRT DataChannel", e);
        }
    }
    
    /**
     * Start capturing local video stream and sending via SDRT
     * 
     * @param videoTrack Local video track from camera/screen
     */
    public void startCapture(VideoTrack videoTrack) {
        if (isCapturing) {
            logger.warning("Already capturing");
            return;
        }
        
        this.localVideoTrack = videoTrack;
        this.isCapturing = true;
        
        // Add video sink to capture frames
        // Note: Actual frame capture requires native integration
        // For now, this is a placeholder for the architecture
        // videoTrack.addSink(frame -> captureAndForwardFrame(frame));
        
        logger.info("Started capturing local video for SDRT (placeholder)");
    }
    
    /**
     * Stop capturing local video
     */
    public void stopCapture() {
        if (!isCapturing) {
            return;
        }
        
        isCapturing = false;
        
        // Remove sink
        if (localVideoTrack != null) {
            // Note: webrtc-java doesn't have removeSink, sink will be GC'd
            localVideoTrack = null;
        }
        
        logger.info("Stopped capturing local video");
    }
    
    /**
     * Capture frame and forward via SDRT tree
     * 
     * This is where the magic happens:
     * 1. WebRTC has already encoded the frame (H264/VP8)
     * 2. We extract the encoded data
     * 3. Encrypt it
     * 4. Send via DataChannel
     */
    private void captureAndForwardFrame(VideoFrame frame) {
        if (sdrtChannel == null || sdrtChannel.getState() != RTCDataChannelState.OPEN) {
            return; // Channel not ready
        }
        
        try {
            // Extract encoded frame data
            // Note: In real implementation, we'd need to access the encoded RTP packet
            // For now, we'll simulate this with frame buffer data
            ByteBuffer frameBuffer = extractEncodedData(frame);
            
            if (frameBuffer == null || frameBuffer.remaining() == 0) {
                return;
            }
            
            // Create RTP-like packet structure
            byte[] rtpPacket = new byte[frameBuffer.remaining()];
            frameBuffer.get(rtpPacket);
            
            // Encrypt packet
            byte[] encryptedPayload = keyManager.encrypt(roomId, rtpPacket);
            
            // Create MediaPacket
            MediaPacket packet = new MediaPacket(
                MediaPacket.PacketType.VIDEO,
                userId,
                System.currentTimeMillis() * 1000, // Convert to microseconds
                outgoingSequence.incrementAndGet(),
                encryptedPayload
            );
            packet.setRoomId(roomId);
            
            // Send to tree (parent or children depending on role)
            sendPacketToTree(packet);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to capture and forward frame", e);
        }
    }
    
    /**
     * Extract encoded data from VideoFrame
     * 
     * NOTE: This is a placeholder for the actual RTP packet interception.
     * In production, this would use native JNI hooks to intercept encoded
     * RTP packets directly from the WebRTC encoder pipeline.
     * 
     * The proper implementation would:
     * 1. Hook into PeerConnection's RTP sender
     * 2. Intercept encoded packets before SRTP encryption
     * 3. Extract RTP header + payload
     * 4. Return as ByteBuffer for SDRT forwarding
     * 
     * This requires native code integration (C++/JNI) which is beyond
     * the scope of this initial implementation.
     */
    private ByteBuffer extractEncodedData(VideoFrame frame) {
        // Placeholder: Return null for now
        // Real implementation requires native integration
        logger.finest("RTP packet extraction placeholder (native integration needed)");
        return null;
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
                // TreeNode will handle broadcasting to children
                break;
        }
    }
    
    /**
     * Handle incoming packet from DataChannel
     */
    private void handleIncomingPacket(RTCDataChannelBuffer buffer) {
        try {
            // Deserialize MediaPacket
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            
            MediaPacket packet = MediaPacket.fromBytes(data);
            packet.setRoomId(roomId);
            
            // Validate room
            if (!roomId.equals(packet.getRoomId())) {
                logger.warning("Packet from wrong room: " + packet.getRoomId());
                return;
            }
            
            // Process based on role and packet source
            if (packet.getSourceId().equals(userId)) {
                // Ignore own packets (loopback prevention)
                return;
            }
            
            // Forward via tree (TreeNode handles routing)
            treeNode.processPacket(packet, packet.getSourceId());
            
            // If we're destination (LEAF or want to display), decrypt and play
            if (treeNode.getRole() == TreeNode.NodeRole.LEAF || 
                shouldDisplayStream(packet.getSourceId())) {
                decryptAndPlayPacket(packet);
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to handle incoming packet", e);
        }
    }
    
    /**
     * Decrypt packet and inject into video playback
     */
    private void decryptAndPlayPacket(MediaPacket packet) {
        try {
            // Decrypt payload
            byte[] rtpPacket = keyManager.decrypt(roomId, packet.getPayload());
            
            // Get or create remote stream state
            String sourceId = packet.getSourceId();
            RemoteStreamState streamState = remoteStreams.get(sourceId);
            
            if (streamState == null) {
                logger.fine("No remote stream for source: " + sourceId);
                return;
            }
            
            // Update sequence tracking
            streamState.lastSequence = packet.getSequenceNumber();
            streamState.lastPacketTime = System.currentTimeMillis();
            
            // Inject RTP packet into VideoTrack
            // TODO: This requires native integration with MediaStream API
            // For now, we log that packet was received
            logger.fine(String.format("Received video packet: source=%s, seq=%d, size=%d",
                                     sourceId, packet.getSequenceNumber(), rtpPacket.length));
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to decrypt and play packet", e);
        }
    }
    
    /**
     * Register remote video track for a participant
     */
    public void registerRemoteStream(String sourceUserId, VideoTrack videoTrack) {
        remoteStreams.put(sourceUserId, new RemoteStreamState(videoTrack));
        logger.info("Registered remote stream: " + sourceUserId);
    }
    
    /**
     * Unregister remote stream
     */
    public void unregisterRemoteStream(String sourceUserId) {
        remoteStreams.remove(sourceUserId);
        logger.info("Unregistered remote stream: " + sourceUserId);
    }
    
    /**
     * Check if we should display this stream (for monitoring/debugging)
     */
    private boolean shouldDisplayStream(String sourceId) {
        return remoteStreams.containsKey(sourceId);
    }
    
    /**
     * DataChannel opened callback
     */
    private void onSDRTChannelOpen() {
        logger.info("SDRT DataChannel opened - tree communication active");
        
        // Send initial heartbeat
        sendHeartbeat();
    }
    
    /**
     * DataChannel closed callback
     */
    private void onSDRTChannelClosed() {
        logger.warning("SDRT DataChannel closed - tree communication lost");
        stopCapture();
    }
    
    /**
     * Send heartbeat packet
     */
    private void sendHeartbeat() {
        if (sdrtChannel == null || sdrtChannel.getState() != RTCDataChannelState.OPEN) {
            return;
        }
        
        try {
            MediaPacket heartbeat = new MediaPacket(
                MediaPacket.PacketType.HEARTBEAT,
                userId,
                System.currentTimeMillis() * 1000,
                0,
                new byte[0]
            );
            heartbeat.setRoomId(roomId);
            
            byte[] data = heartbeat.toBytes();
            RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(
                ByteBuffer.wrap(data), true);
            sdrtChannel.send(buffer);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send heartbeat", e);
        }
    }
    
    /**
     * Get current DataChannel state
     */
    public RTCDataChannelState getChannelState() {
        return sdrtChannel != null ? sdrtChannel.getState() : RTCDataChannelState.CLOSED;
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        logger.info("Cleaning up SDRTMediaBridge");
        
        stopCapture();
        
        if (sdrtChannel != null) {
            try {
                sdrtChannel.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing DataChannel", e);
            }
            sdrtChannel = null;
        }
        
        remoteStreams.clear();
    }
}
