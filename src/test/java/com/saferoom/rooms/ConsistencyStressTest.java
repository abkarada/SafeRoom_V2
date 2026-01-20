package com.saferoom.rooms;

import com.saferoom.rooms.client.storage.EventLog;
import com.saferoom.rooms.grpc.RoomEvent;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ConsistencyStressTest {

    @Test
    public void testDeduplication() {
        EventLog eventLog = new EventLog();
        String msgId = UUID.randomUUID().toString();

        RoomEvent event = RoomEvent.newBuilder()
                .setType(RoomEvent.EventType.ROOM_PRESENCE)
                .setMsgId(msgId)
                .setTimestamp(System.currentTimeMillis())
                .build();

        assertTrue(eventLog.append(event), "First append should succeed");
        assertFalse(eventLog.append(event), "Second append (duplicate) should fail");

        assertEquals(1, eventLog.size());
    }

    @Test
    public void testConcurrentAppends() throws InterruptedException {
        EventLog eventLog = new EventLog();
        int threads = 10;
        int eventsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < eventsPerThread; j++) {
                        String msgId = UUID.randomUUID().toString();
                        RoomEvent event = RoomEvent.newBuilder()
                                .setType(RoomEvent.EventType.ROOM_PRESENCE)
                                .setMsgId(msgId)
                                .setTimestamp(System.currentTimeMillis())
                                .build();
                        if (eventLog.append(event)) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(threads * eventsPerThread, eventLog.size());
        assertEquals(threads * eventsPerThread, successCount.get());
    }

    @Test
    public void testConcurrentDuplicates() throws InterruptedException {
        EventLog eventLog = new EventLog();
        int threads = 10;
        String sharedMsgId = "duplicate-id";

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    RoomEvent event = RoomEvent.newBuilder()
                            .setType(RoomEvent.EventType.ROOM_PRESENCE)
                            .setMsgId(sharedMsgId)
                            .setTimestamp(System.currentTimeMillis())
                            .build();
                    if (eventLog.append(event)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(1, eventLog.size());
        assertEquals(1, successCount.get(), "Only one thread should succeed");
    }
}
