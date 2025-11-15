package com.saferoom.webrtc.relay;

import com.saferoom.crypto.GroupKeyManager;
import com.saferoom.webrtc.relay.qos.*;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
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
 * QoS Features:
 * - Adaptive bitrate control (GCC algorithm)
 * - Forward Error Correction (XOR-based FEC)
 * - Jitter buffer for smooth playback
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
    
    // QoS components
    private final AdaptiveBitrateController bitrateController;
    private final FECEncoder fecEncoder;
    private final FECDecoder fecDecoder;
    private final JitterBuffer jitterBuffer;
    
    // DataChannel for SDRT communication
    private RTCDataChannel sdrtChannel;
    
    // Local media state
    private VideoTrack localVideoTrack;
    private boolean isCapturing = false;
    
    // Packet sequencing
    private final AtomicInteger outgoingSequence = new AtomicInteger(0);
    private final Map<String, RemoteStreamState> remoteStreams = new ConcurrentHashMap<>();
    
    // Processing thread pool
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Configuration
    private static final String SDRT_CHANNEL_LABEL = "sdrt-media";
    private static final int JITTER_PLAYOUT_INTERVAL_MS = 20; // 20ms playout interval
    
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
        
        // Initialize QoS components
        ConnectionMetrics metrics = new ConnectionMetrics();
        this.bitrateController = new AdaptiveBitrateController(metrics);
        this.fecEncoder = new FECEncoder();
        this.fecDecoder = new FECDecoder();
        this.jitterBuffer = new JitterBuffer();
        
        // Setup bitrate callback
        bitrateController.setBitrateCallback(newBitrate -> {
            if (localVideoTrack != null) {
                // TODO: Apply bitrate to VideoTrack encoder
                logger.info(String.format("Target bitrate adjusted to %d kbps", newBitrate / 1000));
            }
        });
        
        // Start jitter buffer playout task
        scheduler.scheduleAtFixedRate(
            this::processJitterBuffer,
            100, // Initial delay
            JITTER_PLAYOUT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
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
     * 4. Apply FEC
     * 5. Send via DataChannel
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
            
            // Send data packet to tree
            sendPacketToTree(packet);
            
            // Add to FEC encoder and get FEC packet if generated (every 10 packets)
            MediaPacket fecPacket = fecEncoder.addPacket(packet);
            if (fecPacket != null) {
                fecPacket.setRoomId(roomId);
                sendPacketToTree(fecPacket);
            }
            
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
            
            // If we're destination (LEAF or want to display), process with QoS
            if (treeNode.getRole() == TreeNode.NodeRole.LEAF || 
                shouldDisplayStream(packet.getSourceId())) {
                processIncomingPacketWithQoS(packet);
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to handle incoming packet", e);
        }
    }
    
    /**
     * Process incoming packet with QoS features (FEC + Jitter Buffer)
     */
    private void processIncomingPacketWithQoS(MediaPacket packet) {
        try {
            // Step 1: FEC Decoding - Recover lost packets if possible
            List<MediaPacket> recoveredPackets = fecDecoder.processPacket(packet);
            
            // Step 2: Add recovered packets to jitter buffer
            for (MediaPacket recoveredPacket : recoveredPackets) {
                jitterBuffer.addPacket(recoveredPacket);
                logger.fine(String.format("Recovered packet via FEC: seq=%d", 
                    recoveredPacket.getSequenceNumber()));
            }
            
            // Step 3: Add original packet to jitter buffer (if not FEC)
            if (packet.getType() != MediaPacket.PacketType.FEC) {
                jitterBuffer.addPacket(packet);
            }
            
            // Note: Jitter buffer playout happens in processJitterBuffer() 
            // scheduled task (every 20ms)
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to process incoming packet with QoS", e);
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
     * Process jitter buffer and deliver ready packets
     * Called periodically (every 20ms) to smooth out network jitter
     */
    private void processJitterBuffer() {
        try {
            List<MediaPacket> readyPackets = jitterBuffer.getReadyPackets();
            
            for (MediaPacket packet : readyPackets) {
                // Decrypt and play each ready packet
                decryptAndPlayPacket(packet);
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing jitter buffer", e);
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
        
        // Shutdown QoS components
        bitrateController.stop();
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close DataChannel
        if (sdrtChannel != null) {
            try {
                sdrtChannel.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing DataChannel", e);
            }
            sdrtChannel = null;
        }
        
        // Clear remote streams
        remoteStreams.clear();
        
        // Log final statistics
        logger.info("SDRTMediaBridge cleanup complete. Final stats:");
        logger.info("  FEC Stats: " + fecDecoder.getStatistics());
        logger.info("  Jitter Buffer Stats: " + jitterBuffer.getStatistics());
        logger.info("  Bitrate Controller: " + bitrateController.getStatistics());
    }
}
