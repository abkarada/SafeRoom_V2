package com.saferoom.media_engine.processor;

/**
 * Decodes encoded audio payloads (e.g., Opus) back into PCM frames.
 */
public interface AudioDecoder extends MediaProcessor {

    /**
     * Decodes an encoded audio packet.
     *
     * @param encodedData Encoded bytes received from transport
     * @param timestamp Capture timestamp propagated through transport
     * @param sequenceNumber Packet sequence number (uint16 wrapping)
     * @return Decoded audio frame or null if the decoder chooses to drop the packet
     */
    MediaFrame decode(byte[] encodedData, long timestamp, int sequenceNumber) throws Exception;
}

