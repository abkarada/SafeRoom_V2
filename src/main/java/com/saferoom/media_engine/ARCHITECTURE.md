# Media Engine Architecture

## Overview

This document outlines the architecture for SafeRoom's Media Engine, designed to integrate seamlessly with SDRT (Serverless Distributed Relay Tree) for distributed media relay in group calls. The engine is built on GStreamer for high-performance audio/video processing.

## Goals

- **Modular Design**: Separation of concerns between media processing, transport, and relay logic
- **SDRT Integration**: Seamless integration with the tree-based relay system
- **Low Latency**: Target glass-to-glass latency < 300ms
- **Scalability**: Support for multi-participant group calls with efficient relay forwarding
- **Flexibility**: Transport-agnostic design allowing WebRTC, SDRT, or custom transports

---

## Critical Design Decision: Transport-Layer Encryption Only

### Rationale

The Media Engine uses **WebRTC DTLS** for transport security rather than application-layer encryption. This design choice is critical for performance in a relay topology.

### Problem with Application-Layer Encryption

In a relay topology (A → B → C), application-layer encryption forces each relay node to decrypt and re-encrypt every packet:

```
A → B (relay) → C

With GroupKeyManager:
1. A encrypts payload → sends to B
2. B decrypts payload (to inspect/route)
3. B re-encrypts payload → sends to C
4. C decrypts payload

Performance Impact:
- Decrypt + Re-encrypt at each hop: ~2ms latency overhead
- 30 FPS video = 30 decrypt/encrypt cycles per second per hop
- 3 hops = 6ms additional latency
- CPU overhead: ~80% for cryptographic operations
```

### Solution: WebRTC DTLS (Transport-Layer Security)

WebRTC DataChannels use DTLS for peer-to-peer encryption, allowing zero-copy forwarding:

```
A → B (relay) → C

With WebRTC DTLS:
1. A sends RTP → DTLS encrypts transport → B
2. B receives → DTLS decrypts → B sees RTP
3. B forwards RTP → DTLS encrypts transport → C
4. C receives → DTLS decrypts → C sees RTP

Performance Benefits:
- Zero-copy forwarding (B doesn't touch media payload)
- Latency per hop: ~0.1ms (routing only)
- 3 hops = 0.3ms total latency (20x improvement)
- CPU overhead: ~5% (DTLS handled by WebRTC native code)
```

### Security Model

| Layer | Mechanism | Purpose |
|-------|-----------|---------|
| **Transport** | WebRTC DTLS | Peer-to-peer encryption between nodes |
| **Authentication** | DTLS Certificates | Node identity verification via fingerprints |
| **Integrity** | DTLS MAC | Message authentication and tamper detection |

**Trade-offs:**

✅ **Advantages:**
- Zero-copy relay forwarding (critical for scalability)
- Minimal latency overhead
- Lower CPU usage (native WebRTC DTLS)
- Simpler codebase (no GroupKeyManager complexity)

⚠️ **Limitations:**
- Relay nodes can inspect RTP headers (but not decrypt media payloads)
- No end-to-end encryption (relay nodes are trusted)
- Relies on WebRTC DTLS security (industry-standard)

**Future Enhancement: Optional E2EE**

If end-to-end encryption becomes required:
- Implement encryption at the RTP payload level (inside Opus/VP8 frames)
- Relay nodes forward encrypted RTP without decryption
- Only sender/receiver possess media decryption keys
- Trade-off: Precludes SFU optimizations (simulcast, bandwidth adaptation)

## 📋 Temel Prensipler

### 1. Separation of Concerns (Endişelerin Ayrılması)
```
MediaEngine  → Media işleme (capture, encode, decode, playback)
SDRT Layer   → Paket routing (tree topology, forwarding)
Transport    → İkisi arasında köprü (abstraction)
```

### 2. Transport Abstraction
MediaEngine, transport katmanını BİLMEZ. Bu sayede:
- SDRT ile çalışabilir
- WebRTC ile çalışabilir
- Ham UDP ile çalışabilir
- Test için mock transport kullanılabilir

