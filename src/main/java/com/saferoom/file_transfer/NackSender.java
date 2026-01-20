package com.saferoom.file_transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

public class NackSender implements Runnable {
	public final long fileId;
	public final long file_size; // Changed to long for large file support
	public final int total_seq;
	public final DatagramChannel channel;
	public final BitSet recv;
	public final NackFrame frame;
	public final MappedByteBuffer mem_buf;
	public final ChunkManager chunkManager; // NEW: For large files

	// Completion callback
	public volatile Runnable onTransferComplete = null;

	// Enhanced congestion control reference
	public volatile HybridCongestionController hybridControl = null;

	// Legacy constructor (backward compatibility)
	public NackSender(DatagramChannel channel, long fileId, long file_size,
			int total_seq, MappedByteBuffer mem_buf) {
		this.channel = channel;
		this.fileId = fileId;
		this.file_size = file_size;
		this.total_seq = total_seq;
		this.mem_buf = mem_buf;
		this.chunkManager = null;
		this.recv = new BitSet(total_seq);
		this.frame = new NackFrame();
	}

	// Enhanced constructor with congestion control
	public NackSender(DatagramChannel channel, long fileId, long file_size,
			int total_seq, MappedByteBuffer mem_buf, HybridCongestionController hybridControl) {
		this.channel = channel;
		this.fileId = fileId;
		this.file_size = file_size;
		this.total_seq = total_seq;
		this.mem_buf = mem_buf;
		this.chunkManager = null;
		this.recv = new BitSet(total_seq);
		this.frame = new NackFrame();
		this.hybridControl = hybridControl;
	}

	// FULL constructor with ChunkManager (for large files > 256MB)
	public NackSender(DatagramChannel channel, long fileId, long file_size,
			int total_seq, ChunkManager chunkManager, HybridCongestionController hybridControl) {
		this.channel = channel;
		this.fileId = fileId;
		this.file_size = file_size;
		this.total_seq = total_seq;
		this.mem_buf = null; // Using ChunkManager instead
		this.chunkManager = chunkManager;
		this.recv = new BitSet(total_seq);
		this.frame = new NackFrame();
		this.hybridControl = hybridControl;
	}

	public volatile int cum_Ack = 0;
	private volatile boolean transferCompleted = false;
	public final int CRC32C_HEADER_SIZE = 22;
	public final int PAYLOAD_SIZE = 1450; // Updated to match FileTransferSender
	public final int TOTAL_PACKET_SIZE = CRC32C_HEADER_SIZE + PAYLOAD_SIZE;

	public static final int OFF_FILE_ID = 0;
	public static final int OFF_SEQ = 8;
	public static final int OFF_TOTAL = 12;
	public static final int OFF_PLEN = 16;

	public ByteBuffer buf = ByteBuffer.allocateDirect(CRC32C_HEADER_SIZE + PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN);
	public CRC32C crc = new CRC32C();

	public synchronized boolean isTransferComplete() {
		return recv.cardinality() == total_seq;
	}

	public boolean isTransferCompleted() {
		return transferCompleted;
	}

	private void updateCumulativeAck() {
		synchronized (this) {
			while (cum_Ack < total_seq && recv.get(cum_Ack)) {
				cum_Ack++;
			}
			if (isTransferComplete() && !transferCompleted) {
				transferCompleted = true;
				stopNackLoop();
				System.out.println("File transfer completed successfully! Shutting down receiver...");

				// Completion callback'ı çağır
				if (onTransferComplete != null) {
					try {
						onTransferComplete.run();
					} catch (Exception e) {
						System.err.println("Transfer completion callback error: " + e);
					}
				}
				return;
			}
		}
	}

