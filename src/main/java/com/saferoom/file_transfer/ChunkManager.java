package com.saferoom.file_transfer;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages file chunks with LRU caching for large file transfers
 * 
 * Responsibilities:
 * - Calculate chunk metadata for entire file
 * - Map/unmap chunks on demand with LRU eviction
 * - Binary search for sequence → chunk mapping
 * - Thread-safe chunk access
 * - Explicit buffer cleaning (unmapping) to avoid OOM
 * 
 * Thread-safety: synchronized on cache access
 */
public class ChunkManager {

    // ========== STATIC CONFIGURATION ==========

    /** Default chunk size (JVM-dependent) */
    private static final long DEFAULT_CHUNK_SIZE;

    /** Maximum chunks to keep in memory (LRU cache size) */
    private static final int CACHE_SIZE = 8; // 8 chunks x 1 GB = 8 GB max

    /** Detect JVM architecture and set chunk size */
    static {
        // Check if 64-bit JVM
        String arch = System.getProperty("sun.arch.data.model");
        boolean is64bit = "64".equals(arch);

        // 64-bit: 1 GB chunks (safe within 2 GB ByteBuffer limit)
        // 32-bit: 128 MB chunks (conservative)
        DEFAULT_CHUNK_SIZE = is64bit ? (1L << 30) : (128L << 20);

        // Log configuration
        System.out.println("🖥️  JVM Architecture: " + (is64bit ? "64-bit" : "32-bit"));
        System.out.println("📦 Chunk Size: " + (DEFAULT_CHUNK_SIZE >> 20) + " MB");
        System.out.println("💾 LRU Cache Size: " + CACHE_SIZE + " chunks (" +
                (CACHE_SIZE * (DEFAULT_CHUNK_SIZE >> 20)) + " MB max)");
    }

    /**
     * Explicitly unmap a MappedByteBuffer.
     * Required because GC is lazy and may not release file handles/memory fast
     * enough for large transfers.
     */
    private static void clean(MappedByteBuffer buffer) {
        if (buffer == null)
            return;
        try {
            // Java 9+ compatible reflection based cleaner
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object theUnsafe = theUnsafeField.get(null);

            java.lang.reflect.Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            invokeCleaner.invoke(theUnsafe, buffer);
        } catch (Exception e) {
            // Fallback or just let GC handle it (safer but slower)
            // System.err.println("Warning: Could not explicitly unmap buffer: " +
            // e.getMessage());
        }
    }

    // ========== INSTANCE FIELDS ==========

    /** Path to file being transferred */
    private final Path filePath;

    /** Total file size in bytes */
    private final long fileSize;

    /** Packet payload size (typically 1450) */
    private final int sliceSize;

    /** Array of chunk metadata (calculated once) */
    private final ChunkMetadata[] chunks;

    /** FileChannel for mapping (kept open during transfer) */
    private final FileChannel fileChannel;

    /** Map mode: READ_ONLY for sender, READ_WRITE for receiver */
    private final FileChannel.MapMode mapMode;

    /** LRU cache of mapped chunks (thread-safe via synchronization) */
    private final Map<Integer, MappedByteBuffer> chunkCache;

    // ========== CONSTRUCTOR ==========

