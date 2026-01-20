package com.saferoom.file_transfer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * BBR (Bottleneck Bandwidth and RTT) Congestion Control
 * 
 * Optimized for File Transfer in SafeRoom architecture.
 * Features:
 * - Model-based: Estimates MaxBW and MinRTT.
 * - Pacing-driven: Primary control is pacing rate, secondary is CWND.
 * - State Machine: Startup -> Drain -> ProbeBW -> ProbeRTT.
 */
public class HybridCongestionController {

    // BBR Parameters
    private static final int MSS = 1450;
    private static final long MIN_WINDOW_PACKETS = 4;
    private static final long PROBE_RTT_DURATION_MS = 200;
    private static final long RT_PROP_FILTER_LEN_SEC = 10;
    private static final long BTL_BW_FILTER_LEN_SEC = 2; // Window for MaxBW

    // BBR States
    public enum State {
        STARTUP,
        DRAIN,
        PROBE_BW,
        PROBE_RTT
    }

    private volatile State state = State.STARTUP;
    private long startupStartTime;

    // Model Estimates
    private volatile long btlBw = 0; // Bottleneck Bandwidth (bytes/sec)
    private volatile long rtProp = Long.MAX_VALUE; // Round-Trip Propagation Time (ns)
    private volatile long pacingRate = 0; // Bytes per second
    private volatile long cwnd = MSS * 32; // Congestion Window (bytes) - Initial 32 packets

    // Windowed Estimates (Max Filter for BW, Min Filter for RTT)
    private final WindowedMaxFilter bandwidthWindow = new WindowedMaxFilter(BTL_BW_FILTER_LEN_SEC);
    private volatile long minRttTimestamp = 0;

    // Inputs
    private final AtomicLong bytesInFlight = new AtomicLong(0);
    private volatile long inFlightCap = Long.MAX_VALUE; // Cap for ProbeRTT

    // ProbeBW Cycle
    // Gains: 1.25, 0.75, 1, 1, 1, 1, 1, 1
    private static final double[] PACING_GAIN_CYCLE = { 1.25, 0.75, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };
    private int cycleIndex = 0;
    private long cycleStartTime = 0;

    // Stats
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalLossCount = new AtomicLong(0);
    private final long startTime = System.nanoTime();

    // Pacing Internals
    private volatile long nextSendTime = 0;
    private volatile long lastSendTime = 0;

    public HybridCongestionController() {
        this.startupStartTime = System.nanoTime();
        // Initial conservative BW estimate (10 Mbps)
        this.btlBw = 10_000_000;
        updateControlParameters();
    }

    /**
     * Check if we can send based on CWND (In-flight Limit)
     * BBR is pacing-limited, but CWND acts as a safety limit (inflight_cap).
     */
    public boolean canSendPacket() {
        return bytesInFlight.get() < cwnd;
    }

    /**
     * Pacing Enforcement
     * Sleeps if we are sending faster than the pacing rate.
     */
    public void rateLimitSend() {
        // Micro-batching support: Don't sleep for tiny intervals (< 20us)
        // Accumulate debit/credit? For now, standard leaky bucket pacing.

        long now = System.nanoTime();
        if (pacingRate == 0)
            return;

        long interval = (MSS * 1_000_000_000L) / pacingRate;
        long timeSinceLast = now - lastSendTime;

        if (timeSinceLast < interval) {
            long wait = interval - timeSinceLast;
            // Only park if wait is substantial (> 50us) to avoid overhead
            if (wait > 50_000) {
                LockSupport.parkNanos(wait);
            }
        }
    }

    /**
     * Called when a packet is actually transmitted to the network.
     */
    public void onPacketSent(int bytes) {
        bytesInFlight.addAndGet(bytes);
        totalBytesSent.addAndGet(bytes);
        totalPacketsSent.incrementAndGet();
        lastSendTime = System.nanoTime();
    }

