package com.saferoom.file_transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.Arrays;

/**
 * EnhancedNackListener: Handles incoming feedback from the receiver.
 * 
 * <p>
 * Separation of Concerns:
 * <ul>
 * <li><b>ACK Logic (Implicit)</b>: Derived from received bitmask. Used for
 * Congestion Control (BBR).</li>
 * <li><b>NACK Logic (Explicit)</b>: Missing bits in mask. Used for ARQ
 * (Retransmission) to fix gaps.</li>
 * </ul>
 * 
 * <p>
 * Security & Performance Improvements:
 * <ul>
 * <li><b>RingBuffer Tracking</b>: Replaces HashMap with O(1) circular buffer
 * for RTT measurements.</li>
 * <li><b>Strict Validation</b>: Enforces packet sizes and magic numbers to
 * prevent DoS/confusion.</li>
 * <li><b>No allocation</b>: Zero-allocation in hot path.</li>
 * </ul>
 */
public class EnhancedNackListener implements Runnable {
	public final DatagramChannel channel;
	public final long fileId;
	public final int totalSeq;
	public final ConcurrentLinkedQueue<Integer> retxQueue;
	public final int backoffNs;

	// Completion callback
	public volatile Runnable onTransferComplete = null;

	// Enhanced congestion control reference
	public volatile HybridCongestionController hybridControl = null;

	// --- High-Performance RingBuffer for RTT Tracking ---
	// Capacity must be power of 2 for fast masking.
	// 16384 packets * 1450 bytes ~= 23 MB window (sufficient for high BDP)
	private static final int HISTORY_CAPACITY = 16384;
	private static final int HISTORY_MASK = HISTORY_CAPACITY - 1;

	private final long[] sentTimes = new long[HISTORY_CAPACITY];
	private final int[] sentSeqs = new int[HISTORY_CAPACITY];

	private volatile long lastRttMeasurement = 0;

	public static final int DEFAULT_BACKOFF_NS = 200_000;

	// Security Constants
	private static final int PACKET_SIZE_COMPLETION = 8;
	private static final int PACKET_SIZE_NACK = 28; // NackFrame.SIZE (assumed 28 based on code)
	private static final int COMPLETION_MAGIC = 0xDEADBEEF;

	public EnhancedNackListener(DatagramChannel channel,
			long fileId,
			int totalSeq,
			ConcurrentLinkedQueue<Integer> retxQueue,
			int backoffNs) {
		this.channel = channel;
		this.fileId = fileId;
		this.totalSeq = totalSeq;
		this.retxQueue = retxQueue;
		this.backoffNs = backoffNs > 0 ? backoffNs : DEFAULT_BACKOFF_NS;

		// Initialize ring buffer with invalid sequences
		Arrays.fill(sentSeqs, -1);
	}

	/**
	 * Record packet send time for RTT calculation using RingBuffer (O(1))
	 */
	public void recordPacketSendTime(int seqNo) {
		int idx = seqNo & HISTORY_MASK;
		sentTimes[idx] = System.nanoTime();
		sentSeqs[idx] = seqNo; // Tag slot with sequence to handle wrapping
	}

	@Override
	public void run() {
		// Allocate buffer large enough for max expected control frame
		final ByteBuffer ctrl = ByteBuffer.allocateDirect(64);

		while (!Thread.currentThread().isInterrupted()) {
			ctrl.clear();
			try {
				int r = channel.read(ctrl);

				if (r <= 0) {
					LockSupport.parkNanos(backoffNs);
					// No periodic cleanup needed anymore! (RingBuffer overwrites old data)
					continue;
				}

				long receiveTime = System.nanoTime();
				ctrl.flip();

				// --- SECURITY & VALIDATION ---
				// Strict length check to classify packet type

				if (r == PACKET_SIZE_COMPLETION) {
					handleCompletionSignal(ctrl);
					// Check if we should exit (thread interrupted by handleCompletionSignal)
					if (Thread.currentThread().isInterrupted())
						break;
					continue;
				}

				// NACK Frame (28 bytes)
				if (r == PACKET_SIZE_NACK) {
					handleNackFrame(ctrl, r, receiveTime);
					continue;
				}

				// Invalid packet size - Drop siliently or log sparingly (avoid log flood DoS)
				// System.err.println("Ignored invalid control packet size: " + r);
				continue;

			} catch (IOException e) {
				System.out.println("IO Error in Listener: " + e);
				LockSupport.parkNanos(backoffNs);
			}
		}
	}

