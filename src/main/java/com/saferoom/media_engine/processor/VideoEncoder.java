package com.saferoom.media_engine.processor;

/**
 * Encodes raw video frames into compressed formats (VP8/VP9).
 */
public interface VideoEncoder extends MediaProcessor {

    /**
     * Encodes a raw video frame into a compressed format.
     *
     * @param frame Raw video frame
     * @return Encoded video data
     */
    byte[] encode(MediaFrame frame) throws Exception;

    /**
     * @return true if the last encoded frame was a key frame.
     */
    boolean isLastFrameKeyFrame();
}

