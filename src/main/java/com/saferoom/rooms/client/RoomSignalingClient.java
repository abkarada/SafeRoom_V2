package com.saferoom.rooms.client;

import com.saferoom.rooms.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client wrapper for Room Signaling gRPC Service.
 * Provides easy API for UI/Logic to join rooms and receive events.
 */
public class RoomSignalingClient {

    private static final Logger logger = Logger.getLogger(RoomSignalingClient.class.getName());

    private ManagedChannel channel;
    private RoomServiceGrpc.RoomServiceBlockingStub blockingStub; // For synchronous calls (Join, Seeds)
    private RoomServiceGrpc.RoomServiceStub asyncStub; // For streaming (Events)

    private final List<RoomEventListener> listeners = new ArrayList<>();
    private StreamObserver<StreamEventsRequest> eventStream; // Request stream to server (if we needed client-stream,
                                                             // but server-stream is unary-request)

    public interface RoomEventListener {
        void onEvent(RoomEvent event);

        void onDisconnected();
    }

    public void connect(String host, int port) {
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        blockingStub = RoomServiceGrpc.newBlockingStub(channel);
        asyncStub = RoomServiceGrpc.newStub(channel);
        logger.info("Connected to Room Server at " + host + ":" + port);
    }

    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // --- Actions ---

    public CreateRoomResponse createRoom(String name, String ownerNodeId, boolean isPrivate) {
        CreateRoomRequest request = CreateRoomRequest.newBuilder()
                .setName(name)
                .setOwnerNodeId(ownerNodeId)
                .setIsPrivate(isPrivate)
                .build();
        if (blockingStub == null) {
            throw new IllegalStateException("RoomSignalingClient not connected. Call connect() first.");
        }
        return blockingStub.createRoom(request);
    }

    public ListRoomsResponse listRooms(String searchQuery) {
        ListRoomsRequest request = ListRoomsRequest.newBuilder()
                .setSearchQuery(searchQuery == null ? "" : searchQuery)
                .build();
        if (blockingStub == null) {
            return ListRoomsResponse.newBuilder().build(); // Return empty instead of crash
        }
        return blockingStub.listRooms(request);
    }

    public GetRoomInfoResponse getRoomInfo(String roomId) {
        GetRoomInfoRequest request = GetRoomInfoRequest.newBuilder()
                .setRoomId(roomId)
                .build();
        return blockingStub.getRoomInfo(request);
    }

    public JoinRoomResponse joinRoom(String roomId, String nodeId, String pubKey) {
        JoinRoomRequest request = JoinRoomRequest.newBuilder()
                .setRoomId(roomId)
                .setNodeId(nodeId)
                .setPubKey(pubKey)
                .build();

        try {
            JoinRoomResponse response = blockingStub.joinRoom(request);
            if (response.getSuccess()) {
                startEventStream(roomId, nodeId);
            }
            return response;
        } catch (Exception e) {
            logger.log(Level.WARNING, "RPC failed: joinRoom", e);
            throw e;
        }
    }

    public void leaveRoom(String roomId, String nodeId) {
        LeaveRoomRequest request = LeaveRoomRequest.newBuilder()
                .setRoomId(roomId)
                .setNodeId(nodeId)
                .build();
        try {
            blockingStub.leaveRoom(request);
        } catch (Exception e) {
            logger.log(Level.WARNING, "RPC failed: leaveRoom", e);
        }
    }

    public void joinVoice(String roomId, String nodeId) {
        JoinVoiceRequest request = JoinVoiceRequest.newBuilder().setRoomId(roomId).setNodeId(nodeId).build();
        blockingStub.joinVoice(request);
    }

    public void leaveVoice(String roomId, String nodeId) {
        LeaveVoiceRequest request = LeaveVoiceRequest.newBuilder().setRoomId(roomId).setNodeId(nodeId).build();
        blockingStub.leaveVoice(request);
    }

    public GetSeedsResponse getSeeds(String roomId, long currentEpoch) {
        GetSeedsRequest request = GetSeedsRequest.newBuilder()
                .setRoomId(roomId)
                .setKnownEpoch(currentEpoch)
                .build();
        return blockingStub.getSeeds(request);
    }

    public void sendOffer(String roomId, String fromNodeId, String toNodeId, String sdp) {
        SdpOffer offer = SdpOffer.newBuilder().setSdp(sdp).build();
        SignalRelayRequest request = SignalRelayRequest.newBuilder()
                .setRoomId(roomId)
                .setSrcNodeId(fromNodeId)
                .setDstNodeId(toNodeId)
                .setOffer(offer)
                .build();
        sendSignalAsync(request);
    }

    public void sendAnswer(String roomId, String fromNodeId, String toNodeId, String sdp) {
        SdpAnswer answer = SdpAnswer.newBuilder().setSdp(sdp).build();
        SignalRelayRequest request = SignalRelayRequest.newBuilder()
                .setRoomId(roomId)
                .setSrcNodeId(fromNodeId)
                .setDstNodeId(toNodeId)
                .setAnswer(answer)
                .build();
        sendSignalAsync(request);
    }

