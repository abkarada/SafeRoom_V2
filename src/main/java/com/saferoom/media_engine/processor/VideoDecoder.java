package com.saferoom.media_engine.processor;

/**
 * Decodes compressed video packets back into raw frames for rendering.
 */
public interface VideoDecoder extends MediaProcessor {

    /**
     * Decodes an encoded video packet.
     *
     * @param encodedData Encoded payload
     * @param timestamp Capture timestamp
     * @param sequenceNumber Packet sequence number
     * @return Decoded video frame
     */
    MediaFrame decode(byte[] encodedData, long timestamp, int sequenceNumber) throws Exception;
}

