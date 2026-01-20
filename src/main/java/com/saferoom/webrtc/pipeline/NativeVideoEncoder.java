package com.saferoom.webrtc.pipeline;

import com.saferoom.util.NativeLibraryLoader;
import java.nio.ByteBuffer;

/**
 * JNI-based Video Encoder Implementation.
 * 
 * <h2>Zero-Copy Architecture</h2>
 * <p>
 * This class serves as the bridge to the native C++ encoding layer.
 * By passing the {@link java.nio.DirectByteBuffer} directly to native code,
 * we avoid all JVM-heap copies. The native code can read the YUV/ARGB data
 * directly from the native memory address of the buffer.
 * </p>
 * 
 * <h2>Usage</h2>
 * 
 * <pre>
 * NativeVideoEncoder encoder = new NativeVideoEncoder();
 * encoder.init(640, 480, 30);
 * encoder.encodeFrame(directBuffer, timestamp);
 * encoder.release();
 * </pre>
 */
public class NativeVideoEncoder {

    static {
        // Load the platform-specific native library (e.g. libnative_encoder.so)
        // Checks System path first, then extracts from JAR resources.
        NativeLibraryLoader.loadLibrary("native_encoder");
    }

    /**
     * Encode a frame data directly from a ByteBuffer.
     * 
     * @param buffer DirectByteBuffer containing frame data (YUV or ARGB).
     *               MUST be allocated with ByteBuffer.allocateDirect().
     * @param length Length of data to encode.
     * @param width  Frame width.
     * @param height Frame height.
     * @return Encoded size or error code.
     */
    public native int encodeFrame(ByteBuffer buffer, int length, int width, int height);

    /**
     * Convert I420 buffer to ARGB using native SIMD optimized code.
     * Use internal native buffer pool to avoid Java Heap allocations.
     * 
     * @param y       Y plane buffer
     * @param u       U plane buffer
     * @param v       V plane buffer
     * @param strideY Y stride
     * @param strideU U stride
     * @param strideV V stride
     * @param width   Frame width
     * @param height  Frame height
     * @return DirectByteBuffer wrapping the pooled ARGB data
     */
    public native ByteBuffer convertI420ToARGB(
            ByteBuffer y, ByteBuffer u, ByteBuffer v,
            int strideY, int strideU, int strideV,
            int width, int height);

    /**
     * Release/Unlock a buffer back to the native pool.
     * 
     * @param buffer The buffer to release
     */
    public native void releaseBuffer(ByteBuffer buffer);

    /*
     * Example C++ implementation provided in documentation
     * ... (comments preserved from original)
     */
}
