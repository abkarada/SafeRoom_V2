package com.saferoom.media_engine.core;

import com.saferoom.media_engine.processor.AudioDecoder;
import com.saferoom.media_engine.processor.AudioEncoder;
import com.saferoom.media_engine.processor.MediaFrame;
import com.saferoom.media_engine.processor.MediaProcessor;
import com.saferoom.media_engine.processor.VideoDecoder;
import com.saferoom.media_engine.processor.VideoEncoder;
import com.saferoom.media_engine.sink.AudioMixer;
import com.saferoom.media_engine.sink.MediaSink;
import com.saferoom.media_engine.source.MediaSource;
import com.saferoom.media_engine.transport.MediaTransport;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates capture, encoding, transport, decoding, and playback for a single call session.
 */
public class MediaSession {

    private static final Logger logger = Logger.getLogger(MediaSession.class.getName());
    private static final int UINT16_MASK = 0xFFFF;

    private final String sessionId;
    private final MediaTransport transport;
    private final MediaSessionConfig config;
    private final MediaSessionListener listener;

    private final MediaSource audioSource;
    private final MediaSource videoSource;
    private final AudioEncoder audioEncoder;
    private final VideoEncoder videoEncoder;
    private final Supplier<AudioDecoder> audioDecoderFactory;
    private final Supplier<VideoDecoder> videoDecoderFactory;
    private final MediaSink audioSink;
    private final MediaSink videoSink;
    private final AudioMixer audioMixer;

    private final Map<String, AudioDecoder> audioDecoders = new ConcurrentHashMap<>();
    private final Map<String, VideoDecoder> videoDecoders = new ConcurrentHashMap<>();
    private final Set<String> knownParticipants = ConcurrentHashMap.newKeySet();

    private final AtomicInteger audioSequence = new AtomicInteger(0);
    private final AtomicInteger videoSequence = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MediaSession(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.transport = Objects.requireNonNull(builder.transport, "transport cannot be null");
        this.config = Objects.requireNonNull(builder.config, "config cannot be null");
        this.listener = Objects.requireNonNullElse(builder.listener, new MediaSessionListener() {});
        this.audioSource = builder.audioSource;
        this.videoSource = builder.videoSource;
        this.audioEncoder = builder.audioEncoder;
        this.videoEncoder = builder.videoEncoder;
        this.audioDecoderFactory = builder.audioDecoderFactory;
        this.videoDecoderFactory = builder.videoDecoderFactory;
        this.audioSink = builder.audioSink;
        this.videoSink = builder.videoSink;
        this.audioMixer = builder.audioMixer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts all components and begins media flow.
     */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            logger.warning("MediaSession already running");
            return;
        }

