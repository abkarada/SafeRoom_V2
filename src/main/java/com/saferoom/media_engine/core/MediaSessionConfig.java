package com.saferoom.media_engine.core;

/**
 * Immutable configuration for {@link MediaSession}.
 */
public final class MediaSessionConfig {

    public enum AudioCodec {
        OPUS
    }

    public enum VideoCodec {
        VP8,
        VP9
    }

    private final AudioCodec audioCodec;
    private final VideoCodec videoCodec;
    private final int audioSampleRate;
    private final int audioChannels;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoFps;
    private final int audioBitrateKbps;
    private final int videoBitrateKbps;
    private final boolean audioEnabled;
    private final boolean videoEnabled;

    private MediaSessionConfig(Builder builder) {
        this.audioCodec = builder.audioCodec;
        this.videoCodec = builder.videoCodec;
        this.audioSampleRate = builder.audioSampleRate;
        this.audioChannels = builder.audioChannels;
        this.videoWidth = builder.videoWidth;
        this.videoHeight = builder.videoHeight;
        this.videoFps = builder.videoFps;
        this.audioBitrateKbps = builder.audioBitrateKbps;
        this.videoBitrateKbps = builder.videoBitrateKbps;
        this.audioEnabled = builder.audioEnabled;
        this.videoEnabled = builder.videoEnabled;
    }

    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    public int getAudioSampleRate() {
        return audioSampleRate;
    }

    public int getAudioChannels() {
        return audioChannels;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public int getVideoFps() {
        return videoFps;
    }

    public int getAudioBitrateKbps() {
        return audioBitrateKbps;
    }

    public int getVideoBitrateKbps() {
        return videoBitrateKbps;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AudioCodec audioCodec = AudioCodec.OPUS;
        private VideoCodec videoCodec = VideoCodec.VP8;
        private int audioSampleRate = 48_000;
        private int audioChannels = 1;
        private int videoWidth = 1280;
        private int videoHeight = 720;
        private int videoFps = 30;
        private int audioBitrateKbps = 32;
        private int videoBitrateKbps = 1500;
        private boolean audioEnabled = true;
        private boolean videoEnabled = true;

        public Builder audioCodec(AudioCodec codec) {
            this.audioCodec = codec;
            return this;
        }

        public Builder videoCodec(VideoCodec codec) {
            this.videoCodec = codec;
            return this;
        }

        public Builder audioSampleRate(int sampleRate) {
            this.audioSampleRate = sampleRate;
            return this;
        }

        public Builder audioChannels(int channels) {
            this.audioChannels = channels;
            return this;
        }

        public Builder videoWidth(int width) {
            this.videoWidth = width;
            return this;
        }

        public Builder videoHeight(int height) {
            this.videoHeight = height;
            return this;
        }

        public Builder videoFps(int fps) {
            this.videoFps = fps;
            return this;
        }

        public Builder audioBitrateKbps(int bitrate) {
            this.audioBitrateKbps = bitrate;
            return this;
        }

        public Builder videoBitrateKbps(int bitrate) {
            this.videoBitrateKbps = bitrate;
            return this;
        }

        public Builder audioEnabled(boolean enabled) {
            this.audioEnabled = enabled;
            return this;
        }

        public Builder videoEnabled(boolean enabled) {
            this.videoEnabled = enabled;
            return this;
        }

        public MediaSessionConfig build() {
            return new MediaSessionConfig(this);
        }
    }
}