---

## 🏗️ Package Structure

```
com.saferoom.media_engine/
├── transport/                    # ADIM 1: Transport Abstraction
│   ├── MediaTransport.java       # Interface (send/receive)
│   ├── MediaPacketType.java      # AUDIO, VIDEO, METADATA
│   ├── TransportCallback.java    # Paket geldiğinde callback
│   └── TransportStats.java       # İstatistik toplama
│
├── adapter/                      # ADIM 2: SDRT Adapter
│   ├── SDRTTransport.java        # MediaTransport implementasyonu
│   └── SDRTPacketWrapper.java    # MediaPacket ↔ Media frame dönüşümü
│
├── core/                         # ADIM 3: Core Architecture
│   ├── MediaSession.java         # Ana session manager
│   ├── MediaSessionConfig.java   # Konfigürasyon
│   └── MediaSessionListener.java # Event callback
│
├── source/                       # ADIM 4: Media Capture
│   ├── MediaSource.java          # Interface
│   ├── GStreamerAudioSource.java # Mikrofon capture
│   ├── GStreamerVideoSource.java # Kamera capture
│   └── ScreenCaptureSource.java  # Ekran paylaşımı
│
├── sink/                         # ADIM 5: Media Playback
│   ├── MediaSink.java            # Interface
│   ├── GStreamerAudioSink.java   # Speaker playback
│   ├── GStreamerVideoSink.java   # Video renderer
│   └── AudioMixer.java           # Çoklu stream mixer (grup görüşme)
│
├── processor/                    # ADIM 6: Codec Layer
│   ├── MediaProcessor.java       # Interface
│   ├── AudioEncoder.java         # Opus encoder (GStreamer)
│   ├── AudioDecoder.java         # Opus decoder
│   ├── VideoEncoder.java         # VP8/VP9 encoder (GStreamer)
│   ├── VideoDecoder.java         # VP8/VP9 decoder
│   └── MediaFrame.java           # Raw frame data container
│
└── util/                         # Utilities
    ├── MediaClock.java           # Timestamp senkronizasyonu
    ├── FrameBuffer.java          # Circular buffer
    └── MediaStatistics.java      # Performans metrikleri
```

---

## 🔄 Data Flow (Veri Akışı)

### Gönderme Tarafı (Local User)
```
[Camera/Mic]
    ↓
[GStreamerSource] → raw frames
    ↓
[MediaProcessor] → encode (Opus/VP8)
    ↓
[SDRTTransport] → MediaPacket wrapper + encryption
    ↓
[TreeNode] → tree topology routing
    ↓
[Network]
```

### Alma Tarafı (Remote User)
```
[Network]
    ↓
[TreeNode] → packet received
    ↓
[SDRTTransport] → decrypt + unwrap MediaPacket
    ↓
[MediaProcessor] → decode
    ↓
[GStreamerSink] → playback (speaker/screen)
```

---

## 📦 ADIM 1: Transport Abstraction

### MediaTransport Interface
```java
public interface MediaTransport {
    // Paket gönderme
    void sendAudio(byte[] encodedData, long timestamp, int sequenceNumber);
    void sendVideo(byte[] encodedData, long timestamp, int sequenceNumber, boolean keyFrame);
    
    // Callback registration
    void setOnAudioReceived(TransportCallback callback);
    void setOnVideoReceived(TransportCallback callback);
    
    // Lifecycle
    void start() throws Exception;
    void stop();
    
    // Statistics
    TransportStats getStats();
}
```

### TransportCallback Interface
```java
public interface TransportCallback {
    void onPacketReceived(
        byte[] data,           // Encoded data
        long timestamp,        // Media timestamp
        int sequenceNumber,    // Packet sequence
        String senderId        // Who sent this
    );
}
```

**Neden bu tasarım?**
- MediaEngine, sadece byte[] gönderir/alır
- Routing, encryption, tree topology → MediaEngine'in sorunu değil
- Test edilebilir (mock transport ile)

---

## 🔌 ADIM 2: SDRT Transport Adapter

