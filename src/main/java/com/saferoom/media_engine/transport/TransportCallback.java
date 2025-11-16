package com.saferoom.media_engine.transport;

/**
 * Callback interface for receiving media packets from transport layer
 * 
 * This interface decouples the MediaEngine from the underlying transport
 * mechanism (SDRT, WebRTC, UDP, etc.). When a packet arrives, the transport
 * invokes this callback with the decoded packet data.
 * 
 * Thread Safety: Implementations MUST be thread-safe as callbacks may be
 * invoked from network I/O threads.
 */
@FunctionalInterface
public interface TransportCallback {
    /**
     * Called when a media packet is received from the transport layer
     * 
     * @param data Encoded media data (Opus/VP8 encoded bytes)
     * @param timestamp Media timestamp in milliseconds (capture time)
     * @param sequenceNumber Monotonically increasing packet sequence number
     * @param senderId Unique identifier of the sender (userId in SDRT)
     * @param metadata Optional metadata (can be null). Used for passing
     *                 additional information like key frame markers, quality hints, etc.
     */
    void onPacketReceived(
        byte[] data,
        long timestamp,
        int sequenceNumber,
        String senderId,
        PacketMetadata metadata
    );
    
    /**
     * Metadata associated with a transport packet
     * 
     * This class carries additional information about the packet that may
     * be useful for decoding or playback decisions.
     */
    class PacketMetadata {
        private final boolean isKeyFrame;
        private final int payloadSize;
        private final long receiveTimestamp;
        
        public PacketMetadata(boolean isKeyFrame, int payloadSize, long receiveTimestamp) {
            this.isKeyFrame = isKeyFrame;
            this.payloadSize = payloadSize;
            this.receiveTimestamp = receiveTimestamp;
        }
        
        /**
         * @return true if this is a video key frame (IDR frame for VP8/VP9)
         */
        public boolean isKeyFrame() {
            return isKeyFrame;
        }
        
        /**
         * @return Size of the encoded payload in bytes
         */
        public int getPayloadSize() {
            return payloadSize;
        }
        
        /**
         * @return System timestamp when packet was received (for jitter calculation)
         */
        public long getReceiveTimestamp() {
            return receiveTimestamp;
        }
    }
}
