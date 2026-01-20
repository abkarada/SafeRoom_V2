package com.saferoom.rooms.client.logic;

import com.saferoom.rooms.grpc.ChatMessage;
import com.saferoom.rooms.grpc.Envelope;
import com.saferoom.rooms.grpc.SignalRelayRequest;
import com.saferoom.webrtc.WebRTCClient;
import com.saferoom.webrtc.wrapper.*;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Native implementation of RoomWebRTCManager using dev.onvoid.webrtc via
 * Wrappers.
 * Manages multiple peer connections with 3 separate DataChannels.
 */
public class RoomWebRTCManagerImpl implements RoomWebRTCManager {

    private static final Logger logger = Logger.getLogger(RoomWebRTCManagerImpl.class.getName());

    // Channels
    private static final String LABEL_CONTROL = "dc_control";
    private static final String LABEL_DATA = "dc_data";
    private static final String LABEL_FILE = "dc_file";

    // Topology State
    private final Map<String, PeerContext> peers = new ConcurrentHashMap<>();

    // Executor for marshaling Native callbacks -> Application Logic
    // Using a single single-threaded executor ensures serial event processing for
    // the FSM
    private final ExecutorService fsmExecutor = Executors.newSingleThreadExecutor();

    private Listener listener;
    private final IWebRTCFactory factory;

    public RoomWebRTCManagerImpl() {
        this(new WebRTCFactoryWrapper(WebRTCClient.getFactory()));
    }

    public RoomWebRTCManagerImpl(IWebRTCFactory factory) {
        this.factory = factory;
        if (this.factory == null) {
            logger.warning(
                    "WebRTC Factory is null! Ensure WebRTCClient.initialize() is called or factory is provided.");
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void broadcastData(Envelope envelope) {
        byte[] data = envelope.toByteArray();
        peers.values().forEach(ctx -> {
            if (ctx.dcData != null && ctx.dcData.getState() == dev.onvoid.webrtc.RTCDataChannelState.OPEN) {
                ctx.dcData.send(new dev.onvoid.webrtc.RTCDataChannelBuffer(ByteBuffer.wrap(data), true));
            }
        });
    }

    @Override
    public void initiateConnection(String remoteNodeId) {
        PeerContext context = ensurePeer(remoteNodeId);

        // Create DataChannels (Initiator side creates them)
        createDataChannels(context);

        // Create Offer
        RTCOfferOptions options = new RTCOfferOptions();
        context.pc.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription desc) {
                context.pc.setLocalDescription(desc, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        marshal(() -> {
                            if (listener != null) {
                                SignalRelayRequest.Builder builder = SignalRelayRequest.newBuilder();
                                builder.setOffer(
                                        com.saferoom.rooms.grpc.SdpOffer.newBuilder().setSdp(desc.sdp).build());
                                listener.onLocalSignal(remoteNodeId, builder.build());
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        logger.severe("Failed to set Local Description (Offer) for " + remoteNodeId + ": " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                logger.severe("Failed to create Offer for " + remoteNodeId + ": " + error);
            }
        });
    }

    @Override
    public void handleOffer(String remoteNodeId, String sdp) {
        PeerContext context = ensurePeer(remoteNodeId);

        // Set Remote Desc
        RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        context.pc.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                // Remote set success, now flush any pending ICE
                flushPendingIce(context);

                // Create Answer
                RTCAnswerOptions options = new RTCAnswerOptions();
                context.pc.createAnswer(options, new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription desc) {
                        context.pc.setLocalDescription(desc, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                marshal(() -> {
                                    if (listener != null) {
                                        SignalRelayRequest.Builder builder = SignalRelayRequest.newBuilder();
                                        builder.setAnswer(com.saferoom.rooms.grpc.SdpAnswer.newBuilder()
                                                .setSdp(desc.sdp).build());
                                        listener.onLocalSignal(remoteNodeId, builder.build());
                                    }
                                });
                            }

                            @Override
                            public void onFailure(String error) {
                                logger.severe(
                                        "Failed to set Local Description (Answer) for " + remoteNodeId + ": " + error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        logger.severe("Failed to create Answer for " + remoteNodeId + ": " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                logger.severe("Failed to set Remote Description (Offer) for " + remoteNodeId + ": " + error);
            }
        });
    }

    @Override
    public void handleAnswer(String remoteNodeId, String sdp) {
        PeerContext context = peers.get(remoteNodeId);
        if (context == null)
            return;

        RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);
        context.pc.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                flushPendingIce(context);
                logger.info("Remote Answer set for " + remoteNodeId);
            }

            @Override
            public void onFailure(String error) {
                logger.severe("Failed to set Remote Description (Answer) for " + remoteNodeId + ": " + error);
            }
        });
    }

    @Override
    public void addIceCandidate(String remoteNodeId, String candidate, String sdpMid, int sdpMLineIndex) {
        PeerContext context = peers.get(remoteNodeId);
        if (context == null)
            return;

        RTCIceCandidate ice = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);

        if (context.remoteDescriptionSet.get()) {
            context.pc.addIceCandidate(ice);
        } else {
            context.pendingIce.add(ice);
        }
    }

    @Override
    public void disconnect(String remoteNodeId) {
        PeerContext context = peers.remove(remoteNodeId);
        if (context != null) {
            context.close();
        }
    }