	public void onData(ByteBuffer fullPacket) {
		// Packet validation
		if (fullPacket == null || fullPacket.remaining() < CRC32C_HEADER_SIZE) {
			System.err.println("Invalid packet: null or too small");
			return;
		}

		int seqNo = CRC32C_Packet.seqNo(fullPacket);
		int receivedCrc = CRC32C_Packet.crc32(fullPacket);
		int payloadLen = CRC32C_Packet.plen(fullPacket);

		// Sequence number validation
		if (seqNo < 0 || seqNo >= total_seq) {
			System.err.println("Invalid sequence number: " + seqNo + " (total: " + total_seq + ")");
			return;
		}

		// Payload length validation
		if (payloadLen <= 0 || payloadLen > PAYLOAD_SIZE) {
			System.err.println("Invalid payload length: " + payloadLen);
			return;
		}

		// Packet size validation
		if (fullPacket.remaining() < CRC32C_HEADER_SIZE + payloadLen) {
			System.err.println("Packet too small for declared payload length");
			return;
		}

		// Extract payload - safely slice (Java 8 uyumlu)
		fullPacket.position(CRC32C_HEADER_SIZE);
		fullPacket.limit(CRC32C_HEADER_SIZE + payloadLen);
		ByteBuffer payload = fullPacket.slice();
		fullPacket.clear(); // Reset position/limit

		// CRC validation
		crc.reset();
		crc.update(payload.duplicate());
		int calculatedCrc = (int) crc.getValue();

		if (calculatedCrc == receivedCrc) {
			int off = seqNo * PAYLOAD_SIZE;

			// Chunk-aware write logic
			if (chunkManager != null) {
				// Large file mode: use ChunkManager
				try {
					// CRITICAL FIX: Do I/O operations OUTSIDE synchronized block to prevent
					// deadlock!
					int chunkIdx = chunkManager.findChunkForSequence(seqNo);
					if (chunkIdx < 0) {
						System.err.println("No chunk found for sequence: " + seqNo);
						return;
					}

					ChunkMetadata chunkMeta = chunkManager.getChunkMetadata(chunkIdx);
					MappedByteBuffer chunkBuffer = chunkManager.getChunk(chunkIdx);
					int localSeq = chunkMeta.toLocalSequence(seqNo);
					int localOff = chunkMeta.getLocalOffset(localSeq, PAYLOAD_SIZE);

					// Bounds check BEFORE synchronized block
					if (localOff + payloadLen > chunkBuffer.capacity()) {
						System.err.println("️Chunk bounds exceeded: localOff=" + localOff +
								", payloadLen=" + payloadLen + ", capacity=" + chunkBuffer.capacity() +
								", seqNo=" + seqNo);
						// Adjust payload length to fit
						payloadLen = chunkBuffer.capacity() - localOff;
						System.out.println("Adjusted payloadLen to: " + payloadLen);
					}

					// Prepare buffer views OUTSIDE synchronized block
					MappedByteBuffer view = chunkBuffer.duplicate();
					view.position(localOff);
					view.limit(localOff + payloadLen);

					ByteBuffer payloadToPut = payload.duplicate();
					payloadToPut.limit(payloadLen);
					payloadToPut.rewind();

					// NOW enter synchronized block - ONLY for BitSet update and write
					synchronized (this) {
						if (recv.get(seqNo))
							return; // Already received

						view.put(payloadToPut);
						recv.set(seqNo);
					}
				} catch (IOException e) {
					System.err.println("Chunk write error for seq " + seqNo + ": " + e);
					return;
				}
			} else {
				// Legacy mode: use single MappedByteBuffer
				if (off < 0 || off >= mem_buf.capacity()) {
					System.err.println("Buffer bounds error: seqNo=" + seqNo + ", off=" + off + ", capacity="
							+ mem_buf.capacity());
					return;
				}

				// Adjust payload if it exceeds buffer
				if (off + payloadLen > mem_buf.capacity()) {
					System.out.println("Adjusting payload: off=" + off + ", payloadLen=" + payloadLen + " → "
							+ (mem_buf.capacity() - off));
					payloadLen = mem_buf.capacity() - off;
				}

				// Prepare buffer views OUTSIDE synchronized block
				MappedByteBuffer view = mem_buf.duplicate();
				view.position(off);
				view.limit(off + payloadLen);

				ByteBuffer payloadToPut = payload.duplicate();
				payloadToPut.limit(payloadLen);
				payloadToPut.rewind();

				// NOW enter synchronized block - ONLY for BitSet update and write
				synchronized (this) {
					if (recv.get(seqNo))
						return; // Already received

					view.put(payloadToPut);
					recv.set(seqNo);
				}
			}

			updateCumulativeAck();
		} else {
			// CRC mismatch - bu paketi alınmamış olarak işaretle
			synchronized (this) {
				recv.clear(seqNo);
			}

			// NACK-based: CRC mismatch sadece log, sender NACK alınca tekrar gönderecek
			// CRC mismatch - sessizce ignore et (network'te bozulmuş paket)
		}
	}

