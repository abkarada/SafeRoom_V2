package com.saferoom.webrtc.relay;

import com.saferoom.media_engine.adapter.SDRTTransport;
import com.saferoom.media_engine.core.MediaSession;
import com.saferoom.media_engine.core.MediaSessionConfig;
import com.saferoom.media_engine.core.MediaSessionListener;
import com.saferoom.media_engine.processor.AudioDecoder;
import com.saferoom.media_engine.processor.AudioEncoder;
import com.saferoom.media_engine.processor.VideoDecoder;
import com.saferoom.media_engine.processor.VideoEncoder;
import com.saferoom.media_engine.sink.AudioMixer;
import com.saferoom.media_engine.sink.MediaSink;
import com.saferoom.media_engine.source.MediaSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level bridge that wires the SDRT tree to the media engine.
 *
 * <p>It encapsulates {@link SDRTTransport}, ensures packets flow through the tree, and
 * instantiates a {@link MediaSession} with the provided media components.</p>
 */
public class SDRTMediaBridge {

    private static final Logger logger = Logger.getLogger(SDRTMediaBridge.class.getName());

    private final String userId;
    private final String roomId;
    private final TreeNode treeNode;
    private final MediaSessionConfig config;
    private final MediaComponents components;

    private SDRTTransport transport;
    private MediaSession mediaSession;

    public SDRTMediaBridge(String userId,
                           String roomId,
                           TreeNode treeNode) {
        this(userId, roomId, treeNode, MediaSessionConfig.builder().build(), MediaComponents.builder().build());
    }

    /**
     * Backward compatible constructor that ignores legacy encryption managers.
     */
    public SDRTMediaBridge(String userId,
                           String roomId,
                           TreeNode treeNode,
                           Object ignoredKeyManager) {
        this(userId, roomId, treeNode);
        logger.warning("GroupKeyManager is no longer used. WebRTC DTLS handles encryption.");
    }

    public SDRTMediaBridge(String userId,
                           String roomId,
                           TreeNode treeNode,
                           MediaSessionConfig config,
                           MediaComponents components) {
        this.userId = requireNonBlank("userId", userId);
        this.roomId = requireNonBlank("roomId", roomId);
        this.treeNode = Objects.requireNonNull(treeNode, "treeNode cannot be null");
        this.config = config != null ? config : MediaSessionConfig.builder().build();
        this.components = components != null ? components : MediaComponents.builder().build();
    }

    /**
     * Starts the media session (idempotent).
     */
    public synchronized void start() throws Exception {
        if (mediaSession != null && mediaSession.isRunning()) {
            logger.fine(String.format("Media session already active for room %s", roomId));
            return;
        }

        transport = new SDRTTransport(userId, roomId, treeNode);

        AudioMixer mixer = components.getAudioMixer();
        if (mixer == null && components.getAudioSink() != null) {
            mixer = new AudioMixer(components.getAudioSink());
        }

        MediaSession.Builder builder = MediaSession.newBuilder()
                .sessionId(UUID.randomUUID().toString())
                .transport(transport)
                .config(config)
                .listener(components.getSessionListener())
                .audioSource(components.getAudioSource())
                .videoSource(components.getVideoSource())
                .audioEncoder(components.getAudioEncoder())
                .videoEncoder(components.getVideoEncoder())
                .audioDecoderFactory(components.getAudioDecoderFactory())
                .videoDecoderFactory(components.getVideoDecoderFactory())
                .audioSink(components.getAudioSink())
                .videoSink(components.getVideoSink())
                .audioMixer(mixer);

        mediaSession = builder.build();
        mediaSession.start();

        logger.info(String.format("Media session started: room=%s user=%s", roomId, userId));
    }

    /**
     * Stops the running media session.
     */
    public synchronized void stop() {
        if (mediaSession != null) {
            try {
                mediaSession.stop();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error while stopping MediaSession", e);
            } finally {
                mediaSession = null;
            }
        }

        if (transport != null) {
            transport.stop();
            transport = null;
        }
    }

    public boolean isRunning() {
        return mediaSession != null && mediaSession.isRunning();
    }