### SDRTTransport Implementation
```java
public class SDRTTransport implements MediaTransport {
    private final SDRTMediaBridge bridge;      // Mevcut SDRT sistemi
    private final TreeNode localNode;           // Kendi node'umuz
    private final String userId;
    private final String roomId;
    
    private TransportCallback audioCallback;
    private TransportCallback videoCallback;
    
    @Override
    public void sendAudio(byte[] encodedData, long timestamp, int seqNum) {
        // 1. MediaPacket oluştur
        MediaPacket packet = new MediaPacket();
        packet.setRoomId(roomId);
        packet.setSenderId(userId);
        packet.setTimestamp(timestamp);
        packet.setSequenceNumber(seqNum);
        packet.setPayloadType(MediaPacketType.AUDIO.getValue());
        packet.setPayload(encodedData);
        
        // 2. Encrypt (SDRTMediaBridge içinde)
        byte[] encrypted = bridge.encryptPacket(packet);
        
        // 3. Tree'ye gönder
        localNode.processPacket(packet, userId);
    }
    
    @Override
    public void sendVideo(byte[] encodedData, long timestamp, int seqNum, boolean keyFrame) {
        // Similar logic, but mark keyframes
        MediaPacket packet = new MediaPacket();
        // ... setup
        packet.setKeyFrame(keyFrame);  // VP8 keyframe marker
        
        byte[] encrypted = bridge.encryptPacket(packet);
        localNode.processPacket(packet, userId);
    }
    
    // TreeNode'dan gelen paketleri MediaEngine'e yönlendir
    public void onPacketReceivedFromTree(MediaPacket packet) {
        // 1. Decrypt
        byte[] decrypted = bridge.decryptPacket(packet);
        
        // 2. Packet type'a göre callback çağır
        if (packet.getPayloadType() == MediaPacketType.AUDIO.getValue()) {
            if (audioCallback != null) {
                audioCallback.onPacketReceived(
                    decrypted,
                    packet.getTimestamp(),
                    packet.getSequenceNumber(),
                    packet.getSenderId()
                );
            }
        } else if (packet.getPayloadType() == MediaPacketType.VIDEO.getValue()) {
            if (videoCallback != null) {
                videoCallback.onPacketReceived(
                    decrypted,
                    packet.getTimestamp(),
                    packet.getSequenceNumber(),
                    packet.getSenderId()
                );
            }
        }
    }
}
```

**Integration Point:**
`SDRTMediaBridge` içinde, `TreeNode` paket aldığında:
```java
// SDRTMediaBridge.java içinde
public void onTreePacketReceived(MediaPacket packet) {
    if (sdrtTransport != null) {
        sdrtTransport.onPacketReceivedFromTree(packet);
    }
}
```

---

## 🎬 ADIM 3: Media Engine Core

### MediaSession - Ana Orkestratör
```java
public class MediaSession {
    private final String sessionId;
    private final MediaTransport transport;
    
    // Components
    private MediaSource audioSource;
    private MediaSource videoSource;
    private MediaProcessor audioEncoder;
    private MediaProcessor videoEncoder;
    
    private Map<String, AudioDecoder> audioDecoders;  // Per-participant
    private Map<String, VideoDecoder> videoDecoders;  // Per-participant
    
    private AudioMixer audioMixer;
    private MediaSink audioSink;
    private MediaSink videoSink;
    
    // Lifecycle
    public void start() {
        // 1. Start capture
        audioSource.start();
        videoSource.start();
        
        // 2. Start encoders
        audioEncoder.start();
        videoEncoder.start();
        
        // 3. Wire callbacks
        audioSource.setFrameCallback(frame -> {
            byte[] encoded = audioEncoder.encode(frame);
            transport.sendAudio(encoded, frame.getTimestamp(), getNextSeqNum());
        });
        
        videoSource.setFrameCallback(frame -> {
            byte[] encoded = videoEncoder.encode(frame);
            boolean keyFrame = videoEncoder.isLastFrameKeyFrame();
            transport.sendVideo(encoded, frame.getTimestamp(), getNextSeqNum(), keyFrame);
        });
        
        // 4. Register receive callbacks
        transport.setOnAudioReceived((data, ts, seq, sender) -> {
            AudioDecoder decoder = getOrCreateAudioDecoder(sender);
            MediaFrame decoded = decoder.decode(data, ts, seq);
            audioMixer.addFrame(sender, decoded);
        });
        
        transport.setOnVideoReceived((data, ts, seq, sender) -> {
            VideoDecoder decoder = getOrCreateVideoDecoder(sender);
            MediaFrame decoded = decoder.decode(data, ts, seq);
            videoSink.render(sender, decoded);
        });
        
        // 5. Start playback
        audioSink.start();
        videoSink.start();
    }
}
```