    /**
     * Called when an ACK is received (or NACK inferred delivery).
     * 
     * @param deliveredBytes Bytes confirmed delivered
     * @param rttNs          Measured RTT for this delivery
     */
    public void onAckReceived(int deliveredBytes, long rttNs) {
        if (deliveredBytes <= 0)
            return;

        long now = System.nanoTime();
        bytesInFlight.addAndGet(-deliveredBytes);
        if (bytesInFlight.get() < 0)
            bytesInFlight.set(0); // Safety

        // 1. Update RTT (Min Filter)
        updateRtProp(rttNs, now);

        // 2. Update Bandwidth (Max Filter)
        // Rate = delivered / RTT (This is a simplified instantaneous rate)
        // Real BBR tracks delivery rate over a window, but for simplicity:
        if (rttNs > 0) {
            long rate = (deliveredBytes * 1_000_000_000L) / rttNs;
            bandwidthWindow.update(rate, now);
            btlBw = bandwidthWindow.getMax();
        }

        // 3. Check State Transitions
        checkStateTransitions(now);

        // 4. Update Control Parameters (Pacing Rate, CWND)
        updateControlParameters();
    }

    /**
     * Called when LOSS is detected via NACK or timeout.
     */
    public void onPacketLoss(int lostPackets, int lostBytes) {
        if (lostBytes <= 0)
            return;

        totalLossCount.addAndGet(lostPackets);
        bytesInFlight.addAndGet(-lostBytes);
        if (bytesInFlight.get() < 0)
            bytesInFlight.set(0);

        // BBR: Loss doesn't directly cut window like Cubic.
        // But if loss is high in Startup, we exit Startup.
        if (state == State.STARTUP) {
            // Heuristic: If we lose > 20% of window in a burst, exit startup
            if (lostBytes > cwnd * 0.2) {
                System.out.println("[BBR] High loss in Startup -> DRAIN");
                state = State.DRAIN;
            }
        }
    }

    // --- Internal Logic ---

    private void updateRtProp(long rttNs, long now) {
        if (rttNs <= 0)
            return;

        if (rtProp == Long.MAX_VALUE || rttNs < rtProp ||
                (now - minRttTimestamp > RT_PROP_FILTER_LEN_SEC * 1_000_000_000L)) {
            rtProp = rttNs;
            minRttTimestamp = now;
        }
    }

    private void checkStateTransitions(long now) {
        switch (state) {
            case STARTUP:
                // Exit startup if BW plateaued (handled by bandwidth filter logic usually)
                // Simplified: If MinRTT increases significantly or loss occurs (handled in
                // onPacketLoss)
                // Or simplified BBR: Startup for X seconds
                if (now - startupStartTime > 1_000_000_000L) { // 1 sec startup basic limit
                    // Check if BW is growing? For now, assume simplified transition
                    // state = State.DRAIN; // Real BBR checks plateau
                }
                // Transition to Drain when BW plateaus (simulated by filter stability)
                if (bandwidthWindow.isStable() && now - startupStartTime > 500_000_000L) {
                    state = State.DRAIN;
                    System.out.println("[BBR] Bandwidth plateau -> DRAIN");
                }
                break;

            case DRAIN:
                // Exit Drain when inflight drops to BDP
                long bdp = getBDP();
                if (bytesInFlight.get() <= bdp) {
                    state = State.PROBE_BW;
                    cycleStartTime = now;
                    cycleIndex = 0;
                    System.out.println("[BBR] Inflight drained -> PROBE_BW");
                }
                break;

            case PROBE_BW:
                // Cycle through gains every RTT (or fixed interval 100ms)
                long cycleDuration = Math.max(rtProp, 100_000_000L); // Min 100ms
                if (now - cycleStartTime > cycleDuration) {
                    cycleStartTime = now;
                    cycleIndex = (cycleIndex + 1) % PACING_GAIN_CYCLE.length;

                    // Check for ProbeRTT
                    if (now - minRttTimestamp > RT_PROP_FILTER_LEN_SEC * 1_000_000_000L) {
                        state = State.PROBE_RTT;
                        inFlightCap = 4 * MSS; // Drop inflight to measure true minRTT
                        System.out.println("[BBR] RtProp expired -> PROBE_RTT");
                    }
                }
                break;

            case PROBE_RTT:
                if (now - minRttTimestamp < RT_PROP_FILTER_LEN_SEC * 1_000_000_000L) {
                    // MinRTT updated!
                    if (bytesInFlight.get() <= 4 * MSS) {
                        // We held low inflight for enough time (200ms)
                        long probeStart = now; // needs tracking
                        // Simplified exit: If new measurement made or timeout
                        state = State.PROBE_BW; // simplified exit
                        inFlightCap = Long.MAX_VALUE;
                        System.out.println("[BBR] PROBE_RTT Done -> PROBE_BW");
                    }
                }
                // Safety exit after 200ms
                // (Tracking probe Duration needed)
                break;
        }
    }

