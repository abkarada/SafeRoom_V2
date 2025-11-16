package com.saferoom.media_engine.core;

import com.saferoom.media_engine.transport.MediaTransport;

/**
 * Callbacks for lifecycle and participant events emitted by {@link MediaSession}.
 */
public interface MediaSessionListener {

    default void onSessionStarted(String sessionId) {}

    default void onSessionStopped(String sessionId) {}

    default void onParticipantJoined(String sessionId, String participantId) {}

    default void onParticipantLeft(String sessionId, String participantId) {}

    default void onError(String sessionId, String message, Throwable cause) {}

    default void onTransportError(String sessionId,
                                  MediaTransport.TransportError error,
                                  String message) {}
}

