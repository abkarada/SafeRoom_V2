package com.saferoom.media_engine.processor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Container for raw media data flowing between capture/encode/decode stages.
 *
 * <p>The MediaEngine treats audio and video frames uniformly via this class so that
 * transports, processors, and mixers can exchange data without depending on the
 * underlying capture implementation (GStreamer, screen share, etc.).</p>
 */
public final class MediaFrame {

    /**
     * Distinguishes whether the frame carries audio or video samples.
     */
    public enum Type {
        AUDIO,
        VIDEO
    }

    private final Type type;
    private final byte[] data;
    private final long timestamp;

    // Audio metadata
    private final int sampleRate;
    private final int channels;

    // Video metadata
    private final int width;
    private final int height;

    private MediaFrame(Type type,
                       byte[] data,
                       long timestamp,
                       int sampleRate,
                       int channels,
                       int width,
                       int height) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.timestamp = timestamp;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.width = width;
        this.height = height;
    }

    public static MediaFrame audioFrame(byte[] pcmData, long timestamp, int sampleRate, int channels) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive");
        }
        return new MediaFrame(Type.AUDIO, pcmData, timestamp, sampleRate, channels, 0, 0);
    }

    public static MediaFrame videoFrame(byte[] rawFrame, long timestamp, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
        return new MediaFrame(Type.VIDEO, rawFrame, timestamp, 0, 0, width, height);
    }

    public Type getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return "MediaFrame{" +
                "type=" + type +
                ", data=" + data.length + " bytes" +
                ", timestamp=" + timestamp +
                ", sampleRate=" + sampleRate +
                ", channels=" + channels +
                ", width=" + width +
                ", height=" + height +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaFrame)) return false;
        MediaFrame that = (MediaFrame) o;
        return timestamp == that.timestamp
                && sampleRate == that.sampleRate
                && channels == that.channels
                && width == that.width
                && height == that.height
                && type == that.type
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, timestamp, sampleRate, channels, width, height);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}

