package com.saferoom.webrtc.relay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MediaPacket - Core packet structure for SDRT (Serverless Distributed Relay Tree)
 * 
 * This class represents a single media packet that can be forwarded across the tree
 * without decoding/re-encoding. Each packet contains encrypted RTP data and metadata.
 * 
 * Packet Format (Binary):
 * [1 byte: type][4 bytes: sourceId length][N bytes: sourceId][8 bytes: timestamp]
 * [4 bytes: sequence][4 bytes: payload length][M bytes: encrypted payload]
 * 
 * Total overhead: ~21 bytes + sourceId length (typically 36 bytes for UUID)
 * Average packet size: ~1500 bytes (MTU optimized)
 */
public class MediaPacket {
    
    /**
     * Packet types for different media streams
     */
    public enum PacketType {
        VIDEO((byte) 0x01),      // Video RTP packet
        AUDIO((byte) 0x02),      // Audio RTP packet
        CONTROL((byte) 0x03),    // Control messages (ICE, metrics, etc.)
        HEARTBEAT((byte) 0x04),  // Keep-alive for connection monitoring
        KEY_ROTATION((byte) 0x05); // Group key update notification
        
        private final byte value;
        
        PacketType(byte value) {
            this.value = value;
        }
        
        public byte getValue() {
            return value;
        }
        
        public static PacketType fromByte(byte b) {
            for (PacketType type : values()) {
                if (type.value == b) return type;
            }
            throw new IllegalArgumentException("Unknown packet type: " + b);
        }
    }
    
    // Packet header fields
    private final PacketType type;
    private final String sourceId;       // UUID of the sender (Room member)
    private final long timestamp;        // RTP timestamp (microseconds)
    private final int sequenceNumber;    // Packet sequence for ordering
    private final byte[] payload;        // Encrypted RTP data
    
    // Room context (not serialized, set during processing)
    private transient String roomId;
    
    /**
     * Constructor for creating new outgoing packets
     */
    public MediaPacket(PacketType type, String sourceId, long timestamp, 
                       int sequenceNumber, byte[] payload) {
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Source ID cannot be null or empty");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        
        this.type = type;
        this.sourceId = sourceId;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.payload = payload;
    }
    
    /**
     * Serialize packet to binary format for transmission over DataChannel
     * 
     * @return ByteBuffer containing serialized packet (ready to send)
     */
    public ByteBuffer serialize() {
        byte[] sourceIdBytes = sourceId.getBytes(StandardCharsets.UTF_8);
        
        // Calculate total size
        int totalSize = 1                          // type
                      + 4                          // sourceId length
                      + sourceIdBytes.length       // sourceId
                      + 8                          // timestamp
                      + 4                          // sequence number
                      + 4                          // payload length
                      + payload.length;            // payload
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        // Write packet header
        buffer.put(type.getValue());
        buffer.putInt(sourceIdBytes.length);
        buffer.put(sourceIdBytes);
        buffer.putLong(timestamp);
        buffer.putInt(sequenceNumber);
        
        // Write payload
        buffer.putInt(payload.length);
        buffer.put(payload);
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Deserialize packet from binary format received from DataChannel
     * 
     * @param buffer ByteBuffer containing received packet data
     * @return Deserialized MediaPacket object
     * @throws IllegalArgumentException if buffer format is invalid
     */
    public static MediaPacket deserialize(ByteBuffer buffer) {
        try {
            // Read type
            byte typeByte = buffer.get();
            PacketType type = PacketType.fromByte(typeByte);
            
            // Read sourceId
            int sourceIdLength = buffer.getInt();
            if (sourceIdLength <= 0 || sourceIdLength > 1024) {
                throw new IllegalArgumentException("Invalid sourceId length: " + sourceIdLength);
            }
            byte[] sourceIdBytes = new byte[sourceIdLength];
            buffer.get(sourceIdBytes);
            String sourceId = new String(sourceIdBytes, StandardCharsets.UTF_8);
            
            // Read timestamp and sequence
            long timestamp = buffer.getLong();
            int sequenceNumber = buffer.getInt();
            
            // Read payload
            int payloadLength = buffer.getInt();
            if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) { // Max 10MB
                throw new IllegalArgumentException("Invalid payload length: " + payloadLength);
            }
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);
            
            return new MediaPacket(type, sourceId, timestamp, sequenceNumber, payload);
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize MediaPacket: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert packet to byte array (convenience method)
     */
    public byte[] toBytes() {
        ByteBuffer buffer = serialize();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
    
    /**
     * Create packet from byte array (convenience method)
     */
    public static MediaPacket fromBytes(byte[] bytes) {
        return deserialize(ByteBuffer.wrap(bytes));
    }
    
    // Getters
    public PacketType getType() {
        return type;
    }
    
    public String getSourceId() {
        return sourceId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public int getSequenceNumber() {
        return sequenceNumber;
    }
    
    public byte[] getPayload() {
        return payload;
    }
    
    public String getRoomId() {
        return roomId;
    }
    
    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
    
    /**
     * Get packet size in bytes (for bandwidth monitoring)
     */
    public int getSize() {
        return 1 + 4 + sourceId.getBytes(StandardCharsets.UTF_8).length 
               + 8 + 4 + 4 + payload.length;
    }
    
    /**
     * Check if this is a media packet (video/audio)
     */
    public boolean isMediaPacket() {
        return type == PacketType.VIDEO || type == PacketType.AUDIO;
    }
    
    /**
     * Check if this is a control packet
     */
    public boolean isControlPacket() {
        return type == PacketType.CONTROL || type == PacketType.HEARTBEAT 
               || type == PacketType.KEY_ROTATION;
    }
    
    @Override
    public String toString() {
        return String.format("MediaPacket{type=%s, sourceId=%s, timestamp=%d, seq=%d, size=%d bytes, roomId=%s}",
                type, sourceId, timestamp, sequenceNumber, getSize(), roomId);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MediaPacket)) return false;
        MediaPacket other = (MediaPacket) obj;
        return type == other.type 
               && sourceId.equals(other.sourceId)
               && timestamp == other.timestamp
               && sequenceNumber == other.sequenceNumber
               && Arrays.equals(payload, other.payload);
    }
    
    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + sourceId.hashCode();
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        result = 31 * result + sequenceNumber;
        return result;
    }
}