    public MediaSession getMediaSession() {
        return mediaSession;
    }

    public SDRTTransport getTransport() {
        return transport;
    }

    public MediaSessionConfig getConfig() {
        return config;
    }

    public MediaComponents getComponents() {
        return components;
    }

    public TreeNode getTreeNode() {
        return treeNode;
    }

    private static String requireNonBlank(String field, String value) {
        Objects.requireNonNull(value, field + " cannot be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return value;
    }

    /**
     * Holder for pluggable media engine components.
     */
    public static final class MediaComponents {
        private final MediaSource audioSource;
        private final MediaSource videoSource;
        private final AudioEncoder audioEncoder;
        private final VideoEncoder videoEncoder;
        private final Supplier<AudioDecoder> audioDecoderFactory;
        private final Supplier<VideoDecoder> videoDecoderFactory;
        private final MediaSink audioSink;
        private final MediaSink videoSink;
        private final AudioMixer audioMixer;
        private final MediaSessionListener sessionListener;

        private MediaComponents(Builder builder) {
            this.audioSource = builder.audioSource;
            this.videoSource = builder.videoSource;
            this.audioEncoder = builder.audioEncoder;
            this.videoEncoder = builder.videoEncoder;
            this.audioDecoderFactory = builder.audioDecoderFactory;
            this.videoDecoderFactory = builder.videoDecoderFactory;
            this.audioSink = builder.audioSink;
            this.videoSink = builder.videoSink;
            this.audioMixer = builder.audioMixer;
            this.sessionListener = builder.sessionListener;
        }

        public static Builder builder() {
            return new Builder();
        }

        public MediaSource getAudioSource() {
            return audioSource;
        }

        public MediaSource getVideoSource() {
            return videoSource;
        }

        public AudioEncoder getAudioEncoder() {
            return audioEncoder;
        }

        public VideoEncoder getVideoEncoder() {
            return videoEncoder;
        }

        public Supplier<AudioDecoder> getAudioDecoderFactory() {
            return audioDecoderFactory;
        }

        public Supplier<VideoDecoder> getVideoDecoderFactory() {
            return videoDecoderFactory;
        }

        public MediaSink getAudioSink() {
            return audioSink;
        }

        public MediaSink getVideoSink() {
            return videoSink;
        }

        public AudioMixer getAudioMixer() {
            return audioMixer;
        }

        public MediaSessionListener getSessionListener() {
            return sessionListener;
        }

        public static final class Builder {
            private MediaSource audioSource;
            private MediaSource videoSource;
            private AudioEncoder audioEncoder;
            private VideoEncoder videoEncoder;
            private Supplier<AudioDecoder> audioDecoderFactory;
            private Supplier<VideoDecoder> videoDecoderFactory;
            private MediaSink audioSink;
            private MediaSink videoSink;
            private AudioMixer audioMixer;
            private MediaSessionListener sessionListener;

            public Builder audioSource(MediaSource source) {
                this.audioSource = source;
                return this;
            }

            public Builder videoSource(MediaSource source) {
                this.videoSource = source;
                return this;
            }

            public Builder audioEncoder(AudioEncoder encoder) {
                this.audioEncoder = encoder;
                return this;
            }

            public Builder videoEncoder(VideoEncoder encoder) {
                this.videoEncoder = encoder;
                return this;
            }

            public Builder audioDecoderFactory(Supplier<AudioDecoder> factory) {
                this.audioDecoderFactory = factory;
                return this;
            }

            public Builder videoDecoderFactory(Supplier<VideoDecoder> factory) {
                this.videoDecoderFactory = factory;
                return this;
            }

            public Builder audioSink(MediaSink sink) {
                this.audioSink = sink;
                return this;
            }

            public Builder videoSink(MediaSink sink) {
                this.videoSink = sink;
                return this;
            }

            public Builder audioMixer(AudioMixer mixer) {
                this.audioMixer = mixer;
                return this;
            }

            public Builder sessionListener(MediaSessionListener listener) {
                this.sessionListener = listener;
                return this;
            }

            public MediaComponents build() {
                return new MediaComponents(this);
            }
        }
    }
}
