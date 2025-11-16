package com.saferoom.media_engine.transport;

/**
 * Media packet type enumeration
 * 
 * Defines the type of media data contained in a transport packet.
 * This allows the transport layer to route packets to the correct
 * decoder without inspecting payload content.
 */
public enum MediaPacketType {
    /**
     * Audio packet (Opus encoded)
     */
    AUDIO(0),
    
    /**
     * Video packet (VP8/VP9 encoded)
     */
    VIDEO(1),
    
    /**
     * Metadata packet (JSON format)
     * Used for signaling (mute/unmute, resolution changes, etc.)
     */
    METADATA(2);
    
    private final int value;
    
    MediaPacketType(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    /**
     * Convert integer value to MediaPacketType
     * 
     * @param value Integer representation
     * @return Corresponding MediaPacketType
     * @throws IllegalArgumentException if value is invalid
     */
    public static MediaPacketType fromValue(int value) {
        for (MediaPacketType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid MediaPacketType value: " + value);
    }
}
