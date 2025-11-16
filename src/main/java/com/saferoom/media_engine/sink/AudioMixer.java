package com.saferoom.media_engine.sink;

import com.saferoom.media_engine.processor.MediaFrame;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Minimal audio mixer placeholder that forwards frames to a single output sink.
 *
 * <p>The real implementation can evolve to perform Opus frame alignment, gain control,
 * and summing. For now it ensures there is a consistent place to plug in mixing logic
 * inside {@link com.saferoom.media_engine.core.MediaSession}.</p>
 */
public class AudioMixer {

    private static final Logger logger = Logger.getLogger(AudioMixer.class.getName());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentMap<String, Long> participantActivity = new ConcurrentHashMap<>();
    private MediaSink outputSink;

    public AudioMixer(MediaSink outputSink) {
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink cannot be null");
    }

    public void setOutputSink(MediaSink outputSink) {
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink cannot be null");
    }

    public void start() {
        running.set(true);
        participantActivity.clear();
        logger.info("AudioMixer started");
    }

    public void stop() {
        running.set(false);
        participantActivity.clear();
        logger.info("AudioMixer stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Adds a decoded audio frame to the mix. Currently it simply forwards the audio
     * to the configured output sink while tracking basic activity timestamps.
     */
    public void addFrame(String participantId, MediaFrame frame) {
        if (!running.get() || outputSink == null) {
            return;
        }
        participantActivity.put(participantId, System.currentTimeMillis());
        outputSink.render(participantId, frame);
    }
}