**Neden bu tasarım?**
- Her component bağımsız (test edilebilir)
- Lifecycle management merkezi
- Her remote participant için ayrı decoder (concurrency)

---

## 🎤 ADIM 4: GStreamer Source

### GStreamerAudioSource Example
```java
public class GStreamerAudioSource implements MediaSource {
    private Pipeline pipeline;
    private AppSink appSink;
    private FrameCallback callback;
    
    public void start() {
        // GStreamer pipeline oluştur
        // Example: alsasrc → audioconvert → audioresample → capsfilter → appsink
        
        pipeline = new Pipeline("audio-capture");
        
        Element alsaSrc = ElementFactory.make("alsasrc", "mic");
        Element convert = ElementFactory.make("audioconvert", "convert");
        Element resample = ElementFactory.make("audioresample", "resample");
        
        // Opus için: 48kHz, mono or stereo
        Caps caps = Caps.fromString("audio/x-raw,format=S16LE,rate=48000,channels=1");
        Element capsFilter = ElementFactory.make("capsfilter", "filter");
        capsFilter.set("caps", caps);
        
        appSink = new AppSink("sink");
        appSink.connect(new AppSink.NEW_SAMPLE() {
            @Override
            public FlowReturn newSample(AppSink elem) {
                Sample sample = elem.pullSample();
                Buffer buffer = sample.getBuffer();
                
                // Buffer → byte[]
                ByteBuffer byteBuffer = buffer.map(false);
                byte[] audioData = new byte[byteBuffer.remaining()];
                byteBuffer.get(audioData);
                buffer.unmap();
                
                // Callback ile MediaProcessor'a gönder
                if (callback != null) {
                    MediaFrame frame = new MediaFrame(
                        MediaFrame.Type.AUDIO,
                        audioData,
                        System.currentTimeMillis(),
                        48000  // sample rate
                    );
                    callback.onFrame(frame);
                }
                
                return FlowReturn.OK;
            }
        });
        
        pipeline.addMany(alsaSrc, convert, resample, capsFilter, appSink);
        Pipeline.linkMany(alsaSrc, convert, resample, capsFilter, appSink);
        
        pipeline.play();
    }
}
```

**Neden GStreamer?**
- Hardware acceleration (GPU encoding)
- Cross-platform (Linux, Windows, macOS)
- Production-ready codecs (Opus, VP8, VP9)
- Screen capture, filters, effects

---

## 🔊 ADIM 5: Audio Mixer (Grup Görüşme)

