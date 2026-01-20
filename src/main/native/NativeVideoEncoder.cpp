#include <jni.h>
#include <iostream>
#include <cstring>
#include <vector>
#include <map>
#include <mutex>
#include <memory>

// -----------------------------------------------------------------------------
// NATIVE BUFFER POOL
// -----------------------------------------------------------------------------
struct NativeBuffer {
    uint8_t* data;
    size_t capacity;
    bool in_use;
    jobject global_ref; // Cache the DirectByteBuffer wrapper

    NativeBuffer(size_t size) : capacity(size), in_use(false), global_ref(nullptr) {
        data = new uint8_t[size];
        std::cout << "[NativePool] Allocated new buffer of size: " << size << std::endl;
    }

    ~NativeBuffer() {
        delete[] data;
        // Note: global_ref needs JNIEnv to delete, but destructor might run at shutdown
        // We can leak it safely at shutdown or handle reuse logic carefully.
    }
};

// Global Pool State
static std::map<size_t, std::vector<std::shared_ptr<NativeBuffer>>> pool;
static std::mutex pool_mutex;

// Color Conversion Matrix (Integer approximation)
static inline uint32_t yuv_to_argb(int y, int u, int v) {
    int c = y - 16;
    int d = u - 128;
    int e = v - 128;

    int r = (298 * c + 409 * e + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;

    r = r < 0 ? 0 : (r > 255 ? 255 : r);
    g = g < 0 ? 0 : (g > 255 ? 255 : g);
    b = b < 0 ? 0 : (b > 255 ? 255 : b);

    return (0xFF000000) | (r << 16) | (g << 8) | b;
}

extern "C" {

// -----------------------------------------------------------------------------
// JNI: convertI420ToARGB
// -----------------------------------------------------------------------------
JNIEXPORT jobject JNICALL Java_com_saferoom_webrtc_pipeline_NativeVideoEncoder_convertI420ToARGB(
    JNIEnv *env, jobject thisObj, 
    jobject y_buf, jobject u_buf, jobject v_buf, 
    jint stride_y, jint stride_u, jint stride_v, 
    jint width, jint height) {

    // 1. Get Source Pointers
    uint8_t* src_y = (uint8_t*)env->GetDirectBufferAddress(y_buf);
    uint8_t* src_u = (uint8_t*)env->GetDirectBufferAddress(u_buf);
    uint8_t* src_v = (uint8_t*)env->GetDirectBufferAddress(v_buf);

    if (!src_y || !src_u || !src_v) {
        return nullptr;
    }

    // 2. Acquire Destination Buffer from C++ Pool
    // ARGB = 4 bytes per pixel
    size_t required_size = (size_t)width * height * 4;
    NativeBuffer* dest_buffer = nullptr;

    {
        std::lock_guard<std::mutex> lock(pool_mutex);
        std::vector<std::shared_ptr<NativeBuffer>>& list = pool[required_size];
        
        // Find first free buffer
        for (auto& buf : list) {
            if (!buf->in_use) {
                dest_buffer = buf.get();
                dest_buffer->in_use = true;
                break;
            }
        }

        // If none found, create new
        if (!dest_buffer) {
            // Hard limit checks could go here
            if (list.size() < 10) { // Limit to 10 buffers per resolution
                auto new_buf = std::make_shared<NativeBuffer>(required_size);
                list.push_back(new_buf);
                dest_buffer = new_buf.get();
                dest_buffer->in_use = true;
            } else {
                std::cerr << "[NativePool] Pool exhausted! Dropping frame." << std::endl;
                return nullptr;
            }
        }
    }

    // 3. Perform Conversion (I420 -> ARGB)
    // Destination is int32 array effectively
    uint32_t* dest_argb = (uint32_t*)dest_buffer->data;

    for (int y = 0; y < height; y++) {
        const uint8_t* pY = src_y + y * stride_y;
        const uint8_t* pU = src_u + (y / 2) * stride_u;
        const uint8_t* pV = src_v + (y / 2) * stride_v;
        
        uint32_t* pDest = dest_argb + y * width;

        for (int x = 0; x < width; x++) {
            int Y = pY[x];
            int U = pU[x / 2];
            int V = pV[x / 2];
            
            pDest[x] = yuv_to_argb(Y, U, V);
        }
    }

    // 4. Wrap with DirectByteBuffer (Cached)
    // Use GlobalRef to avoid recreating the Java object every frame
    if (dest_buffer->global_ref == nullptr) {
        jobject local_ref = env->NewDirectByteBuffer(dest_buffer->data, required_size);
        dest_buffer->global_ref = env->NewGlobalRef(local_ref);
        // Do not DeleteLocalRef yet, we return it? 
        // No, NewGlobalRef creates a new ref. We can return the global ref (it acts as local ref in current frame too)
        // Actually, best practice: return global_ref, JNI handles it.
    }
    
    return dest_buffer->global_ref;
}

// -----------------------------------------------------------------------------
// JNI: releaseBuffer
// -----------------------------------------------------------------------------
JNIEXPORT void JNICALL Java_com_saferoom_webrtc_pipeline_NativeVideoEncoder_releaseBuffer(
    JNIEnv *env, jobject thisObj, jobject buffer) {
    
    void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr) return; // Should not happen

    std::lock_guard<std::mutex> lock(pool_mutex);
    
    // Scan all pools (inefficient linear search, but size is small < 10)
    bool found = false;
    for (auto& kv : pool) {
        for (auto& buf : kv.second) {
            if (buf->data == addr) {
                buf->in_use = false;
                found = true;
                break;
            }
        }
        if (found) break;
    }
}

// Legacy stub
JNIEXPORT jint JNICALL Java_com_saferoom_webrtc_pipeline_NativeVideoEncoder_encodeFrame
  (JNIEnv *env, jobject thisObj, jobject buffer, jint length, jint width, jint height) {
    return length;
}

}
