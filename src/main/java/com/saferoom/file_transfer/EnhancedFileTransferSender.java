package com.saferoom.file_transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import com.saferoom.transport.FlowControlledEndpoint;

public class EnhancedFileTransferSender {
	private final DatagramChannel channel;
	private volatile boolean stopRequested = false;
	private final FlowControlledEndpoint flowController;
	private final boolean zeroCopyEnabled;
	private final BufferPool bufferPool;
	private FileTransferRuntime runtime;

	// QUIC-inspired congestion control
	private HybridCongestionController hybridControl;
	private EnhancedNackListener enhancedNackListener;
	private ChunkManager chunkManager;
	private TransferListener transferListener;

	private static final ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r);
		t.setDaemon(true);
		t.setName("enhanced-transfer-" + t.threadId());
		return t;
	});

	public static final long TURBO_MAX = 256L << 20; // 256 MB
	public static final int SLICE_SIZE = 1450; // Maximum payload without fragmentation
	public static final int MAX_TRY = 4;
	public static final int BACKOFF_NS = 0; // HİÇ BEKLEME YOK!

	public EnhancedFileTransferSender(DatagramChannel ch) {
		this(ch, null);
	}

	public EnhancedFileTransferSender(DatagramChannel ch, FlowControlledEndpoint flowController) {
		this.channel = ch;
		this.flowController = flowController;
		this.zeroCopyEnabled = Boolean.parseBoolean(
				System.getProperty("saferoom.transfer.zeroCopy.enabled", "true"));
		if (zeroCopyEnabled) {
			this.bufferPool = null;
		} else {
			int poolSize = Integer.getInteger("saferoom.transfer.pool.size", 8);
			this.bufferPool = new BufferPool(poolSize, SLICE_SIZE);
		}
	}

	public interface TransferListener {
		void onPacketProgress(long fileId, long bytesSent, long totalBytes);

		void onTransferComplete(long fileId);

		void onTransferFailed(long fileId, Throwable error);
	}

	public void setTransferListener(TransferListener listener) {
		this.transferListener = listener;
	}

	public void requestStop() {
		this.stopRequested = true;
	}

	public boolean handshake(long fileId, long file_size, int total_seq) throws IOException {
		System.out.println("[SENDER-HANDSHAKE] ╔════════════════════════════════════════════════");
		System.out.println("[SENDER-HANDSHAKE] ║ handshake() ENTERED");
		System.out.printf("[SENDER-HANDSHAKE]  ║ Thread: %s%n", Thread.currentThread().getName());
		System.out.printf("[SENDER-HANDSHAKE]  ║ fileId=%d, size=%d, chunks=%d%n",
				fileId, file_size, total_seq);
		System.out.printf("[SENDER-HANDSHAKE]  ║ Channel connected: %s%n",
				channel != null ? channel.isConnected() : "NULL");
		System.out.println("[SENDER-HANDSHAKE] ╚════════════════════════════════════════════════");

		if (channel == null)
			throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		long candidate_file_Id = -1;
		HandShake_Packet pkt = new HandShake_Packet();
		pkt.make_SYN(fileId, file_size, total_seq);

		System.out.printf("[FILE-HANDSHAKE] 🤝 Sending SYN for fileId=%d, size=%d, chunks=%d%n",
				fileId, file_size, total_seq);

		// Send initial SYN
		int bytesSent = channel.write(pkt.get_header().duplicate());
		System.out.printf("[FILE-HANDSHAKE] 📤 SYN sent: %d bytes%n", bytesSent);

		ByteBuffer buffer = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

		long ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); // 30 saniye timeout
		long lastSynTime = System.nanoTime();
		int r;
		int synRetryCount = 0;

		do {
			if (System.nanoTime() > ackDeadline) {
				System.err.println("Handshake ACK timeout after 30 seconds");
				return false;
			}

			// Her 250ms'de bir SYN tekrar gönder (receiver başlayana kadar)
			long now = System.nanoTime();
			if (now - lastSynTime > TimeUnit.MILLISECONDS.toNanos(250)) {
				pkt.resetForRetransmitter();
				channel.write(pkt.get_header().duplicate());
				synRetryCount++;
				if (synRetryCount % 10 == 0) {
					System.out.printf("[FILE-HANDSHAKE] 🔄 SYN retry #%d (waiting for receiver...)%n", synRetryCount);
				}
				lastSynTime = now;
			}

			// CRITICAL: Clear buffer before each read attempt!
			buffer.clear();
			r = channel.read(buffer);
			if (r <= 0)
				LockSupport.parkNanos(1_000_000); // 1ms bekleme
		} while (r <= 0);

		buffer.flip();
		if (r >= HandShake_Packet.HEADER_SIZE && buffer.get(0) == 0x10) {
			buffer.position(1); // Position'ı 1'e set et
			candidate_file_Id = buffer.getLong(); // Relative okuma

			System.out.printf("[SENDER-HANDSHAKE] ✅ ACK received: fileId=%d (after %d SYN retries)%n",
					candidate_file_Id, synRetryCount);
		} else {
			System.err.printf("[SENDER-HANDSHAKE] ❌ Invalid ACK: size=%d, type=0x%02X%n",
					r, buffer.get(0));
		}

		if (candidate_file_Id == fileId) {
			pkt.make_SYN_ACK(fileId);
			try {
				// SYN_ACK için de timeout ekle
				long synAckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while (channel.write(pkt.get_header().duplicate()) == 0) {
					if (System.nanoTime() > synAckDeadline) {
						System.err.println("SYN_ACK send timeout");
						return false;
					}
					pkt.resetForRetransmitter();
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
				}
				System.out.println("[SENDER-HANDSHAKE] ✅ SYN_ACK sent successfully");

				// Give receiver time to process SYN_ACK before data flood
				LockSupport.parkNanos(5_000_000); // 5ms bekle

			} catch (IOException e) {
				System.err.println("SYN+ACK Signal Error: " + e);
				return false;
			}
			return true;
		}
		return false;
	}

	public void sendOne(CRC32C crc, CRC32C_Packet pkt,
			MappedByteBuffer mem, long fileId,
			int seqNo, int totalSeq, int take, int off) throws IOException {
		ByteBuffer payload = preparePayload(mem, off, take);
		ByteBuffer headerBuffer = pkt.headerBuffer();

		crc.reset();
		ByteBuffer crcView = payload.duplicate();
		crc.update(crcView);
		int crc32c = (int) crc.getValue();

		pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);

		ByteBuffer payloadForSend = payload.duplicate();
		payloadForSend.position(0).limit(take);
		ByteBuffer[] frame = new ByteBuffer[] { headerBuffer, payloadForSend };

		if (enhancedNackListener != null) {
			enhancedNackListener.recordPacketSendTime(seqNo);
		}

		if (hybridControl != null) {
			hybridControl.rateLimitSend(); // Rate pacing
		}

		try {
			final int packetBytes = CRC32C_Packet.HEADER_SIZE + take;
			waitForBackpressure(packetBytes);
			channel.write(frame);

			if (hybridControl != null) {
				hybridControl.onPacketSent(packetBytes);
			}
		} catch (IOException e) {
			System.err.println("Frame sending error: " + e);
			throw e;
		} finally {
			if (!zeroCopyEnabled) {
				bufferPool.release(payload);
			}
		}
	}

	public void sendFile(Path filePath, long fileId) throws IOException {
		sendFileInternal(filePath, fileId, true); // With handshake
	}

	/**
	 * Send file WITHOUT handshake (Legacy path, use with caution)
	 */
	public void sendFileWithoutHandshake(Path filePath, long fileId) throws IOException {
		sendFileInternal(filePath, fileId, false); // Skip handshake
	}

	private void sendFileInternal(Path filePath, long fileId, boolean doHandshake) throws IOException {
		System.out.println("[SEND-INTERNAL] ╔════════════════════════════════════════════════");
		System.out.printf("[SEND-INTERNAL] ║ sendFileInternal() ENTERED%n");
		System.out.printf("[SEND-INTERNAL] ║ Thread: %s%n", Thread.currentThread().getName());
		System.out.printf("[SEND-INTERNAL] ║ filePath: %s%n", filePath.getFileName());
		System.out.printf("[SEND-INTERNAL] ║ fileId: %d%n", fileId);
		System.out.printf("[SEND-INTERNAL] ║ doHandshake: %s%n", doHandshake);
		System.out.println("[SEND-INTERNAL] ╚════════════════════════════════════════════════");

		if (channel == null)
			throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		if (stopRequested)
			throw new IllegalStateException("Transfer was stopped");

		try (FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)) {
			long fileSize = fc.size();

			System.out.printf("[SEND-INTERNAL] File opened: size=%d bytes%n", fileSize);

			// Initialize ChunkManager for unlimited file size support
			this.chunkManager = new ChunkManager(filePath, SLICE_SIZE);
			int totalSeq = chunkManager.getTotalSequenceCount();

			System.out.printf("[SEND-INTERNAL] ChunkManager initialized: %d chunks%n", totalSeq);

			// Thread-safe için her thread kendi instance'larını kullanacak
			CRC32C initialCrc = new CRC32C();
			CRC32C_Packet initialPkt = new CRC32C_Packet();

			System.out.printf("[SEND-INTERNAL] ⚙️ doHandshake check: %s%n", doHandshake);

			// ALWAYS perform handshake in WebRTC DataChannel mode
			// The legacy KeepAliveManager shortcut is no longer supported
			if (doHandshake) {
				System.out.println("[SEND-INTERNAL] ✅ Handshake WILL BE PERFORMED");
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
				final long MAX_BACKOFF = 10_000_000L;
				long backoff = 1_000_000L;
				boolean hand_shaking;
				do {
					hand_shaking = handshake(fileId, fileSize, totalSeq);
					if (hand_shaking)
						break;

					if (Thread.currentThread().isInterrupted()) {
						throw new IllegalStateException("Handshake Thread interrupted");
					}
					if (System.nanoTime() > deadline) {
						throw new IllegalStateException("Handshake timeout");
					}
					LockSupport.parkNanos(backoff);
					if (backoff < MAX_BACKOFF) {
						backoff = Math.min(MAX_BACKOFF, backoff << 1);
					}
				} while (!hand_shaking);
			} else {
				// This path should ideally not be used anymore, but we'll keep the log for now
				System.out.println("[FILE-SEND] ⏩ Skipping handshake (WARNING: Legacy path)");
			}

			ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();

			// Transfer completion için latch
			final CountDownLatch transferCompleteLatch = new CountDownLatch(1);

			// Enhanced NACK listener'ı başlat
			this.enhancedNackListener = new EnhancedNackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS);

			// Completion callback ayarla
			enhancedNackListener.onTransferComplete = () -> {
				System.out.println("Sender: Transfer completion detected!");
				transferCompleteLatch.countDown();
			};

			// QUIC-inspired hybrid congestion control (BBR)
			this.hybridControl = new HybridCongestionController();

			// Enhanced NACK listener'a congestion control referansını ver
			enhancedNackListener.hybridControl = hybridControl;

			// Auto-tuning BBR handles network modes automatically now

			final boolean[] initialTransmissionDone = { false };
			this.runtime = new FileTransferRuntime();
			runtime.start(enhancedNackListener);
			runtime.start(createStatsTask());
			runtime.start(createRetransmissionTask(retxQueue, fileId, totalSeq, initialTransmissionDone));

			long bytesSent = 0;

			// ENHANCED WINDOWED TRANSMISSION - QUIC-style with Chunk Support
			System.out.println(
					"Starting QUIC-inspired windowed transmission with chunked I/O (Micro-Batching Enabled)...");
			int seqNo = 0;
			long startTime = System.currentTimeMillis();
			long lastProgressTime = startTime;

			// Chunk-based sequential transmission
			int chunkCount = chunkManager.getChunkCount();

			// MICRO-BATCHING Configuration
			final int BATCH_SIZE = 16;

			for (int chunkIdx = 0; chunkIdx < chunkCount; chunkIdx++) {
				MappedByteBuffer chunkBuffer = chunkManager.getChunk(chunkIdx);

				// Send all sequences in this chunk
				for (int off = 0; off < chunkBuffer.capacity();) {
					// Send a BATCH of packets
					for (int b = 0; b < BATCH_SIZE && off < chunkBuffer.capacity(); b++) {
						int remaining = chunkBuffer.capacity() - off;
						int take = Math.min(SLICE_SIZE, remaining);

						sendOne(initialCrc, initialPkt, chunkBuffer, fileId, seqNo, totalSeq, take, off);
						bytesSent += take;

						off += take;
						seqNo++;
					}

					// Update listeners and stats after batch (reduce overhead)
					if (transferListener != null) {
						transferListener.onPacketProgress(fileId, bytesSent, fileSize);
					}

					// Enhanced progress display
					if (System.currentTimeMillis() - lastProgressTime > 1000) {
						double progress = (double) seqNo / totalSeq * 100;
						long elapsed = System.currentTimeMillis() - startTime;
						double throughputMbps = (seqNo * SLICE_SIZE * 8.0) / (elapsed * 1000.0);
						System.out.printf(" Progress: %.1f%% (Chunk %d/%d), Throughput: %.1f Mbps\n",
								progress, chunkIdx + 1, chunkCount, throughputMbps);
						System.out.println(" " + hybridControl.getStats());
						lastProgressTime = System.currentTimeMillis();
					}
				}
			}

			initialTransmissionDone[0] = true;
			System.out.println("Initial transmission completed, waiting for retransmissions...");

			boolean completedSuccessfully = false;
			// Transfer completion bekle
			try {
				boolean completed = transferCompleteLatch.await(300, TimeUnit.SECONDS);
				if (completed) {
					System.out.println(" File transfer completed successfully!");
					System.out.println(" Final stats: " + hybridControl.getStats());
					completedSuccessfully = true;
				} else {
					System.err.println(" Transfer timeout - network issue or very large file");
					if (transferListener != null) {
						transferListener.onTransferFailed(fileId, new IllegalStateException("Transfer timeout"));
					}
				}
			} catch (InterruptedException e) {
				System.err.println("Transfer interrupted");
				Thread.currentThread().interrupt();
				if (transferListener != null) {
					transferListener.onTransferFailed(fileId, e);
				}
			}
			if (completedSuccessfully && transferListener != null) {
				transferListener.onTransferComplete(fileId);
			}
		} finally {
			System.out.println(" Cleaning up enhanced transfer threads...");
			if (runtime != null) {
				try {
					runtime.close();
				} catch (RuntimeException ex) {
					System.err.println(" Runtime shutdown error: " + ex.getMessage());
				} finally {
					runtime = null;
				}
			}
			if (hybridControl != null) {
				System.out.println(" Transfer summary: " + hybridControl.getStats());
			}
		}
	}

	private Runnable createStatsTask() {
		return () -> {
			while (!Thread.currentThread().isInterrupted() && !stopRequested) {
				try {
					Thread.sleep(2000);
					if (hybridControl != null) {
						System.out.println(" " + hybridControl.getStats());
					}
					if (enhancedNackListener != null) {
						System.out.println(" " + enhancedNackListener.getRttStats());
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		};
	}

	private Runnable createRetransmissionTask(ConcurrentLinkedQueue<Integer> retxQueue,
			long fileId, int totalSeq, boolean[] initialTransmissionDone) {
		return () -> {
			CRC32C retxCrc = new CRC32C();
			CRC32C_Packet retxPkt = new CRC32C_Packet();
			while (!Thread.currentThread().isInterrupted() && !stopRequested) {
				Integer miss = retxQueue.poll();
				if (miss == null) {
					LockSupport.parkNanos(initialTransmissionDone[0] ? 1_000_000 : 50_000);
					continue;
				}
				if (miss < 0 || miss >= totalSeq) {
					continue;
				}
				if (hybridControl != null && !hybridControl.canSendPacket()) {
					retxQueue.offer(miss);
					LockSupport.parkNanos(100_000);
					continue;
				}
				try {
					int chunkIdx = chunkManager.findChunkForSequence(miss);
					if (chunkIdx < 0) {
						continue;
					}
					ChunkMetadata chunkMeta = chunkManager.getChunkMetadata(chunkIdx);
					MappedByteBuffer chunkBuffer = chunkManager.getChunk(chunkIdx);
					int localSeq = chunkMeta.toLocalSequence(miss);
					int localOff = chunkMeta.getLocalOffset(localSeq, SLICE_SIZE);
					int take = chunkMeta.getPayloadSize(localSeq, SLICE_SIZE);
					if (take > 0) {
						sendOne(retxCrc, retxPkt, chunkBuffer, fileId, miss, totalSeq, take, localOff);
					}
				} catch (IOException e) {
					System.err.println("Retransmission error for seq " + miss + ": " + e);
				}
			}
		};
	}

	public static void shutdownThreadPool() {
		threadPool.shutdown();
		try {
			if (!threadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
				threadPool.shutdownNow();
			}
		} catch (InterruptedException e) {
			threadPool.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private void waitForBackpressure(int bytesToSend) throws IOException {
		if (flowController == null) {
			return;
		}
		final long maxBuffered = Long.getLong("saferoom.transfer.buffer.maxBytes", 8L << 20);
		while (flowController.bufferedAmount() + bytesToSend > maxBuffered) {
			if (!flowController.isOpen()) {
				throw new IOException("Transport closed");
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
		}
	}

	private ByteBuffer preparePayload(MappedByteBuffer mem, int off, int take) {
		ByteBuffer slice = mem.duplicate();
		slice.position(off).limit(off + take);
		if (zeroCopyEnabled) {
			return slice.slice();
		}
		ByteBuffer buffer = bufferPool.acquire();
		buffer.clear();
		buffer.put(slice);
		buffer.flip();
		return buffer;
	}
}