### AudioMixer - Çoklu Stream Birleştirme
```java
public class AudioMixer {
    // Her participant için buffer
    private Map<String, CircularFrameBuffer> participantBuffers;
    
    private ScheduledExecutorService mixer;
    private MediaSink outputSink;
    
    public void addFrame(String participantId, MediaFrame audioFrame) {
        CircularFrameBuffer buffer = participantBuffers.get(participantId);
        if (buffer == null) {
            buffer = new CircularFrameBuffer(100);  // 100ms buffer
            participantBuffers.put(participantId, buffer);
        }
        buffer.push(audioFrame);
    }
    
    // 20ms'de bir mix yap (Opus frame size)
    private void mixAudio() {
        List<MediaFrame> framesToMix = new ArrayList<>();
        
        // Her participant'tan bir frame al
        for (CircularFrameBuffer buffer : participantBuffers.values()) {
            MediaFrame frame = buffer.pop();
            if (frame != null) {
                framesToMix.add(frame);
            }
        }
        
        if (framesToMix.isEmpty()) return;
        
        // Audio mixing: Sample-by-sample addition + clipping
        byte[] mixed = mixSamples(framesToMix);
        
        // Output sink'e gönder (speaker playback)
        MediaFrame mixedFrame = new MediaFrame(
            MediaFrame.Type.AUDIO,
            mixed,
            System.currentTimeMillis(),
            48000
        );
        outputSink.render(null, mixedFrame);
    }
    
    private byte[] mixSamples(List<MediaFrame> frames) {
        // S16LE format: 16-bit signed PCM
        int sampleCount = frames.get(0).getData().length / 2;
        short[] mixed = new short[sampleCount];
        
        for (MediaFrame frame : frames) {
            byte[] data = frame.getData();
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            
            for (int i = 0; i < sampleCount; i++) {
                short sample = bb.getShort();
                mixed[i] += sample;  // Simple addition
            }
        }
        
        // Clipping to prevent overflow
        for (int i = 0; i < sampleCount; i++) {
            if (mixed[i] > Short.MAX_VALUE) mixed[i] = Short.MAX_VALUE;
            if (mixed[i] < Short.MIN_VALUE) mixed[i] = Short.MIN_VALUE;
        }
        
        // short[] → byte[]
        ByteBuffer result = ByteBuffer.allocate(sampleCount * 2);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (short s : mixed) result.putShort(s);
        
        return result.array();
    }
}
```

**Neden Mixer Gerekli?**
- Grup görüşmede N kişi → N audio stream
- Speaker sadece 1 stream oynatabilir
- Mixing: Tüm stream'leri birleştirip tek output

---

## 🎥 ADIM 6: Video Codec (VP8 Encoder)

### VideoEncoder with GStreamer
```java
public class VideoEncoder implements MediaProcessor {
    private Pipeline pipeline;
    private boolean lastWasKeyFrame = false;
    
    public byte[] encode(MediaFrame rawFrame) {
        // GStreamer VP8 encoder pipeline:
        // appsrc → videoconvert → vp8enc → appsink
        
        // Key frame forcing (IDR frame)
        if (shouldForceKeyFrame()) {
            vp8enc.set("force-keyframe", true);
        }
        
        // Push raw frame to appsrc
        // Pull encoded packet from appsink
        
        // Return encoded VP8 packet
    }
    
    private boolean shouldForceKeyFrame() {
        // Key frame her 2 saniyede bir
        // Ya da network loss yüksekse
        return (frameCount % 60 == 0);  // 30fps → 2 saniye
    }
}
```

---

## 🔗 ADIM 7: Integration with SDRT

### SDRTMediaBridge Update
```java
public class SDRTMediaBridge {
    private TreeNode localNode;
    private SDRTTransport transport;
    private MediaSession mediaSession;
    
    public void startGroupCall(String roomId, String userId) {
        // 1. Setup tree node (already done)
        localNode = new TreeNode(userId, roomId, NodeRole.LEAF);
        
        // 2. Create transport adapter
        transport = new SDRTTransport(this, localNode, userId, roomId);
        
        // 3. Create media session
        MediaSessionConfig config = new MediaSessionConfig()
            .setAudioCodec(AudioCodec.OPUS)
            .setVideoCodec(VideoCodec.VP8)
            .setAudioSampleRate(48000)
            .setVideoResolution(1280, 720)
            .setVideoFps(30);
        
        mediaSession = new MediaSession(UUID.randomUUID().toString(), transport, config);
        
        // 4. Start media flow
        mediaSession.start();
        
        // 5. Register with SDRT coordinator
        coordinator.handleTreeJoin(roomId, userId);
    }
    
    // TreeNode'dan paket gelince
    public void onPacketFromTree(MediaPacket packet) {
        transport.onPacketReceivedFromTree(packet);
    }
}
```

---

## 📊 Threading Model

