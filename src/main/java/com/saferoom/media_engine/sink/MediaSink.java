package com.saferoom.media_engine.sink;

import com.saferoom.media_engine.processor.MediaFrame;

/**
 * Media sink abstraction for playback/rendering components.
 */
public interface MediaSink {

    void start() throws Exception;

    void stop();

    void render(String participantId, MediaFrame frame);

    default boolean isRunning() {
        return false;
    }
}