	public long build64() {
		long mask = 0L;
		int base = cum_Ack;
		for (int i = 0; i < 64; i++) {
			if (base + i >= total_seq)
				break;
			if (recv.get(base + i))
				mask |= (1L << i);
		}
		return mask;
	}

	// controlFrames() method removed - use isTransferComplete() instead
	// isTransferComplete() has O(1) complexity vs controlFrames() O(N)

	public void printTransferStatus() {
		int received = recv.cardinality();
		int missing = total_seq - received;
		double progress = (received * 100.0) / total_seq;

		// RTT ve congestion info
		String congestionInfo = "";
		if (hybridControl != null) {
			long rttMs = hybridControl.getSmoothedRtt() / 1_000_000;
			long cwndPkts = hybridControl.getCongestionWindow() / PAYLOAD_SIZE;
			congestionInfo = String.format(", RTT=%dms, CWND=%d pkts", rttMs, cwndPkts);
		}

		System.out.printf("Transfer Status: %.2f%% (%d/%d packets, %d missing, cumAck=%d%s)%n",
				progress, received, total_seq, missing, cum_Ack, congestionInfo);
	}

	public void send_Nack_Frame() {
		long mask = build64();
		frame.fill(fileId, cum_Ack, mask);

		int r;
		int retries = 0;
		final int MAX_RETRIES = 20; // Increased from 5 to handle UDP buffer congestion

		try {
			do {
				r = channel.write(frame.buffer().duplicate());
				if (r == 0) {
					// Exponential backoff: start at 100μs, double each retry, max 10ms
					long baseBackoff = 100_000; // 100μs
					long maxBackoff = 10_000_000; // 10ms
					long backoffNs = Math.min(baseBackoff << retries, maxBackoff);

					// Additional congestion-aware adjustment
					if (hybridControl != null) {
						long pacingInterval = hybridControl.getPacingInterval();
						backoffNs = Math.max(backoffNs, pacingInterval / 2);
					}

					LockSupport.parkNanos(backoffNs);
					retries++;
					if (retries >= MAX_RETRIES) {
						System.err.printf("️NACK frame send failed after %d retries (UDP buffer congested)%n",
								MAX_RETRIES);
						return;
					}
				}
			} while (r == 0 && retries < MAX_RETRIES);

			// Success log (only if there were retries)
			if (retries > 0) {
				System.out.printf("[NACK]NACK sent after %d retries%n", retries);
			}
		} catch (IOException e) {
			System.err.println("NACK write failed: " + e.getMessage());
			// Don't throw RuntimeException, just log and continue
		}
	}

	ThreadFactory daemonFactory = r -> {
		Thread t = new Thread(r, "nack-scheduler");
		t.setDaemon(true);
		return t;
	};

