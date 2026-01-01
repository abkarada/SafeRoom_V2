package com.saferoom.webrtc.pipeline;

import dev.onvoid.webrtc.media.video.VideoFrame;
import org.jctools.queues.SpscArrayQueue;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * High-performance video frame processor using lock-free SPSC queue.
 * 
 * <h2>Performance Optimizations</h2>
 * <ul>
 * <li><b>JCTools SpscArrayQueue:</b> Lock-free, cache-optimized, 2-10x faster
 * than ArrayBlockingQueue</li>
 * <li><b>Single producer/consumer:</b> WebRTC video sink → processor
 * thread</li>
 * <li><b>Spin-wait with backoff:</b> Low latency polling without blocking</li>
 * </ul>
 */
public final class FrameProcessor implements AutoCloseable {

    private static final String QUEUE_CAPACITY_PROPERTY = "saferoom.video.queue.capacity";
    private static final int DEFAULT_QUEUE_CAPACITY = Integer.getInteger(QUEUE_CAPACITY_PROPERTY, 30); // 1 second
                                                                                                       // buffer at
                                                                                                       // 30fps
    private static final long POLL_SPIN_NANOS = 1_000_000; // 1ms spin before park
    private static final long STALL_THRESHOLD_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long STALL_LOG_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();

    // Lock-free SPSC queue (JCTools)
    private final SpscArrayQueue<VideoFrame> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Consumer<FrameRenderResult> consumer;
    private final Thread workerThread;
    private final VideoPipelineStats stats = new VideoPipelineStats();

    public FrameProcessor(Consumer<FrameRenderResult> consumer) {
        this(consumer, DEFAULT_QUEUE_CAPACITY);
    }

    public FrameProcessor(Consumer<FrameRenderResult> consumer, int capacity) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        int resolvedCapacity = capacity > 0 ? capacity : DEFAULT_QUEUE_CAPACITY;
        // SpscArrayQueue requires power-of-2 capacity for optimal performance
        int powerOf2Capacity = Integer.highestOneBit(resolvedCapacity - 1) << 1;
        this.queue = new SpscArrayQueue<>(Math.max(2, powerOf2Capacity));

        // Platform thread for native WebRTC interop
        this.workerThread = Thread.ofPlatform()
                .name("frame-processor-" + System.identityHashCode(this))
                .daemon(true)
                .unstarted(this::processLoop);
        System.out.println(
                "[FrameProcessor] Using JCTools SpscArrayQueue (lock-free, capacity=" + powerOf2Capacity + ")");

        this.workerThread.start();
    }

    /**
     * Submit a video frame to the processing queue.
     * Lock-free, non-blocking operation.
     */
    public void submit(VideoFrame frame) {
        if (!running.get() || frame == null) {
            return;
        }
        if (paused.get()) {
            return;
        }
        frame.retain();

        // Try to offer; if full, drop oldest frame and retry
        if (!queue.offer(frame)) {
            stats.recordDrop();
            VideoFrame dropped = queue.poll();
            if (dropped != null) {
                dropped.release();
            }
            // Retry after making room
            if (!queue.offer(frame)) {
                frame.release(); // Still can't add, release this one too
            }
        }
    }

    // Debug counters
    private volatile long processedCount = 0;
    private volatile long lastProcessedLog = 0;

    private void processLoop() {
        System.out.println(
                "[FrameProcessor] Process loop started (JCTools SPSC) on: " + Thread.currentThread().getName());

        long emptySpinCount = 0;

        while (running.get()) {
            try {
                VideoFrame frame = queue.poll();

                if (frame == null) {
                    // Queue empty - spin briefly, then park
                    emptySpinCount++;
                    if (emptySpinCount > 100) {
                        // After 100 empty polls, park for 1ms to save CPU
                        LockSupport.parkNanos(POLL_SPIN_NANOS);
                        logIfStalled();
                    }
                    continue;
                }

                emptySpinCount = 0; // Reset spin counter on successful poll

                if (paused.get()) {
                    frame.release();
                    continue;
                }

                try {
                    long start = System.nanoTime();
                    FrameRenderResult result = convertFrame(frame);
                    long processingTimeMs = (System.nanoTime() - start) / 1_000_000;
                    stats.recordProcessed(System.nanoTime() - start, queue.size());

                    // Log processing stats every 100 frames
                    processedCount++;
                    if (processedCount - lastProcessedLog >= 100) {
                        System.out.printf("[FrameProcessor] Processed %d frames (last took %dms, queue=%d)%n",
                                processedCount, processingTimeMs, queue.size());
                        lastProcessedLog = processedCount;
                    }

                    consumer.accept(result);
                } finally {
                    frame.release();
                }
            } catch (Throwable t) {
                System.err.println("[FrameProcessor] ERROR in processLoop: " + t.getMessage());
                t.printStackTrace();
            }
        }
        System.out.println("[FrameProcessor] Process loop ended, processed total: " + processedCount);
        drainQueue();
    }

    public VideoPipelineStats getStats() {
        return stats;
    }

    private void logIfStalled() {
        long now = System.nanoTime();
        if (stats.shouldLogStall(now, STALL_THRESHOLD_NANOS, STALL_LOG_INTERVAL_NANOS)) {
            System.err.printf("[FrameProcessor] ⚠️ Pipeline stalled: %s%n", stats);
        }
    }

    private FrameRenderResult convertFrame(VideoFrame frame) {
        var buffer = frame.buffer.toI420();
        try {
            return FrameRenderResult.fromI420(buffer, frame.timestampNs);
        } finally {
            buffer.release();
        }
    }

    private void drainQueue() {
        VideoFrame frame;
        while ((frame = queue.poll()) != null) {
            frame.release();
        }
    }

    @Override
    public void close() {
        running.set(false);
        workerThread.interrupt();
        drainQueue();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }
}