    @Override
    public void closeAllConnections() {
        logger.info("Closing all peer connections (" + peers.size() + " peers)");
        for (String nodeId : new java.util.ArrayList<>(peers.keySet())) {
            disconnect(nodeId);
        }
        peers.clear();
    }

    // --- Helpers ---

    private void marshal(Runnable task) {
        if (!fsmExecutor.isShutdown()) {
            fsmExecutor.submit(task);
        }
    }

    private PeerContext ensurePeer(String nodeId) {
        return peers.computeIfAbsent(nodeId, id -> {
            logger.info("Creating new PeerContext for " + id);
            return new PeerContext(id);
        });
    }

    private void createDataChannels(PeerContext context) {
        // Control (Reliable, Ordered)
        RTCDataChannelInit initControl = new RTCDataChannelInit();
        initControl.ordered = true;
        initControl.id = 0;
        context.dcControl = context.pc.createDataChannel(LABEL_CONTROL, initControl);
        setupDataChannelObserver(context.dcControl, context, LABEL_CONTROL);

        // Data (Reliable, Ordered)
        RTCDataChannelInit initData = new RTCDataChannelInit();
        initData.ordered = true;
        context.dcData = context.pc.createDataChannel(LABEL_DATA, initData);
        setupDataChannelObserver(context.dcData, context, LABEL_DATA);

        // File (Unordered, Reliable)
        RTCDataChannelInit initFile = new RTCDataChannelInit();
        initFile.ordered = false;
        context.dcFile = context.pc.createDataChannel(LABEL_FILE, initFile);
        setupDataChannelObserver(context.dcFile, context, LABEL_FILE);
    }

    private void setupDataChannelObserver(IWebRTCDataChannel dc, PeerContext context, String label) {
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                RTCDataChannelState state = dc.getState();
                logger.info("DC " + label + " state: " + state);
                if (state == RTCDataChannelState.OPEN && label.equals(LABEL_CONTROL)) {
                    marshal(() -> {
                        if (listener != null)
                            listener.onConnectionStateChange(context.nodeId, true);
                    });
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                if (!buffer.binary)
                    return;
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);

                marshal(() -> {
                    try {
                        if (listener != null) {
                            Envelope env = Envelope.parseFrom(data);
                            listener.onDataMessage(context.nodeId, env);
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to parse Envelope from " + context.nodeId + ": " + e.getMessage());
                    }
                });
            }
        });
    }

    private void flushPendingIce(PeerContext context) {
        context.remoteDescriptionSet.set(true);
        RTCIceCandidate ice;
        while ((ice = context.pendingIce.poll()) != null) {
            context.pc.addIceCandidate(ice);
        }
    }

    // --- Inner Class: PeerContext ---

    private class PeerContext {
        final String nodeId;
        final IWebRTCPeerConnection pc;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final ConcurrentLinkedQueue<RTCIceCandidate> pendingIce = new ConcurrentLinkedQueue<>();
        final AtomicBoolean remoteDescriptionSet = new AtomicBoolean(false);

        IWebRTCDataChannel dcControl;
        IWebRTCDataChannel dcData;
        IWebRTCDataChannel dcFile;

        PeerContext(String nodeId) {
            this.nodeId = nodeId;

            RTCConfiguration config = new RTCConfiguration();
            RTCIceServer stun = new RTCIceServer();
            stun.urls.add("stun:stun.l.google.com:19302");
            config.iceServers.add(stun);

            this.pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    marshal(() -> {
                        if (listener != null) {
                            SignalRelayRequest.Builder builder = SignalRelayRequest.newBuilder();
                            builder.setIce(com.saferoom.rooms.grpc.IceCandidate.newBuilder()
                                    .setCandidate(candidate.sdp)
                                    .setSdpMid(candidate.sdpMid)
                                    .setSdpMLineIndex(candidate.sdpMLineIndex)
                                    .build());
                            listener.onLocalSignal(nodeId, builder.build());
                        }
                    });
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    logger.info("PC State " + nodeId + ": " + state);
                    if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                        marshal(() -> {
                            if (listener != null)
                                listener.onConnectionStateChange(nodeId, false);
                        });
                    }
                }

                @Override
                public void onDataChannel(RTCDataChannel dc) {
                    // Wrap the incoming native DC
                    IWebRTCDataChannel wrappedDc = new WebRTCDataChannelWrapper(dc);
                    handleDataChannel(wrappedDc);
                }

                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                }

                @Override
                public void onIceGatheringChange(RTCIceGatheringState state) {
                }

                @Override
                public void onTrack(RTCRtpTransceiver transceiver) {
                }

                @Override
                public void onRemoveTrack(RTCRtpReceiver receiver) {
                }
            });
        }

        private void handleDataChannel(IWebRTCDataChannel dc) {
            String label = dc.getLabel();
            logger.info("Remote opened DC: " + label);

            if (label.equals(LABEL_CONTROL))
                dcControl = dc;
            else if (label.equals(LABEL_DATA))
                dcData = dc;
            else if (label.equals(LABEL_FILE))
                dcFile = dc;

            setupDataChannelObserver(dc, PeerContext.this, label);
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                if (dcControl != null)
                    dcControl.close();
                if (dcData != null)
                    dcData.close();
                if (dcFile != null)
                    dcFile.close();
                if (pc != null)
                    pc.close();
            }
        }
    }
}
