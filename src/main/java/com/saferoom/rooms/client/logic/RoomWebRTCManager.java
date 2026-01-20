package com.saferoom.rooms.client.logic;

import com.saferoom.rooms.grpc.SignalRelayRequest;
import com.saferoom.rooms.grpc.Envelope;

/**
 * Interface for WebRTC operations required by DataFSM.
 * Decouples FSM logic from actual PeerConnection implementation.
 */
public interface RoomWebRTCManager {

    /**
     * Initiates a WebRTC connection to a target node (Parent).
     * Should create PeerConnection, DataChannel, and generate Offer.
     */
    void initiateConnection(String remoteNodeId);

    /**
     * Handles an incoming Offer from a child.
     * Should create PeerConnection, set RemoteDesc, and generate Answer.
     */
    void handleOffer(String remoteNodeId, String sdp);

    /**
     * Handles an incoming Answer from a parent.
     * Should set RemoteDesc.
     */
    void handleAnswer(String remoteNodeId, String sdp);

    /**
     * Handles an incoming ICE candidate.
     */
    void addIceCandidate(String remoteNodeId, String candidate, String sdpMid, int sdpMLineIndex);

    /**
     * Disconnects a specific peer.
     */
    void disconnect(String remoteNodeId);

    /**
     * Closes all peer connections. Used when leaving a room.
     */
    void closeAllConnections();

    /**
     * Sets the listener for incoming DataChannel messages.
     */
    void setListener(Listener listener);

    void broadcastData(Envelope envelope);

    interface Listener {
        // Called when Local Signal (Offer/Answer/ICE) is generated and needs sending
        // via gRPC
        void onLocalSignal(String remoteNodeId, SignalRelayRequest signal);

        // Called when DataChannel receives data
        void onDataMessage(String remoteNodeId, Envelope envelope);

        // Called when DataChannel state changes (OPEN/CLOSE)
        void onConnectionStateChange(String remoteNodeId, boolean connected);
    }
}