        try {
            if (config.isAudioEnabled() && audioSink != null) {
                audioSink.start();
            }
            if (config.isVideoEnabled() && videoSink != null) {
                videoSink.start();
            }
            if (config.isAudioEnabled() && audioMixer != null && !audioMixer.isRunning()) {
                audioMixer.start();
            }

            registerTransportCallbacks();
            transport.start();

            if (config.isAudioEnabled() && audioEncoder != null) {
                audioEncoder.start();
            }
            if (config.isVideoEnabled() && videoEncoder != null) {
                videoEncoder.start();
            }

            if (config.isAudioEnabled() && audioSource != null) {
                audioSource.setFrameCallback(this::handleCapturedAudio);
                audioSource.start();
            }

            if (config.isVideoEnabled() && videoSource != null) {
                videoSource.setFrameCallback(this::handleCapturedVideo);
                videoSource.start();
            }

            listener.onSessionStarted(sessionId);
            logger.info(String.format("MediaSession %s started", sessionId));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start MediaSession", e);
            listener.onError(sessionId, "Failed to start session", e);
            stop();
            throw e;
        }
    }

    /**
     * Stops capture, transport, and playback.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        safeStop(audioSource, "audioSource");
        safeStop(videoSource, "videoSource");
        safeStop(audioEncoder, "audioEncoder");
        safeStop(videoEncoder, "videoEncoder");
        safeStopDecoders(audioDecoders);
        safeStopDecoders(videoDecoders);

        if (audioMixer != null) {
            audioMixer.stop();
        }

        safeStop(audioSink, "audioSink");
        safeStop(videoSink, "videoSink");

        try {
            transport.stop();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Transport stop failed", e);
        }

        audioDecoders.clear();
        videoDecoders.clear();
        knownParticipants.clear();

        listener.onSessionStopped(sessionId);
        logger.info(String.format("MediaSession %s stopped", sessionId));
    }

    private void registerTransportCallbacks() {
        transport.setOnAudioReceived((data, timestamp, sequence, senderId, metadata) ->
                handleIncomingAudio(senderId, data, timestamp, sequence));
        transport.setOnVideoReceived((data, timestamp, sequence, senderId, metadata) ->
                handleIncomingVideo(senderId, data, timestamp, sequence));
        transport.setOnMetadataReceived((data, timestamp, sequence, senderId, metadata) ->
                logger.fine(String.format("Metadata packet from %s: %d bytes", senderId, data.length)));
        transport.setErrorListener((error, message) ->
                listener.onTransportError(sessionId, error, message));
    }

    private void handleCapturedAudio(MediaFrame frame) {
        if (!running.get() || audioEncoder == null) {
            return;
        }
        try {
            byte[] encoded = audioEncoder.encode(frame);
            if (encoded == null || encoded.length == 0) {
                return;
            }
            int seq = nextAudioSequence();
            transport.sendAudio(encoded, frame.getTimestamp(), seq);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Audio encode/send failed", e);
            listener.onError(sessionId, "Audio encode/send failed", e);
        }
    }

    private void handleCapturedVideo(MediaFrame frame) {
        if (!running.get() || videoEncoder == null) {
            return;
        }
        try {
            byte[] encoded = videoEncoder.encode(frame);
            if (encoded == null || encoded.length == 0) {
                return;
            }
            int seq = nextVideoSequence();
            boolean keyFrame = videoEncoder.isLastFrameKeyFrame();
            transport.sendVideo(encoded, frame.getTimestamp(), seq, keyFrame);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Video encode/send failed", e);
            listener.onError(sessionId, "Video encode/send failed", e);
        }
    }

    private void handleIncomingAudio(String senderId, byte[] data, long timestamp, int sequenceNumber) {
        if (!running.get()) {
            return;
        }
        AudioDecoder decoder = audioDecoders.computeIfAbsent(senderId, id -> startDecoder(audioDecoderFactory));
        if (decoder == null) {
            return;
        }
        try {
            MediaFrame decoded = decoder.decode(data, timestamp, sequenceNumber);
            if (decoded != null) {
                markParticipantActive(senderId);
                if (audioMixer != null && audioMixer.isRunning()) {
                    audioMixer.addFrame(senderId, decoded);
                } else if (audioSink != null) {
                    audioSink.render(senderId, decoded);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, String.format("Audio decode failed for %s", senderId), e);
        }
    }

    private void handleIncomingVideo(String senderId, byte[] data, long timestamp, int sequenceNumber) {
        if (!running.get() || videoSink == null) {
            return;
        }
        VideoDecoder decoder = videoDecoders.computeIfAbsent(senderId, id -> startDecoder(videoDecoderFactory));
        if (decoder == null) {
            return;
        }
        try {
            MediaFrame decoded = decoder.decode(data, timestamp, sequenceNumber);
            if (decoded != null) {
                markParticipantActive(senderId);
                videoSink.render(senderId, decoded);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, String.format("Video decode failed for %s", senderId), e);
        }
    }

    private void markParticipantActive(String participantId) {
        if (knownParticipants.add(participantId)) {
            listener.onParticipantJoined(sessionId, participantId);
        }
    }

    private int nextAudioSequence() {
        return audioSequence.updateAndGet(prev -> (prev + 1) & UINT16_MASK);
    }

    private int nextVideoSequence() {
        return videoSequence.updateAndGet(prev -> (prev + 1) & UINT16_MASK);
    }

    private <T extends MediaProcessor> T startDecoder(Supplier<T> factory) {
        if (factory == null) {
            logger.warning("Decoder factory is not configured");
            return null;
        }
        try {
            T decoder = factory.get();
            if (decoder != null) {
                decoder.start();
            }
            return decoder;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start decoder", e);
            return null;
        }
    }

    private void safeStop(Object component, String name) {
        if (component == null) {
            return;
        }
        try {
            if (component instanceof MediaSource) {
                ((MediaSource) component).stop();
            } else if (component instanceof MediaSink) {
                ((MediaSink) component).stop();
            } else if (component instanceof MediaProcessor) {
                ((MediaProcessor) component).stop();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, String.format("Failed to stop %s", name), e);
        }
    }

    private void safeStopDecoders(Map<String, ? extends MediaProcessor> decoders) {
        decoders.values().forEach(decoder -> {
            try {
                decoder.stop();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Decoder stop failed", e);
            }
        });
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionId;
        private MediaTransport transport;
        private MediaSessionConfig config;
        private MediaSessionListener listener;
        private MediaSource audioSource;
        private MediaSource videoSource;
        private AudioEncoder audioEncoder;
        private VideoEncoder videoEncoder;
        private Supplier<AudioDecoder> audioDecoderFactory;
        private Supplier<VideoDecoder> videoDecoderFactory;
        private MediaSink audioSink;
        private MediaSink videoSink;
        private AudioMixer audioMixer;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder transport(MediaTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder config(MediaSessionConfig config) {
            this.config = config;
            return this;
        }

        public Builder listener(MediaSessionListener listener) {
            this.listener = listener;
            return this;
        }

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

        public MediaSession build() {
            return new MediaSession(this);
        }
    }
}