    private void updateControlParameters() {
        double pacingGain = 1.0;
        double cwndGain = 2.0;

        switch (state) {
            case STARTUP:
                pacingGain = 2.89; // 2/ln(2)
                cwndGain = 2.89;
                break;
            case DRAIN:
                pacingGain = 1.0 / 2.89; // Drain the queue
                cwndGain = 2.89;
                break;
            case PROBE_BW:
                pacingGain = PACING_GAIN_CYCLE[cycleIndex];
                cwndGain = 2.0;
                break;
            case PROBE_RTT:
                pacingGain = 1.0;
                cwndGain = 0.5; // Shrink window
                break;
        }

        // Pacing Rate = BtlBW * Gain
        pacingRate = (long) (btlBw * pacingGain);

        // CWND = BDP * Gain + (Safety Margin)
        // BDP = BtlBW * RtProp
        if (rtProp != Long.MAX_VALUE) {
            long bdp = (btlBw * rtProp) / 1_000_000_000L;
            cwnd = (long) (bdp * cwndGain);

            // Apply bounds
            cwnd = Math.max(cwnd, 4 * MSS); // Min 4 packets
            if (state == State.PROBE_RTT) {
                cwnd = Math.min(cwnd, 4 * MSS);
            }
        }
    }

    private long getBDP() {
        if (rtProp == Long.MAX_VALUE)
            return 0;
        return (btlBw * rtProp) / 1_000_000_000L;
    }

    // --- Helpers ---

    /** Max-Filter for Bandwidth */
    private static class WindowedMaxFilter {
        private final long windowNs;
        private long bucket1 = 0, bucket2 = 0, bucket3 = 0; // Simplified buckets
        private long lastUpdate = 0;

        WindowedMaxFilter(long windowSec) {
            this.windowNs = windowSec * 1_000_000_000L;
        }

        void update(long value, long now) {
            // Full implementation would be MinMax heap or cyclic buffer
            // Simplified: Peak Hold with decay
            if (value > bucket1) {
                bucket1 = value;
                lastUpdate = now;
            } else if (now - lastUpdate > windowNs) {
                // Reset if peak is old
                bucket1 = value; // Hard reset
                lastUpdate = now;
            }
        }

        long getMax() {
            return Math.max(bucket1, 100_000);
        } // Min 100KBps safety

        boolean isStable() {
            return false;
        } // TODO impl
    }

    // --- Getters for Stats ---

    public String getStats() {
        return String.format(
                "BBR State: %s, BW: %.2f Mbps, RTT: %.2f ms, CWND: %d pkts, Pacing: %.2f Mbps",
                state,
                btlBw / 1_000_000.0,
                rtProp / 1_000_000.0,
                cwnd / MSS,
                pacingRate / 1_000_000.0);
    }

    public long getSmoothedRtt() {
        return rtProp == Long.MAX_VALUE ? 0 : rtProp;
    }

    public long getCongestionWindow() {
        return cwnd / MSS;
    } // In packets for legacy display

    public long getPacingInterval() {
        if (pacingRate == 0)
            return 0;
        return (MSS * 1_000_000_000L) / pacingRate;
    }

    // Compatibility methods for old code
    public void onNackFrameReceived(int received, int lost) {
        // Legacy entry point, ignored or adapted if needed.
        // real updates come via onAckReceived
    }

    public void updateRtt(long rtt) {
        updateRtProp(rtt, System.nanoTime());
    }
}