    /**
     * Create chunk manager for given file
     * 
     * @param filePath  Path to file to transfer
     * @param sliceSize Packet payload size (typically 1450)
     * @throws IOException if file cannot be opened or read
     */
    public ChunkManager(Path filePath, int sliceSize) throws IOException {
        this.filePath = filePath;
        this.sliceSize = sliceSize;
        this.mapMode = FileChannel.MapMode.READ_ONLY; // Sender mode

        // Open file channel (will stay open for entire transfer)
        this.fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
        this.fileSize = fileChannel.size();

        // Calculate all chunk metadata upfront
        this.chunks = calculateChunks();

        // Create LRU cache with access-order
        this.chunkCache = new LinkedHashMap<Integer, MappedByteBuffer>(
                CACHE_SIZE + 1, // Initial capacity
                0.75f, // Load factor
                true // Access-order (LRU)
        ) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, MappedByteBuffer> eldest) {
                boolean shouldRemove = size() > CACHE_SIZE;
                if (shouldRemove) {
                    System.out.println("🗑️  LRU evicted chunk " + eldest.getKey());
                    clean(eldest.getValue());
                }
                return shouldRemove;
            }
        };

        // Log initialization
        System.out.println("📦 ChunkManager initialized");
        System.out.println("   File: " + filePath.getFileName());
        System.out.println("   Size: " + String.format("%,d", fileSize) + " bytes (" +
                String.format("%.2f", fileSize / (1024.0 * 1024.0 * 1024.0)) + " GB)");
        System.out.println("   Total chunks: " + chunks.length);
        System.out.println("   Cache capacity: " + CACHE_SIZE + " chunks");
    }

    /**
     * Create chunk manager with existing FileChannel (for receiver - WRITE mode)
     * This constructor is used when FileChannel is already opened in READ_WRITE
     * mode.
     * 
     * @param fileChannel Existing FileChannel (must be READ_WRITE mode)
     * @param fileSize    Total file size in bytes
     * @param sliceSize   Packet payload size (typically 1450)
     * @throws IOException if chunks cannot be calculated
     */
    public ChunkManager(FileChannel fileChannel, long fileSize, int sliceSize) throws IOException {
        this.filePath = null; // Not needed when FileChannel provided
        this.fileChannel = fileChannel;
        this.fileSize = fileSize;
        this.sliceSize = sliceSize;
        this.mapMode = FileChannel.MapMode.READ_WRITE; // Receiver mode

        // Calculate all chunk metadata upfront
        this.chunks = calculateChunks();

        // Create LRU cache with access-order
        this.chunkCache = new LinkedHashMap<Integer, MappedByteBuffer>(
                CACHE_SIZE + 1, // Initial capacity
                0.75f, // Load factor
                true // Access-order (LRU)
        ) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, MappedByteBuffer> eldest) {
                boolean shouldRemove = size() > CACHE_SIZE;
                if (shouldRemove) {
                    System.out.println("🗑️  LRU evicted chunk " + eldest.getKey());
                    clean(eldest.getValue());
                }
                return shouldRemove;
            }
        };

        // Log initialization
        System.out.println("📦 ChunkManager initialized (using provided FileChannel)");
        System.out.println("   Size: " + String.format("%,d", fileSize) + " bytes (" +
                String.format("%.2f", fileSize / (1024.0 * 1024.0 * 1024.0)) + " GB)");
        System.out.println("   Total chunks: " + chunks.length);
        System.out.println("   Cache capacity: " + CACHE_SIZE + " chunks");
    }

    // ========== METHODS ==========

    /**
     * Calculate chunk metadata for entire file
     * 
     * Divides file into fixed-size chunks (last may be smaller)
     * and calculates sequence number ranges for each chunk.
     * 
     * @return Array of chunk metadata, ordered by chunk index
     */
    private ChunkMetadata[] calculateChunks() {
        // How many chunks needed?
        int numChunks = (int) ((fileSize + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE);
        ChunkMetadata[] result = new ChunkMetadata[numChunks];

        int globalSeqNo = 0; // Running sequence counter

        for (int i = 0; i < numChunks; i++) {
            // Calculate chunk boundaries
            long chunkOffset = (long) i * DEFAULT_CHUNK_SIZE;
            long chunkSize = Math.min(DEFAULT_CHUNK_SIZE, fileSize - chunkOffset);

            // How many packets in this chunk?
            int packetCount = (int) ((chunkSize + sliceSize - 1) / sliceSize);

            // Create metadata
            result[i] = new ChunkMetadata(
                    i, // chunkIndex
                    chunkOffset, // fileOffset
                    chunkSize, // chunkSize
                    globalSeqNo, // globalSeqStart
                    globalSeqNo + packetCount - 1, // globalSeqEnd (inclusive)
                    packetCount // packetCount
            );

            // Advance global sequence counter
            globalSeqNo += packetCount;

            // Log each chunk
            System.out.println("   " + result[i]);
        }

        System.out.println("   Total packets: " + String.format("%,d", globalSeqNo));
        return result;
    }

    /**
     * Get chunk buffer (from cache or map new)
     * 
     * Thread-safe: synchronized to protect LRU cache access.
     * 
     * Cache hit: Returns existing MappedByteBuffer (fast)
     * Cache miss: Maps new chunk, adds to cache, evicts oldest if needed
     * 
     * @param chunkIndex Zero-based chunk index
     * @return MappedByteBuffer for this chunk
     * @throws IOException              if mapping fails
     * @throws IllegalArgumentException if chunkIndex invalid
     */
    public synchronized MappedByteBuffer getChunk(int chunkIndex) throws IOException {
        // Validation
        if (chunkIndex < 0 || chunkIndex >= chunks.length) {
            throw new IllegalArgumentException("Invalid chunk index: " + chunkIndex +
                    " (valid range: 0-" + (chunks.length - 1) + ")");
        }

        // Cache hit?
        if (chunkCache.containsKey(chunkIndex)) {
            // LinkedHashMap with access-order will mark this as recently used
            return chunkCache.get(chunkIndex);
        }

        // Cache miss - map new chunk
        ChunkMetadata meta = chunks[chunkIndex];

        long mapStart = System.nanoTime();
        MappedByteBuffer buffer = fileChannel.map(
                mapMode, // READ_ONLY for sender, READ_WRITE for receiver
                meta.fileOffset,
                meta.chunkSize);

        // Pre-fault pages into memory (force OS to load)
        buffer.load();

        long mapTime = (System.nanoTime() - mapStart) / 1_000_000; // Convert to ms

        System.out.println("📦 Mapped chunk " + chunkIndex + " (" +
                String.format("%.1f", meta.chunkSize / (1024.0 * 1024.0)) + " MB) in " +
                mapTime + " ms");

        // Add to cache (LRU will evict oldest if size > CACHE_SIZE)
        chunkCache.put(chunkIndex, buffer);

        return buffer;
    }

    /**
     * Find which chunk contains given global sequence number
     * 
     * Uses binary search: O(log n) complexity
     * 
     * @param globalSeq Global sequence number to search
     * @return Chunk index containing this sequence
     * @throws IllegalArgumentException if sequence not found in any chunk
     */
    public int findChunkForSequence(int globalSeq) {
        int left = 0;
        int right = chunks.length - 1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            ChunkMetadata chunk = chunks[mid];

            // Found it?
            if (globalSeq >= chunk.globalSeqStart && globalSeq <= chunk.globalSeqEnd) {
                return mid;
            }

            // Search left half?
            if (globalSeq < chunk.globalSeqStart) {
                right = mid - 1;
            }
            // Search right half
            else {
                left = mid + 1;
            }
        }

        // Not found - throw exception
        throw new IllegalArgumentException("Sequence " + globalSeq +
                " not found in any chunk (valid range: 0-" +
                chunks[chunks.length - 1].globalSeqEnd + ")");
    }

    /**
     * Close file channel and release resources
     * 
     * @throws IOException if close fails
     */
    public void close() throws IOException {
        synchronized (this) {
            for (MappedByteBuffer buffer : chunkCache.values()) {
                clean(buffer);
            }
            chunkCache.clear();
        }
        fileChannel.close();
        System.out.println("📦 ChunkManager closed");
    }

    /**
     * Get chunk metadata by index
     * 
     * @param chunkIndex Chunk index
     * @return Chunk metadata
     * @throws IllegalArgumentException if index invalid
     */
    public ChunkMetadata getChunkMetadata(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunks.length) {
            throw new IllegalArgumentException("Invalid chunk index: " + chunkIndex);
        }
        return chunks[chunkIndex];
    }

    /**
     * Get total number of chunks
     * 
     * @return Chunk count
     */
    public int getChunkCount() {
        return chunks.length;
    }

    /**
     * Get total sequence count (for handshake)
     * 
     * @return Total packets in file
     */
    public int getTotalSequenceCount() {
        if (chunks.length == 0)
            return 0;
        ChunkMetadata last = chunks[chunks.length - 1];
        return last.globalSeqEnd + 1;
    }

    /**
     * Get cache statistics for monitoring
     * 
     * @return Human-readable cache stats
     */
    public String getCacheStats() {
        return String.format("Cache: %d/%d chunks loaded", chunkCache.size(), CACHE_SIZE);
    }

    /**
     * Get file size
     * 
     * @return File size in bytes
     */
    public long getFileSize() {
        return fileSize;
    }
}
