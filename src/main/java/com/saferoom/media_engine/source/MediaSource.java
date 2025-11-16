package com.saferoom.media_engine.source;

import com.saferoom.media_engine.processor.MediaFrame;

/**
 * Abstraction over media capture pipelines (camera, microphone, screen, etc.).
 */
public interface MediaSource {

    /**
     * Starts capture.
     */
    void start() throws Exception;

    /**
     * Stops capture gracefully.
     */
    void stop();

    /**
     * Registers a callback for captured frames.
     *
     * @param callback Consumer invoked for every captured frame
     */
    void setFrameCallback(FrameCallback callback);

    /**
     * @return true if the source is actively capturing.
     */
    default boolean isRunning() {
        return false;
    }

    @FunctionalInterface
    interface FrameCallback {
        void onFrame(MediaFrame frame);
    }
}

