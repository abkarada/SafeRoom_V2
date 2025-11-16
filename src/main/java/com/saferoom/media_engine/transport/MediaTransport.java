package com.saferoom.media_engine.transport;

/**
 * Transport abstraction interface for media packet transmission
 * 
 * This interface decouples the MediaEngine from the underlying transport
 * mechanism (SDRT tree relay, WebRTC data channels, UDP sockets, etc.).
 * 
 * Design Philosophy:
 * - MediaEngine knows nothing about tree topology, encryption, or routing
 * - Transport implementation handles all network-layer concerns
 * - Simple byte[] in/out interface for maximum flexibility
 * 
 * Thread Safety:
 * - All methods MUST be thread-safe
 * - send*() methods may be called from encoder threads
 * - Callbacks may be invoked from network I/O threads
 * 
 * Lifecycle:
 * 1. Create transport instance
 * 2. Register callbacks with setOn*Received()
 * 3. Call start() to begin packet transmission/reception
 * 4. Call send*() methods to transmit media
 * 5. Call stop() to gracefully shutdown
 */
public interface MediaTransport {
    
    // --- Packet Transmission ---
    
    /**
     * Send encoded audio packet
     * 
     * This method should be NON-BLOCKING. If the transport layer has internal
     * buffering/queueing, this method should enqueue the packet and return immediately.
     * 
     * @param encodedData Opus-encoded audio data (typically 20-60ms of audio)
     * @param timestamp Media timestamp in milliseconds (capture time, monotonic)
     * @param sequenceNumber Monotonically increasing packet sequence number (uint16 wrapping)
     * @throws TransportException if packet cannot be sent (e.g., transport closed)
     */
    void sendAudio(byte[] encodedData, long timestamp, int sequenceNumber) throws TransportException;
    
    /**
     * Send encoded video packet
     * 
     * Video packets may be larger than audio packets (up to several KB for key frames).
     * Transport implementation should handle fragmentation if needed.
     * 
     * @param encodedData VP8/VP9 encoded video data (single frame or frame fragment)
     * @param timestamp Media timestamp in milliseconds (capture time, monotonic)
     * @param sequenceNumber Monotonically increasing packet sequence number (uint16 wrapping)
     * @param isKeyFrame true if this is a key frame (IDR frame), false for delta frames
     * @throws TransportException if packet cannot be sent
     */
    void sendVideo(byte[] encodedData, long timestamp, int sequenceNumber, boolean isKeyFrame) 
        throws TransportException;
    
    /**
     * Send metadata packet (JSON format)
     * 
     * Used for signaling events like mute/unmute, resolution changes, quality adjustments.
     * Format: UTF-8 encoded JSON string
     * 
     * @param jsonMetadata JSON string (e.g., {"event": "mute", "type": "audio"})
     * @param timestamp Timestamp of the event
     * @throws TransportException if packet cannot be sent
     */
    void sendMetadata(String jsonMetadata, long timestamp) throws TransportException;
    
    // --- Packet Reception (Callbacks) ---
    
    /**
     * Register callback for received audio packets
     * 
     * The callback will be invoked on a transport-internal thread (network I/O thread).
     * Callback implementation should be fast and non-blocking (e.g., enqueue to a buffer).
     * 
     * @param callback Audio packet callback (null to unregister)
     */
    void setOnAudioReceived(TransportCallback callback);
    
    /**
     * Register callback for received video packets
     * 
     * @param callback Video packet callback (null to unregister)
     */
    void setOnVideoReceived(TransportCallback callback);
    
    /**
     * Register callback for received metadata packets
     * 
     * @param callback Metadata packet callback (null to unregister)
     */
    void setOnMetadataReceived(TransportCallback callback);
    
    // --- Lifecycle Management ---
    
    /**
     * Start the transport layer
     * 
     * After calling this method:
     * - send*() methods become operational
     * - Callbacks will be invoked when packets arrive
     * 
     * @throws TransportException if transport cannot be started (e.g., network unavailable)
     */
    void start() throws TransportException;
    
    /**
     * Stop the transport layer gracefully
     * 
     * This method should:
     * - Flush any pending outgoing packets
     * - Stop invoking callbacks
     * - Release network resources
     * 
     * After calling stop(), send*() methods will throw TransportException.
     */
    void stop();
    
    /**
     * Check if transport is currently running
     * 
     * @return true if start() was called and stop() has not been called yet
     */
    boolean isRunning();
    
    // --- Statistics and Monitoring ---
    
    /**
     * Get current transport statistics
     * 
     * @return Snapshot of transport statistics (packets sent/received, loss, jitter, etc.)
     */
    TransportStats getStats();
    
    /**
     * Reset transport statistics to zero
     */
    void resetStats();
    
    // --- Error Handling ---
    
    /**
     * Register callback for transport errors
     * 
     * Examples: network disconnection, encryption failure, congestion
     * 
     * @param listener Error listener (null to unregister)
     */
    void setErrorListener(TransportErrorListener listener);
    
    /**
     * Listener interface for transport-level errors
     */
    interface TransportErrorListener {
        /**
         * Called when a transport error occurs
         * 
         * @param error Error type
         * @param message Human-readable error message
         */
        void onTransportError(TransportError error, String message);
    }
    
    /**
     * Transport error types
     */
    enum TransportError {
        /** Network connection lost */
        NETWORK_DISCONNECTED,
        
        /** Packet encryption/decryption failed */
        ENCRYPTION_FAILURE,
        
        /** Network congestion detected (high packet loss) */
        CONGESTION_DETECTED,
        
        /** Invalid packet received (malformed data) */
        INVALID_PACKET,
        
        /** Transport layer internal error */
        INTERNAL_ERROR
    }
    
    /**
     * Transport exception for send/start failures
     */
    class TransportException extends Exception {
        public TransportException(String message) {
            super(message);
        }
        
        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