    public void sendIceCandidate(String roomId, String fromNodeId, String toNodeId, String candidate, String sdpMid,
            int sdpMLineIndex) {
        IceCandidate ice = IceCandidate.newBuilder()
                .setCandidate(candidate)
                .setSdpMid(sdpMid)
                .setSdpMLineIndex(sdpMLineIndex)
                .build();

        SignalRelayRequest request = SignalRelayRequest.newBuilder()
                .setRoomId(roomId)
                .setSrcNodeId(fromNodeId)
                .setDstNodeId(toNodeId)
                .setIce(ice)
                .build();
        sendSignalAsync(request);
    }

    private void sendSignalAsync(SignalRelayRequest request) {
        asyncStub.relaySignal(request, new StreamObserver<SignalRelayResponse>() {
            @Override
            public void onNext(SignalRelayResponse value) {
            }

            @Override
            public void onError(Throwable t) {
                logger.warning("Relay failed: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    // --- Event Streaming ---

    private void startEventStream(String roomId, String nodeId) {
        StreamEventsRequest request = StreamEventsRequest.newBuilder()
                .setRoomId(roomId)
                .setNodeId(nodeId)
                .build();

        asyncStub.streamEvents(request, new StreamObserver<RoomEvent>() {
            @Override
            public void onNext(RoomEvent event) {
                notifyListeners(event);
            }

            @Override
            public void onError(Throwable t) {
                logger.warning("Event stream error: " + t.getMessage());
                notifyDisconnected();
            }

            @Override
            public void onCompleted() {
                logger.info("Event stream completed");
                notifyDisconnected();
            }
        });
    }

    // --- Listeners ---

    public void addListener(RoomEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RoomEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(RoomEvent event) {
        for (RoomEventListener l : listeners) {
            l.onEvent(event);
        }
    }

    private void notifyDisconnected() {
        for (RoomEventListener l : listeners) {
            l.onDisconnected();
        }
    }

    // =============================================================================
    // SPRINT 13: CHANNEL MANAGEMENT RPC METHODS
    // =============================================================================

    public CreateChannelResponse createChannel(String roomId, String name, String type, String category) {
        if (blockingStub == null) {
            throw new IllegalStateException("RoomSignalingClient not connected. Call connect() first.");
        }
        CreateChannelRequest request = CreateChannelRequest.newBuilder()
                .setRoomId(roomId)
                .setName(name)
                .setType(type)
                .setCategory(category == null ? "GENERAL" : category)
                .build();
        logger.info("[RPC] CreateChannel: " + name + " in room " + roomId);
        return blockingStub.createChannel(request);
    }

    public ListChannelsResponse listChannels(String roomId) {
        if (blockingStub == null) {
            return ListChannelsResponse.newBuilder().build(); // Return empty if not connected
        }
        ListChannelsRequest request = ListChannelsRequest.newBuilder()
                .setRoomId(roomId)
                .build();
        logger.info("[RPC] ListChannels for room: " + roomId);
        return blockingStub.listChannels(request);
    }

    public DeleteChannelResponse deleteChannel(String channelId) {
        if (blockingStub == null) {
            throw new IllegalStateException("RoomSignalingClient not connected. Call connect() first.");
        }
        DeleteChannelRequest request = DeleteChannelRequest.newBuilder()
                .setChannelId(channelId)
                .build();
        logger.info("[RPC] DeleteChannel: " + channelId);
        return blockingStub.deleteChannel(request);
    }

    // =============================================================================
    // SPRINT 14: MESSAGE MANAGEMENT RPC METHODS
    // =============================================================================

    public SendMessageResponse sendMessageToChannel(String channelId, String senderUsername, String content) {
        if (blockingStub == null) {
            throw new IllegalStateException("RoomSignalingClient not connected. Call connect() first.");
        }
        SendMessageRequest request = SendMessageRequest.newBuilder()
                .setChannelId(channelId)
                .setSenderUsername(senderUsername)
                .setContent(content)
                .build();
        logger.info("[RPC] SendMessage to channel: " + channelId);
        return blockingStub.sendMessage(request);
    }

    public GetMessagesResponse getMessages(String channelId, int limit) {
        if (blockingStub == null) {
            return GetMessagesResponse.newBuilder().build(); // Return empty if not connected
        }
        GetMessagesRequest request = GetMessagesRequest.newBuilder()
                .setChannelId(channelId)
                .setLimit(limit > 0 ? limit : 50)
                .setBeforeTimestamp(0) // 0 = get latest
                .build();
        logger.info("[RPC] GetMessages for channel: " + channelId + ", limit=" + limit);
        return blockingStub.getMessages(request);
    }
}
