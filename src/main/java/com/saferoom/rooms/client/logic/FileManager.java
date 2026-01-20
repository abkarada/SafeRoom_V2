package com.saferoom.rooms.client.logic;

import com.saferoom.rooms.grpc.FileMessage;
import com.saferoom.rooms.grpc.FileMeta;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * FileManager: Manages File Metadata and Piece Routing (Torrent layer).
 * Owned by DataFSM.
 */
public class FileManager {
    private static final Logger logger = Logger.getLogger(FileManager.class.getName());

    // Index: FileId -> Metadata
    private final Map<String, FileMeta> fileIndex = new HashMap<>();

    // Bitfields: FileId -> NodeId -> BitSet (Pieces owned by peer)
    private final Map<String, Map<String, BitSet>> swarmState = new HashMap<>();

    public void handleMeta(FileMeta meta) {
        if (meta == null || meta.getFileId().isEmpty())
            return;

        if (!fileIndex.containsKey(meta.getFileId())) {
            fileIndex.put(meta.getFileId(), meta);
            swarmState.put(meta.getFileId(), new HashMap<>());
            logger.info("[FileManager] Registered file: " + meta.getName() + " (" + meta.getSizeBytes() + " bytes)");
        }
    }

    public void handleMessage(FileMessage msg, String srcNodeId) {
        String fileId = msg.getFileId();
        if (!fileIndex.containsKey(fileId)) {
            logger.warning("[FileManager] Received msg for unknown file: " + fileId);
            return;
        }

        switch (msg.getType()) {
            case HAVE:
                handleHave(fileId, srcNodeId, msg.getPieceIndex());
                break;
            case REQUEST:
                handleRequest(fileId, srcNodeId, msg.getPieceIndex());
                break;
            case PIECE:
                handlePiece(fileId, srcNodeId, msg.getPieceIndex(), msg.getData());
                break;
            default:
                break;
        }
    }

    private void handleHave(String fileId, String nodeId, int pieceIndex) {
        Map<String, BitSet> fileSwarm = swarmState.get(fileId);
        fileSwarm.putIfAbsent(nodeId, new BitSet());
        fileSwarm.get(nodeId).set(pieceIndex);
        logger.info("[FileManager] Node " + nodeId + " has piece " + pieceIndex + " of " + fileId);
    }

    private void handleRequest(String fileId, String nodeId, int pieceIndex) {
        // Logic: Check if we have piece. If yes, send PIECE msg.
        // For Sprint 5 verification (No Disk I/O), we verify valid request only.
        logger.info("[FileManager] Node " + nodeId + " requested piece " + pieceIndex);
    }

    private void handlePiece(String fileId, String fromNodeId, int pieceIndex, com.google.protobuf.ByteString data) {
        // Logic: Verify Hash against FileMeta
        FileMeta meta = fileIndex.get(fileId);
        // Mock verification
        logger.info("[FileManager] Received piece " + pieceIndex + " of " + meta.getName() + " from " + fromNodeId);
    }

    // For testing/verification
    public boolean peerHasPiece(String fileId, String nodeId, int pieceIndex) {
        if (!swarmState.containsKey(fileId))
            return false;
        Map<String, BitSet> swarm = swarmState.get(fileId);
        return swarm.containsKey(nodeId) && swarm.get(nodeId).get(pieceIndex);
    }
}
