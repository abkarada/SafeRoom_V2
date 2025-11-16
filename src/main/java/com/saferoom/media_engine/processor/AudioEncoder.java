package com.saferoom.media_engine.processor;

/**
 * Encodes raw audio frames (PCM) into compressed formats such as Opus.
 */
public interface AudioEncoder extends MediaProcessor {

    /**
     * Encodes a raw audio frame.
     *
     * @param frame Audio frame captured from a source
     * @return Encoded byte array ready for transport
     */
    byte[] encode(MediaFrame frame) throws Exception;
}