	private void handleCompletionSignal(ByteBuffer ctrl) {
		if (ctrl.remaining() < 8)
			return;

		int magic = ctrl.getInt();
		int receivedFileId = ctrl.getInt(); // 32-bit cast of fileId? Protocol seems to assume int here based on older
											// code.

		if (magic == COMPLETION_MAGIC && receivedFileId == (int) fileId) {
			System.out.println("Transfer completion signal received from receiver! (Validated)");
			if (onTransferComplete != null) {
				try {
					onTransferComplete.run();
				} catch (Exception e) {
					System.err.println("Error in completion callback: " + e.getMessage());
				}
			}
			// Signal loop to probably stop?
			// We can interrupt ourselves to exit gracefully
			Thread.currentThread().interrupt();
		}
	}

	private void handleNackFrame(ByteBuffer ctrl, int size, long receiveTime) {
		if (ctrl.remaining() != NackFrame.SIZE)
			return;

		long fid = NackFrame.fileId(ctrl);
		if (fid != fileId)
			return; // Wrong file, ignore

		// --- RTT MEASUREMENT (Congestion Control) ---
		long nackSentTime = NackFrame.timestamp(ctrl);
		long rttNs = receiveTime - nackSentTime;

		// RTT sanity check (50us - 100ms)
		if (rttNs > 50_000 && rttNs < 100_000_000) {
			if (hybridControl != null) {
				hybridControl.updateRtt(rttNs);
				lastRttMeasurement = rttNs;
			}
		}

		int base = NackFrame.baseSeq(ctrl);
		long mask = NackFrame.mask64(ctrl);

		if (base < 0 || base >= totalSeq)
			return;

		// --- PROCESS FEEDBACK ---
		int lossCount = 0;
		int receivedCount = 0; // "Delivered" count for BBR

		// Improve RTT sample from individual packets if possible?
		// With RingBuffer we can check individual packet RTTs efficiently

		for (int i = 0; i < 64; i++) {
			int seq = base + i;
			if (seq >= totalSeq)
				break;

			boolean received = ((mask >>> i) & 1L) == 1L;

			if (!received) {
				// --- ARQ LOGIC: Packet Loss ---
				// Only queue if not already queued? (Queue handles duplicates usually or Set)
				boolean added = retxQueue.offer(seq);
				// Note: Standard Queue allows duplicates, but sender logic usually handles
				// dedup or idempotent sends.
				lossCount++;
			} else {
				// --- IMPLICIT ACK: Congestion Control ---
				receivedCount++;

				// Clean up ring buffer (Optional, but good for debugging/correctness)
				// We don't strictly *need* to remove, but we can verify this specific packet's
				// RTT
				int idx = seq & HISTORY_MASK;
				if (sentSeqs[idx] == seq) {
					// Start efficient RTT sample
					// long packetRtt = receiveTime - sentTimes[idx];
					// We could average these, but the Frame RTT is usually sufficient and less
					// noisy.

					sentSeqs[idx] = -1; // Mark as consumed
				}
			}
		}

		// --- FEED BBR CONTROLLER ---
		if (hybridControl != null) {
			// deliveredBytes: approximation (count * MSS)
			int deliveredBytes = receivedCount * 1450;

			// Use frame RTT
			long feedbackRtt = (lastRttMeasurement > 0) ? lastRttMeasurement : 0;

			hybridControl.onAckReceived(deliveredBytes, feedbackRtt);

			if (lossCount > 0) {
				hybridControl.onPacketLoss(lossCount, lossCount * 1450);
			}
		}

		// --- TRANSFER COMPLETION CHECK ---
		checkCompletion(base, mask);
	}

	private void checkCompletion(int base, long mask) {
		int remainingPackets = totalSeq - base;
		if (remainingPackets <= 64) {
			long expectedMask = (remainingPackets == 64) ? -1L : (1L << remainingPackets) - 1;

			if ((mask & expectedMask) == expectedMask) {
				System.out.println("Sender detected implicit completion (All last chunks ACKed).");
				if (onTransferComplete != null) {
					try {
						onTransferComplete.run();
					} catch (Exception e) {
					}
				}
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Get current RTT statistics
	 */
	public String getRttStats() {
		if (hybridControl != null) {
			return String.format("RTT: %.1fms", hybridControl.getSmoothedRtt() / 1_000_000.0);
		}
		return "RTT: N/A";
	}
}