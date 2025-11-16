package com.saferoom.media_engine.processor;

/**
 * Base interface for codec components (encoders/decoders).
 *
 * <p>Concrete implementations are responsible for managing underlying resources
 * (e.g., GStreamer pipelines, hardware codecs) and must be thread-safe.</p>
 */
public interface MediaProcessor {

    /**
     * Initializes the processor and allocates resources.
     */
    void start() throws Exception;

    /**
     * Stops processing and releases resources. Implementations must be idempotent.
     */
    void stop();

    /**
     * @return true if the processor has been started.
     */
    default boolean isRunning() {
        return false;
    }
}

