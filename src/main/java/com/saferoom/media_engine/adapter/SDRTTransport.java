package com.saferoom.media_engine.adapter;

import com.saferoom.media_engine.transport.MediaTransport;
import com.saferoom.media_engine.transport.TransportCallback;
import com.saferoom.media_engine.transport.TransportStats;
import com.saferoom.webrtc.relay.MediaPacket;
import com.saferoom.webrtc.relay.TreeNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDRTTransport bridges the MediaEngine transport abstraction with the SDRT relay tree.
 *
 * Responsibilities:
 * - Wrap encoded media frames into {@link MediaPacket} structures
 * - Forward packets through {@link TreeNode} (parent uplink + children downlink)
 * - Deliver packets arriving from the tree back to the MediaEngine via callbacks
 *
 * Encryption Strategy:
 * - NO application-layer encryption is performed here. WebRTC DataChannels
 *   already provide DTLS transport security, which keeps per-hop latency low
 *   and allows zero-copy forwarding inside the relay network.
 */
public class SDRTTransport implements MediaTransport {

    private static final Logger logger = Logger.getLogger(SDRTTransport.class.getName());
    private static final int UINT16_MASK = 0xFFFF;

    private final String userId;
    private final String roomId;
    private final TreeNode treeNode;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger audioSequence = new AtomicInteger(-1);
    private final AtomicInteger videoSequence = new AtomicInteger(-1);
    private final AtomicInteger metadataSequence = new AtomicInteger(-1);

    private final TransportStats stats = new TransportStats();

    private volatile TransportCallback audioCallback;
    private volatile TransportCallback videoCallback;
    private volatile TransportCallback metadataCallback;
    private volatile MediaTransport.TransportErrorListener errorListener;

    public SDRTTransport(String userId, String roomId, TreeNode treeNode) {
        this.userId = requireNonBlank("userId", userId);
        this.roomId = requireNonBlank("roomId", roomId);
        this.treeNode = Objects.requireNonNull(treeNode, "treeNode cannot be null");

        logger.info(String.format("SDRTTransport created: userId=%s, roomId=%s", userId, roomId));
    }

    @Override
    public void sendAudio(byte[] encodedData, long timestamp, int sequenceNumber)
            throws MediaTransport.TransportException {
        ensureRunning();
        validatePayload(encodedData, "audio");

        int seq = normalizeSequence(sequenceNumber, audioSequence);
        MediaPacket packet = buildPacket(MediaPacket.PacketType.AUDIO, timestamp, seq, encodedData);
        forwardPacket(packet);

        stats.recordAudioPacketSent(encodedData.length);
    }

    @Override
    public void sendVideo(byte[] encodedData, long timestamp, int sequenceNumber, boolean isKeyFrame)
            throws MediaTransport.TransportException {
        ensureRunning();
        validatePayload(encodedData, "video");

        int seq = normalizeSequence(sequenceNumber, videoSequence);
        MediaPacket packet = buildPacket(MediaPacket.PacketType.VIDEO, timestamp, seq, encodedData);
        forwardPacket(packet);

        stats.recordVideoPacketSent(encodedData.length);
        if (isKeyFrame) {
            logger.fine(String.format("Sent key frame: seq=%d size=%d bytes", seq, encodedData.length));
        }
    }

    @Override
    public void sendMetadata(String jsonMetadata, long timestamp) throws MediaTransport.TransportException {
        ensureRunning();
        if (jsonMetadata == null || jsonMetadata.isEmpty()) {
            throw new MediaTransport.TransportException("jsonMetadata cannot be null or empty");
        }

        int seq = normalizeSequence(-1, metadataSequence);
        byte[] payload = jsonMetadata.getBytes(StandardCharsets.UTF_8);
        MediaPacket packet = buildPacket(MediaPacket.PacketType.CONTROL, timestamp, seq, payload);
        forwardPacket(packet);
    }

    @Override
    public void setOnAudioReceived(TransportCallback callback) {
        this.audioCallback = callback;
    }

    @Override
    public void setOnVideoReceived(TransportCallback callback) {
        this.videoCallback = callback;
    }

    @Override
    public void setOnMetadataReceived(TransportCallback callback) {
        this.metadataCallback = callback;
    }

