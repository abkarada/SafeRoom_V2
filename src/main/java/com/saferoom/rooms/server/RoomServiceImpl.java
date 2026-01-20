package com.saferoom.rooms.server;

import com.saferoom.rooms.grpc.*;
import com.saferoom.rooms.grpc.RoomServiceGrpc.RoomServiceImplBase;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Implementation of Rooms v1 Signaling Service.
 * Handles Join/Leave/Relay and Voice Presence.
 */
public class RoomServiceImpl extends RoomServiceImplBase {

    private static final Logger logger = Logger.getLogger(RoomServiceImpl.class.getName());

    // RoomId -> NodeId -> Observer (For routing signals)
    private final Map<String, Map<String, StreamObserver<RoomEvent>>> roomSessions = new ConcurrentHashMap<>();

    // RoomId -> NodeId -> Boolean (Voice Presence)
    private final Map<String, Map<String, Boolean>> voicePresence = new ConcurrentHashMap<>();

    // RoomId -> Current Epoch
    private final Map<String, Long> roomEpochs = new ConcurrentHashMap<>();

    // RoomId -> NodeId -> RoomPeer (For active member list - Online users)
    private final Map<String, Map<String, RoomPeer>> activePeers = new ConcurrentHashMap<>();

    @Override
    public void createRoom(CreateRoomRequest request, StreamObserver<CreateRoomResponse> responseObserver) {
        String name = request.getName();
        String ownerId = request.getOwnerNodeId();
        boolean isPrivate = request.getIsPrivate();
        String roomId = java.util.UUID.randomUUID().toString();

        logger.info("[ROOM] CreateRoom request: name=" + name + ", owner=" + ownerId + ", private=" + isPrivate);

        try {
            // Persist to DB
            if (com.saferoom.db.DBManager.createRoom(roomId, name, ownerId, isPrivate)) {
                // Auto-join owner as admin
                com.saferoom.db.DBManager.addRoomMember(roomId, ownerId);

                logger.info("[ROOM] ✅ Room created successfully: " + roomId + " (" + name + ")");

                RoomMetadata meta = RoomMetadata.newBuilder()
                        .setRoomId(roomId)
                        .setName(name)
                        .setOwnerNodeId(ownerId)
                        .setIsPrivate(isPrivate)
                        .setCreatedAt(System.currentTimeMillis())
                        .build();

                responseObserver.onNext(CreateRoomResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Room created")
                        .setRoom(meta)
                        .build());
            } else {
                logger.warning("[ROOM] ❌ Failed to persist room: " + name);
                responseObserver.onNext(CreateRoomResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Failed to persist room")
                        .build());
            }
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ CreateRoom error: " + e.getMessage());
            e.printStackTrace();
            responseObserver.onNext(CreateRoomResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void listRooms(ListRoomsRequest request, StreamObserver<ListRoomsResponse> responseObserver) {
        logger.info("[ROOM] ListRooms request: query='" + request.getSearchQuery() + "'");
        try {
            java.util.List<RoomMetadata> rooms = com.saferoom.db.DBManager.getRooms(request.getSearchQuery());
            logger.info("[ROOM] ListRooms: returning " + rooms.size() + " rooms");
            responseObserver.onNext(ListRoomsResponse.newBuilder()
                    .addAllRooms(rooms)
                    .build());
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ ListRooms error: " + e.getMessage());
            responseObserver.onNext(ListRoomsResponse.newBuilder().build()); // Return empty on error
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getRoomInfo(GetRoomInfoRequest request, StreamObserver<GetRoomInfoResponse> responseObserver) {
        try {
            RoomMetadata room = com.saferoom.db.DBManager.getRoom(request.getRoomId());
            if (room != null) {
                responseObserver.onNext(GetRoomInfoResponse.newBuilder()
                        .setExists(true)
                        .setRoom(room)
                        .build());
            } else {
                responseObserver.onNext(GetRoomInfoResponse.newBuilder()
                        .setExists(false)
                        .build());
            }
        } catch (Exception e) {
            logger.severe("GetRoomInfo error: " + e.getMessage());
            responseObserver.onNext(GetRoomInfoResponse.newBuilder().setExists(false).build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void joinRoom(JoinRoomRequest request, StreamObserver<JoinRoomResponse> responseObserver) {
        String roomId = request.getRoomId();
        String nodeId = request.getNodeId();

        logger.info("[ROOM] JoinRoom request: nodeId=" + nodeId + ", roomId=" + roomId);

        try {
            // 1. Check if room exists
            RoomMetadata room = com.saferoom.db.DBManager.getRoom(roomId);
            if (room == null) {
                logger.warning("[ROOM] ❌ Room not found: " + roomId);
                responseObserver.onNext(JoinRoomResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Room not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // 2. Persist membership
            com.saferoom.db.DBManager.addRoomMember(roomId, nodeId);

            // 3. Init runtime state (Active Session)
            roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
            voicePresence.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
            long currentEpoch = roomEpochs.computeIfAbsent(roomId, k -> 1L);

            // Add self to active list
            RoomPeer peer = RoomPeer.newBuilder()
                    .setNodeId(nodeId)
                    .setPubKey(request.getPubKey())
                    .setRole("LEAF")
                    .build();

            addActivePeer(roomId, nodeId, peer);

            // Success response
            JoinRoomResponse response = JoinRoomResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Joined")
                    .setCurrentEpoch(currentEpoch)
                    .setSelfRole(peer)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            broadcastPresence(roomId);

        } catch (Exception e) {
            logger.severe("JoinRoom error: " + e.getMessage());
            responseObserver.onNext(JoinRoomResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    private void addActivePeer(String roomId, String nodeId, RoomPeer peer) {
        activePeers.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(nodeId, peer);
    }

    @Override
    public void streamEvents(StreamEventsRequest request, StreamObserver<RoomEvent> responseObserver) {
        String roomId = request.getRoomId();
        String nodeId = request.getNodeId();

        logger.info("StreamEvents active: " + nodeId);

        // Register active session for routing
        Map<String, StreamObserver<RoomEvent>> sessions = roomSessions.computeIfAbsent(roomId,
                k -> new ConcurrentHashMap<>());
        sessions.put(nodeId, responseObserver);

        // Send initial state snapshots immediately
        sendSnapshot(roomId, nodeId, responseObserver);
    }

    @Override
    public void leaveRoom(LeaveRoomRequest request, StreamObserver<LeaveRoomResponse> responseObserver) {
        String roomId = request.getRoomId();
        String nodeId = request.getNodeId();

        // Remove from memory (Offline)
        removeUser(roomId, nodeId);

        responseObserver.onNext(LeaveRoomResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void joinVoice(JoinVoiceRequest request, StreamObserver<JoinVoiceResponse> responseObserver) {
        String roomId = request.getRoomId();
        String nodeId = request.getNodeId();

        voicePresence.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(nodeId, true);
        broadcastVoice(roomId);

        responseObserver.onNext(JoinVoiceResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void leaveVoice(LeaveVoiceRequest request, StreamObserver<LeaveVoiceResponse> responseObserver) {
        String roomId = request.getRoomId();
        String nodeId = request.getNodeId();

        if (voicePresence.containsKey(roomId)) {
            voicePresence.get(roomId).remove(nodeId);
            broadcastVoice(roomId);
        }

        responseObserver.onNext(LeaveVoiceResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getSeeds(GetSeedsRequest request, StreamObserver<GetSeedsResponse> responseObserver) {
        String roomId = request.getRoomId();
        long epoch = roomEpochs.getOrDefault(roomId, 1L);

        // Return random 3 routers (For now just first 3 peers as simplified logic)
        Map<String, RoomPeer> peers = activePeers.get(roomId);
        GetSeedsResponse.Builder builder = GetSeedsResponse.newBuilder().setEpoch(epoch);

        if (peers != null) {
            peers.values().stream().limit(5).forEach(builder::addSeedRouters);
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void relaySignal(SignalRelayRequest request, StreamObserver<SignalRelayResponse> responseObserver) {
        String roomId = request.getRoomId();
        String targetNodeId = request.getDstNodeId();

        Map<String, StreamObserver<RoomEvent>> sessions = roomSessions.get(roomId);
        if (sessions != null && sessions.containsKey(targetNodeId)) {
            StreamObserver<RoomEvent> target = sessions.get(targetNodeId);

            RoomEvent event = RoomEvent.newBuilder()
                    .setType(RoomEvent.EventType.SIGNAL_RELAY)
                    .setTimestamp(System.currentTimeMillis())
                    .setSignal(request) // Pass the full typed request
                    .build();

            synchronized (target) {
                target.onNext(event);
            }
            responseObserver.onNext(SignalRelayResponse.newBuilder().setSuccess(true).build());
        } else {
            responseObserver.onNext(SignalRelayResponse.newBuilder().setSuccess(false).build());
        }

        responseObserver.onCompleted();
    }

    // --- Helpers ---

    private void removeUser(String roomId, String nodeId) {
        if (activePeers.containsKey(roomId))
            activePeers.get(roomId).remove(nodeId);
        if (roomSessions.containsKey(roomId))
            roomSessions.get(roomId).remove(nodeId);
        if (voicePresence.containsKey(roomId))
            voicePresence.get(roomId).remove(nodeId);

        broadcastPresence(roomId);
        broadcastVoice(roomId);
    }

    private void sendSnapshot(String roomId, String nodeId, StreamObserver<RoomEvent> observer) {
        // Presence
        Map<String, RoomPeer> peers = activePeers.get(roomId);
        if (peers != null) {
            RoomPresence p = RoomPresence.newBuilder()
                    .setRoomId(roomId)
                    .addAllPeers(peers.values())
                    .setConnectedPeersCount(peers.size())
                    .build();
            observer.onNext(RoomEvent.newBuilder()
                    .setType(RoomEvent.EventType.ROOM_PRESENCE)
                    .setTimestamp(System.currentTimeMillis())
                    .setPresence(p)
                    .build());
        }
        // Voice
        broadcastVoiceSingle(roomId, observer);
    }

    private void broadcastPresence(String roomId) {
        if (!roomSessions.containsKey(roomId))
            return;
        Map<String, RoomPeer> peers = activePeers.get(roomId);
        if (peers == null)
            return;

        RoomPresence p = RoomPresence.newBuilder()
                .setRoomId(roomId)
                .addAllPeers(peers.values())
                .setConnectedPeersCount(peers.size())
                .build();

        RoomEvent event = RoomEvent.newBuilder()
                .setType(RoomEvent.EventType.ROOM_PRESENCE)
                .setTimestamp(System.currentTimeMillis())
                .setPresence(p)
                .build();

        notifyRoom(roomId, event);
    }

    private void broadcastVoice(String roomId) {
        if (!roomSessions.containsKey(roomId))
            return;
        Map<String, Boolean> voiceMap = voicePresence.get(roomId);

        VoicePresence v = VoicePresence.newBuilder()
                .setRoomId(roomId)
                .addAllVoicePeerIds(voiceMap != null ? voiceMap.keySet() : java.util.Collections.emptyList())
                .build();

        RoomEvent event = RoomEvent.newBuilder()
                .setType(RoomEvent.EventType.VOICE_PRESENCE)
                .setTimestamp(System.currentTimeMillis())
                .setVoice(v)
                .build();

        notifyRoom(roomId, event);
    }

    private void broadcastVoiceSingle(String roomId, StreamObserver<RoomEvent> target) {
        Map<String, Boolean> voiceMap = voicePresence.get(roomId);
        VoicePresence v = VoicePresence.newBuilder()
                .setRoomId(roomId)
                .addAllVoicePeerIds(voiceMap != null ? voiceMap.keySet() : java.util.Collections.emptyList())
                .build();
        target.onNext(RoomEvent.newBuilder()
                .setType(RoomEvent.EventType.VOICE_PRESENCE)
                .setTimestamp(System.currentTimeMillis())
                .setVoice(v)
                .build());
    }

    private void notifyRoom(String roomId, RoomEvent event) {
        Map<String, StreamObserver<RoomEvent>> sessions = roomSessions.get(roomId);
        if (sessions == null) {
            logger.warning("[ROOM] notifyRoom: No sessions map for roomId=" + roomId);
            return;
        }
        logger.info("[ROOM] notifyRoom: Broadcasting " + event.getType() + " to " + sessions.size() + " sessions in "
                + roomId);

        sessions.values().forEach(observer -> {
            try {
                synchronized (observer) {
                    observer.onNext(event);
                }
            } catch (Exception e) {
                logger.warning("[ROOM] notifyRoom error for peer: " + e.getMessage());
            }
        });
    }

    // =============================================================================
    // SPRINT 13: CHANNEL MANAGEMENT RPCs
    // =============================================================================

    @Override
    public void createChannel(CreateChannelRequest request, StreamObserver<CreateChannelResponse> responseObserver) {
        String roomId = request.getRoomId();
        String name = request.getName();
        String type = request.getType();
        String category = request.getCategory();
        String channelId = java.util.UUID.randomUUID().toString();

        logger.info("[ROOM] CreateChannel: name=" + name + ", type=" + type + ", roomId=" + roomId);

        try {
            if (com.saferoom.db.DBManager.createChannel(channelId, roomId, name, type, category)) {
                logger.info("[ROOM] ✅ Channel created: " + channelId + " (" + name + ")");

                ChannelMetadata meta = ChannelMetadata.newBuilder()
                        .setChannelId(channelId)
                        .setRoomId(roomId)
                        .setName(name)
                        .setType(type)
                        .setCategory(category)
                        .setPosition(0)
                        .build();

                responseObserver.onNext(CreateChannelResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Channel created")
                        .setChannel(meta)
                        .build());

                // Sprint 15: Broadcast CHANNEL_CREATED event
                notifyRoom(roomId, RoomEvent.newBuilder()
                        .setType(RoomEvent.EventType.CHANNEL_CREATED)
                        .setTimestamp(System.currentTimeMillis())
                        .setChannel(meta)
                        .setMsgId(java.util.UUID.randomUUID().toString())
                        .build());
            } else {
                logger.warning("[ROOM] ❌ Failed to create channel: " + name);
                responseObserver.onNext(CreateChannelResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Failed to create channel")
                        .build());
            }
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ CreateChannel error: " + e.getMessage());
            responseObserver.onNext(CreateChannelResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void listChannels(ListChannelsRequest request, StreamObserver<ListChannelsResponse> responseObserver) {
        String roomId = request.getRoomId();
        logger.info("[ROOM] ListChannels: roomId=" + roomId);

        try {
            java.util.List<com.saferoom.db.DBManager.ChannelInfo> dbChannels = com.saferoom.db.DBManager
                    .getChannels(roomId);

            ListChannelsResponse.Builder builder = ListChannelsResponse.newBuilder();
            for (var ch : dbChannels) {
                builder.addChannels(ChannelMetadata.newBuilder()
                        .setChannelId(ch.channelId)
                        .setRoomId(ch.roomId)
                        .setName(ch.name)
                        .setType(ch.type)
                        .setCategory(ch.category)
                        .setPosition(ch.position)
                        .build());
            }

            logger.info("[ROOM] ListChannels: returning " + dbChannels.size() + " channels");
            responseObserver.onNext(builder.build());
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ ListChannels error: " + e.getMessage());
            responseObserver.onNext(ListChannelsResponse.newBuilder().build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void deleteChannel(DeleteChannelRequest request, StreamObserver<DeleteChannelResponse> responseObserver) {
        String channelId = request.getChannelId();
        logger.info("[ROOM] DeleteChannel: channelId=" + channelId);

        try {
            // Find roomId before deleting
            com.saferoom.db.DBManager.ChannelInfo ch = com.saferoom.db.DBManager.getChannel(channelId);
            String roomId = (ch != null) ? ch.roomId : null;

            if (com.saferoom.db.DBManager.deleteChannel(channelId)) {
                logger.info("[ROOM] ✅ Channel deleted: " + channelId);
                responseObserver.onNext(DeleteChannelResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Channel deleted")
                        .build());

                // Sprint 15: Broadcast CHANNEL_DELETED event
                if (roomId != null) {
                    notifyRoom(roomId, RoomEvent.newBuilder()
                            .setType(RoomEvent.EventType.CHANNEL_DELETED)
                            .setTimestamp(System.currentTimeMillis())
                            .setChannelId(channelId)
                            .setMsgId(java.util.UUID.randomUUID().toString())
                            .build());
                }
            } else {
                responseObserver.onNext(DeleteChannelResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Channel not found")
                        .build());
            }
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ DeleteChannel error: " + e.getMessage());
            responseObserver.onNext(DeleteChannelResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    // =============================================================================
    // SPRINT 14: MESSAGE MANAGEMENT RPCs
    // =============================================================================

    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        String channelId = request.getChannelId();
        String sender = request.getSenderUsername();
        String content = request.getContent();
        String messageId = java.util.UUID.randomUUID().toString();

        logger.info("[ROOM] SendMessage: channelId=" + channelId + ", sender=" + sender);

        try {
            // Disable Persistence (User Request)
            // if (com.saferoom.db.DBManager.saveMessage(messageId, channelId, sender,
            // content)) {

            logger.info("[ROOM] Broker Message (No Persistence): " + messageId);

            ChatMessage msg = ChatMessage.newBuilder()
                    .setMessageId(messageId)
                    .setChannelId(channelId)
                    .setSenderUsername(sender)
                    .setContent(content)
                    .setSentAt(System.currentTimeMillis())
                    .build();

            responseObserver.onNext(SendMessageResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Message relayed")
                    .setSavedMessage(msg)
                    .build());

            // Sprint 15: Broadcast MESSAGE_RECEIVED event
            try {
                com.saferoom.db.DBManager.ChannelInfo ch = com.saferoom.db.DBManager.getChannel(channelId);
                if (ch != null) {
                    notifyRoom(ch.roomId, RoomEvent.newBuilder()
                            .setType(RoomEvent.EventType.MESSAGE_RECEIVED)
                            .setTimestamp(System.currentTimeMillis())
                            .setMessage(msg)
                            .setMsgId(java.util.UUID.randomUUID().toString())
                            .build());
                } else {
                    logger.warning("[ROOM] Channel not found for broadcasting: " + channelId);
                }
            } catch (Exception ex) {
                logger.warning("[ROOM] Failed to broadcast message event: " + ex.getMessage());
            }

        } catch (Exception e) {
            logger.severe("[ROOM] ❌ SendMessage error: " + e.getMessage());
            responseObserver.onNext(SendMessageResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getMessages(GetMessagesRequest request, StreamObserver<GetMessagesResponse> responseObserver) {
        String channelId = request.getChannelId();
        int limit = request.getLimit() > 0 ? request.getLimit() : 50;

        logger.info("[ROOM] GetMessages: channelId=" + channelId + ", limit=" + limit);

        try {
            // Disable Persistence (User Request)
            logger.info("[ROOM] GetMessages: Persistence disabled, returning empty list.");
            GetMessagesResponse.Builder builder = GetMessagesResponse.newBuilder();
            responseObserver.onNext(builder.build());
        } catch (Exception e) {
            logger.severe("[ROOM] ❌ GetMessages error: " + e.getMessage());
            responseObserver.onNext(GetMessagesResponse.newBuilder().build());
        }
        responseObserver.onCompleted();
    }
}