	public final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, daemonFactory);

	public final Runnable nack_service = () -> {
		try {
			send_Nack_Frame();
		} catch (Exception e) {
			System.err.println("Thread Error[nack-scheduler]: " + e);
		}
	};

	public ScheduledFuture<?> nackHandle;

	public void startNackLoop() {
		if (nackHandle == null || nackHandle.isCancelled() || nackHandle.isDone()) {
			// Dynamic NACK interval based on RTT
			long nackIntervalMs = 25; // Default 25ms
			if (hybridControl != null) {
				long rttMs = hybridControl.getSmoothedRtt() / 1_000_000; // ns to ms
				if (rttMs > 0) {
					// NACK interval = RTT/4, min 10ms, max 50ms
					nackIntervalMs = Math.max(10, Math.min(50, rttMs / 4));
				}
			}
			nackHandle = scheduler.scheduleAtFixedRate(nack_service, 0, nackIntervalMs, TimeUnit.MILLISECONDS);
		}
	}

	public void stopNackLoop() {
		if (nackHandle != null) {
			nackHandle.cancel(false);
			nackHandle = null;
		}
	}

	public void shutdownScheduler() {
		scheduler.shutdown();
	}

	public void cleanup() {
		stopNackLoop();
		shutdownScheduler();
		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	// Adaptive ACK Configuration
	private static final int ACK_PACKET_THRESHOLD = 16;
	private static final long ACK_TIME_THRESHOLD_NS = 25_000_000; // 25ms
	private int unackedPackets = 0;
	private long lastAckTime = System.nanoTime();

	// Batching Configuration
	private static final int BATCH_SIZE = 16;

	@Override
	public void run() {
		if (channel == null) {
			throw new IllegalStateException("You must bind the channel first");
		}

		System.out.printf("[NACK-SENDER] Starting receive loop for fileId=%d (Adaptive ACK + Batching)%n", fileId);

		// Ensure non-blocking mode for batching efficiency
		try {
			channel.configureBlocking(false);
		} catch (IOException e) {
			System.err.println("Failed to set non-blocking mode: " + e);
		}

		try {
			// No fixed scheduler anymore - ACKs are driven by data arrival
			// startNackLoop();

			int totalPacketCount = 0;

			while (!Thread.currentThread().isInterrupted() && !transferCompleted) {
				// MICRO-BATCHING: Try to read multiple packets
				int packetsRead = 0;
				boolean gapDetected = false;

				for (int i = 0; i < BATCH_SIZE; i++) {
					buf.clear();
					int bytesRead = 0;
					try {
						bytesRead = channel.read(buf);
					} catch (IOException e) {
						System.err.println("Read failed: " + e);
						break;
					}

					if (bytesRead > 0) {
						buf.flip();
						if (processPacket(buf, bytesRead)) {
							packetsRead++;
							totalPacketCount++;
							if (isGapDetected())
								gapDetected = true;
						}
					} else {
						break; // No more data available right now
					}
				}

				if (packetsRead > 0) {
					// Adaptive ACK Logic
					unackedPackets += packetsRead;
					long now = System.nanoTime();

					boolean shouldAck = false;
					if (unackedPackets >= ACK_PACKET_THRESHOLD)
						shouldAck = true;
					if (now - lastAckTime > ACK_TIME_THRESHOLD_NS)
						shouldAck = true;
					if (gapDetected)
						shouldAck = true; // Fast Retransmit Trigger

					if (shouldAck) {
						send_Nack_Frame();
						unackedPackets = 0;
						lastAckTime = now;
					}
				} else {
					// No data received, backoff minimally to save CPU
					LockSupport.parkNanos(50_000); // 50us

					// Keep-alive/Timeout ACK: If we haven't ACKed in a while, do it
					long now = System.nanoTime();
					if (now - lastAckTime > ACK_TIME_THRESHOLD_NS * 2) { // 50ms (idle)
						send_Nack_Frame();
						lastAckTime = now;
					}
				}

				if (transferCompleted)
					break;
			}

			if (transferCompleted) {
				System.out.println("NackSender: All packets received, transfer complete!");
				sendCompletionSignal(); // Ensure sender knows
			}
		} finally {
			cleanup();
		}
	}

	// Helper to reduce logic inside run()
	private boolean processPacket(ByteBuffer fullPacket, int x) {
		if (x < CRC32C_HEADER_SIZE || x > TOTAL_PACKET_SIZE || fullPacket.getLong(OFF_FILE_ID) != fileId) {
			return false;
		}
		// Reset position for processing
		fullPacket.rewind();
		onData(fullPacket);
		return true;
	}

	// Simple gap detection heuristic
	private boolean isGapDetected() {
		// Check if the packet we just received created a gap or filled one?
		// Logic simplified: if we receive out-of-order, it usually implies a gap.
		// Detailed tracking is in onData(), but for fast trigger:
		// If we are missing packets before the latest received seq, it's a gap.
		int latestSeq = cum_Ack; // This is the start of the gap
		return !recv.get(latestSeq) && latestSeq < total_seq;
	}

	public void sendCompletionSignal() {
		try {
			// Özel completion frame gönder
			ByteBuffer completionFrame = ByteBuffer.allocate(8);
			completionFrame.putInt(0xDEADBEEF); // Magic number for completion
			completionFrame.putInt((int) fileId);
			completionFrame.flip();

			channel.write(completionFrame);
			System.out.println("Transfer completion signal sent to sender");
		} catch (IOException e) {
			System.err.println("Failed to send completion signal: " + e);
		}
	}
}
