package com.saferoom.webrtc.pipeline;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.I420Buffer;
import org.jctools.queues.SpscArrayQueue;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Virtual-thread based frame processor. Each instance owns a dedicated virtual
 * thread that
 * performs decode → convert steps off the JavaFX thread and pushes paint-ready
 * frames to a consumer.
 */
public final class FrameProcessor implements AutoCloseable {

    private static final String QUEUE_CAPACITY_PROPERTY = "saferoom.video.queue.capacity";
    public static final int DEFAULT_QUEUE_CAPACITY = Integer.getInteger(QUEUE_CAPACITY_PROPERTY, 12);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(50);
    private static final long POLL_SPIN_NANOS = Duration.ofMillis(1).toNanos();
    private static final long STALL_THRESHOLD_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long STALL_LOG_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();

    // SHARED Executor for all FrameProcessors to allow ThreadLocal reuse!
    private static final java.util.concurrent.ExecutorService SHARED_EXECUTOR = java.util.concurrent.Executors
            .newCachedThreadPool(r -> {
                Thread t = Thread.ofPlatform().name("fp-worker").daemon(true).unstarted(r);
                return t;
            });

    private final BlockingQueue<VideoFrame> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Consumer<FrameRenderResult> consumer;
    private final Predicate<VideoFrame> shouldProcess;
    private final java.util.concurrent.Future<?> workerFuture; // Track task
    private final VideoPipelineStats stats = new VideoPipelineStats();

    public FrameProcessor(Consumer<FrameRenderResult> consumer) {
        this(consumer, DEFAULT_QUEUE_CAPACITY, frame -> true);
    }

    public FrameProcessor(Consumer<FrameRenderResult> consumer, int capacity) {
        this(consumer, capacity, frame -> true);
    }

    public FrameProcessor(Consumer<FrameRenderResult> consumer, int capacity, Predicate<VideoFrame> shouldProcess) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.shouldProcess = Objects.requireNonNull(shouldProcess, "shouldProcess");
        int resolvedCapacity = capacity > 0 ? capacity : DEFAULT_QUEUE_CAPACITY;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, resolvedCapacity));

        // Submit to SHARED executor
        // This allows the task to run on an existing thread, reusing the
        // ThreadLocal<ByteBuffer>
        this.workerFuture = SHARED_EXECUTOR.submit(this::processLoop);

        System.out.println("[FrameProcessor] Submitted process loop to shared executor");
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
        System.out.println("[FrameProcessor] Process loop started on thread: " + Thread.currentThread().getName());

        // Initialize JNI Encoder (per-thread instance if needed, or shared)
        NativeVideoEncoder nativeEncoder = new NativeVideoEncoder();
        int emptySpinCount = 0;

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

                // Reset spin count when we get a frame
                emptySpinCount = 0;

                // BACKPRESSURE CHECK: If UI is busy, drop frame immediately
                if (paused.get() || !shouldProcess.test(frame)) {
                    frame.release();
                    stats.recordDrop(); // Count as drop in stats since we skipped it
                    continue;
                }

                try {
                    long start = System.nanoTime();
                    // Use Native Conversion (Pool)
                    FrameRenderResult result = convertFrame(frame, nativeEncoder);

                    // ════════════════════════════════════════════════════════════════════════
                    // NATIVE ENCODING INTEGRATION (Use the JNI Encoder)
                    // ... (rest is same)
                    // ════════════════════════════════════════════════════════════════════════

                    if (result != null) {
                        nativeEncoder.encodeFrame(
                                result.getBuffer(),
                                result.getWidth() * result.getHeight() * 4,
                                result.getWidth(),
                                result.getHeight());
                    }

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
        // ... (keep as is if not in window)
        long now = System.nanoTime();
        if (stats.shouldLogStall(now, STALL_THRESHOLD_NANOS, STALL_LOG_INTERVAL_NANOS)) {
            System.err.printf("[FrameProcessor] ⚠️ Pipeline stalled: %s%n", stats);
        }
    }

    private FrameRenderResult convertFrame(VideoFrame frame, NativeVideoEncoder encoder) {
        I420Buffer buffer = frame.buffer.toI420();
        try {
            return FrameRenderResult.fromI420Native(buffer, frame.timestampNs, encoder);
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
        // workerThread.interrupt(); // REMOVED
        if (workerFuture != null) {
            workerFuture.cancel(true); // INTERRUPT via Future
        }
        drainQueue();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }
}