    @Override
    public void start() throws MediaTransport.TransportException {
        if (!running.compareAndSet(false, true)) {
            throw new MediaTransport.TransportException("Transport is already running");
        }

        treeNode.setPacketCallback(this::onPacketFromTree);
        audioSequence.set(-1);
        videoSequence.set(-1);
        metadataSequence.set(-1);
        stats.reset();

        logger.info(String.format("SDRTTransport started: userId=%s, roomId=%s", userId, roomId));
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            treeNode.setPacketCallback(null);
            logger.info(String.format("SDRTTransport stopped: userId=%s, roomId=%s, stats=%s",
                    userId, roomId, stats));
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public TransportStats getStats() {
        return stats;
    }

    @Override
    public void resetStats() {
        stats.reset();
        logger.info("Transport statistics reset");
    }

    @Override
    public void setErrorListener(MediaTransport.TransportErrorListener listener) {
        this.errorListener = listener;
    }

    private void onPacketFromTree(MediaPacket packet) {
        if (!running.get() || packet == null) {
            return;
        }

        if (!roomId.equals(packet.getRoomId())) {
            logger.warning(String.format("Dropping packet for wrong room: expected=%s, got=%s",
                    roomId, packet.getRoomId()));
            return;
        }

        if (userId.equals(packet.getSourceId())) {
            // Ignore our own packets (local echo)
            return;
        }

        byte[] payload = packet.getPayload();
        if (payload == null || payload.length == 0) {
            logger.fine("Dropping packet with empty payload");
            return;
        }

        TransportCallback.PacketMetadata metadata =
                new TransportCallback.PacketMetadata(
                        packet.getType() == MediaPacket.PacketType.VIDEO && isLikelyKeyFrame(payload),
                        payload.length,
                        System.currentTimeMillis());

        try {
            switch (packet.getType()) {
                case AUDIO:
                    stats.recordAudioPacketReceived(payload.length);
                    deliver(audioCallback, payload, packet, metadata);
                    break;
                case VIDEO:
                    stats.recordVideoPacketReceived(payload.length);
                    deliver(videoCallback, payload, packet, metadata);
                    break;
                case CONTROL:
                    deliver(metadataCallback, payload, packet, metadata);
                    break;
                case HEARTBEAT:
                case KEY_ROTATION:
                    logger.fine(String.format("Received %s packet from %s",
                            packet.getType(), packet.getSourceId()));
                    break;
                default:
                    logger.warning(String.format("Unknown packet type: %s", packet.getType()));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to process packet from tree", e);
            notifyError(MediaTransport.TransportError.INVALID_PACKET,
                    "Packet processing failed: " + e.getMessage());
        }
    }

    private void deliver(TransportCallback callback,
                         byte[] payload,
                         MediaPacket packet,
                         TransportCallback.PacketMetadata metadata) {
        if (callback != null) {
            callback.onPacketReceived(
                    payload,
                    packet.getTimestamp(),
                    packet.getSequenceNumber(),
                    packet.getSourceId(),
                    metadata);
        }
    }

    private MediaPacket buildPacket(MediaPacket.PacketType type,
                                    long timestamp,
                                    int sequenceNumber,
                                    byte[] payload) {
        MediaPacket packet = new MediaPacket(type, userId, timestamp, sequenceNumber, payload);
        packet.setRoomId(roomId);
        return packet;
    }

    private void forwardPacket(MediaPacket packet) throws MediaTransport.TransportException {
        try {
            if (treeNode.getParent() != null) {
                treeNode.sendToParent(packet);
            } else if (treeNode.getRole() == TreeNode.NodeRole.LEAF) {
                logger.warning("Leaf node has no parent; packet cannot be forwarded upstream");
            }

            treeNode.processPacket(packet, userId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to forward MediaPacket", e);
            notifyError(MediaTransport.TransportError.INTERNAL_ERROR,
                    "Packet forward failed: " + e.getMessage());
            throw new MediaTransport.TransportException("Failed to forward packet", e);
        }
    }

    private void ensureRunning() throws MediaTransport.TransportException {
        if (!running.get()) {
            throw new MediaTransport.TransportException("Transport is not running");
        }
    }

    private void validatePayload(byte[] payload, String kind) throws MediaTransport.TransportException {
        if (payload == null || payload.length == 0) {
            throw new MediaTransport.TransportException(kind + " payload cannot be null or empty");
        }
    }

    private int normalizeSequence(int requested, AtomicInteger counter) {
        if (requested >= 0) {
            return requested & UINT16_MASK;
        }
        return counter.updateAndGet(current -> (current + 1) & UINT16_MASK);
    }

    private boolean isLikelyKeyFrame(byte[] payload) {
        // TODO: Parse VP8/VP9 bitstream for actual key frame detection.
        // For now, always return false so downstream components treat it as delta frame.
        return false;
    }

    private void notifyError(MediaTransport.TransportError error, String message) {
        MediaTransport.TransportErrorListener listener = errorListener;
        if (listener != null) {
            try {
                listener.onTransportError(error, message);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error listener threw exception", e);
            }
        }
    }

    private static String requireNonBlank(String field, String value) {
        Objects.requireNonNull(value, field + " cannot be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return value;
    }
}

