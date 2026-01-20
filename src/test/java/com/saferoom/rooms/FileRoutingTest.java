package com.saferoom.rooms;

import com.saferoom.rooms.client.logic.FileManager;
import com.saferoom.rooms.grpc.FileMessage;
import com.saferoom.rooms.grpc.FileMeta;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FileRoutingTest {

    @Test
    public void testMetadataRegistration() {
        FileManager manager = new FileManager();
        String fileId = "file-1";

        FileMeta meta = FileMeta.newBuilder()
                .setFileId(fileId)
                .setName("test.txt")
                .setSizeBytes(1024)
                .setPieceCount(10)
                .build();

        manager.handleMeta(meta);

        // Assert state implicitly via behavior or access
        // Since FileManager doesn't expose getters yet, we verify via bitfield logic
        // An unknown file would warn and ignore updates

        FileMessage haveMsg = FileMessage.newBuilder()
                .setType(FileMessage.Type.HAVE)
                .setFileId(fileId)
                .setPieceIndex(0)
                .build();

        manager.handleMessage(haveMsg, "peer-1");
        assertTrue(manager.peerHasPiece(fileId, "peer-1", 0), "Peer should be registered as having piece 0");
    }

    @Test
    public void testPieceRoutingFlow() {
        FileManager manager = new FileManager();
        String fileId = "file-2";
        FileMeta meta = FileMeta.newBuilder().setFileId(fileId).setPieceCount(5).build();
        manager.handleMeta(meta);

        // 1. HAVE
        manager.handleMessage(FileMessage.newBuilder()
                .setType(FileMessage.Type.HAVE)
                .setFileId(fileId)
                .setPieceIndex(3)
                .build(), "peer-A");

        assertTrue(manager.peerHasPiece(fileId, "peer-A", 3));

        // 2. REQUEST (Simulated)
        // In real system, this triggers output. Here just ensure no exception.
        manager.handleMessage(FileMessage.newBuilder()
                .setType(FileMessage.Type.REQUEST)
                .setFileId(fileId)
                .setPieceIndex(3)
                .build(), "peer-B");

        // 3. PIECE (Simulated)
        manager.handleMessage(FileMessage.newBuilder()
                .setType(FileMessage.Type.PIECE)
                .setFileId(fileId)
                .setPieceIndex(3)
                .setData(ByteString.copyFromUtf8("data"))
                .build(), "peer-A");
    }
}