```
[Capture Thread]      GStreamer pipeline → AppSink callback
        ↓
[Encoder Thread]      MediaProcessor.encode()
        ↓
[Transport Thread]    SDRTTransport.sendAudio/Video()
        ↓
[Network Thread]      TreeNode.processPacket()

---

[Network Thread]      TreeNode receives packet
        ↓
[Transport Thread]    SDRTTransport.onPacketReceived()
        ↓
[Decoder Thread]      MediaProcessor.decode()
        ↓
[Mixer Thread]        AudioMixer.mixAudio() (20ms interval)
        ↓
[Playback Thread]     GStreamer AppSrc → Speaker
```

**Thread Safety:**
- Circular buffers (lock-free queues)
- Volatile flags for state
- Executor services for scheduled tasks

---

## ✅ Implementation Checklist

### Phase 1: Foundation (ADIM 1-2)
- [ ] Create `transport/` package interfaces
- [ ] Implement `SDRTTransport` adapter
- [ ] Write unit tests (mock transport)

### Phase 2: Core (ADIM 3)
- [ ] Create `MediaSession` manager
- [ ] Implement lifecycle (start/stop/error handling)
- [ ] Add statistics collection

### Phase 3: GStreamer Integration (ADIM 4-5)
- [ ] Setup GStreamer Java bindings (dependency)
- [ ] Implement `GStreamerAudioSource`
- [ ] Implement `GStreamerVideoSource`
- [ ] Implement `GStreamerAudioSink`
- [ ] Implement `GStreamerVideoSink`
- [ ] Implement `AudioMixer`

### Phase 4: Codecs (ADIM 6)
- [ ] Implement Opus encoder/decoder
- [ ] Implement VP8 encoder/decoder
- [ ] Handle key frame requests
- [ ] Packet loss handling (PLC - Packet Loss Concealment)

### Phase 5: Integration (ADIM 7)
- [ ] Update `SDRTMediaBridge`
- [ ] Wire `SDRTTransport` to `TreeNode`
- [ ] End-to-end testing

### Phase 6: Optimization
- [ ] Latency profiling
- [ ] CPU usage optimization
- [ ] Memory leak checks
- [ ] Network congestion handling

---

## 🧪 Testing Strategy

### Unit Tests
- Mock transport for MediaEngine
- Synthetic frames for codecs
- Buffer overflow tests

### Integration Tests
- Local loopback (capture → encode → decode → playback)
- 2-person call simulation
- 5-person group call simulation

### Performance Tests
- Latency measurement (glass-to-glass)
- CPU usage under load
- Network bandwidth utilization

---

## 📚 Dependencies

### build.gradle additions:
```gradle
dependencies {
    // GStreamer Java bindings
    implementation 'org.freedesktop.gstreamer:gst1-java-core:1.4.0'
    
    // Existing
    implementation 'io.grpc:grpc-netty-shaded:1.50.0'
    implementation 'com.google.protobuf:protobuf-java:3.21.9'
}
```

### System Requirements:
- GStreamer 1.20+ installed on system
- PulseAudio (Linux) or CoreAudio (macOS)
- Camera/microphone access permissions

---

## 🎯 Success Criteria

✅ **Functional:**
- Audio/video capture works
- Encoding/decoding works
- Tree relay works (packets routed correctly)
- Multi-participant mixing works
- Latency < 300ms (glass-to-glass)

✅ **Non-Functional:**
- CPU usage < 30% per call
- Memory stable (no leaks)
- Graceful error handling
- Thread-safe (no race conditions)

---

## 🚀 Next Steps

1. **Start with ADIM 1:** Create transport interfaces (30 minutes)
2. **Then ADIM 2:** Implement SDRTTransport (1 hour)
3. **Test integration:** Mock transport → verify callbacks work
4. **Continue with ADIM 3-7** step by step

**ÖNEMLI:** Her adımı tamamladıktan sonra, o adımı test edip doğrulayacağız. 
Böylece sorun çıkarsa hemen tespit ederiz.

---

Hazır mısın? İlk adımdan başlayalım mı? 🚀
