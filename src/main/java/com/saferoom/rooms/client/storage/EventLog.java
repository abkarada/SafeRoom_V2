package com.saferoom.rooms.client.storage;

import com.saferoom.rooms.grpc.RoomEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * EventLog: specific append-only storage for Room Events.
 * Used for Replication and Sync.
 * Thread-safe.
 */
public class EventLog {
    private final List<RoomEvent> log = new ArrayList<>();
    private final java.util.Set<String> processedIds = new java.util.HashSet<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    // In a real DB-backed impl, we would track last persisted index

    public boolean append(RoomEvent event) {
        lock.writeLock().lock();
        try {
            String msgId = event.getMsgId();
            // Idempotency Check
            if (msgId != null && !msgId.isEmpty()) {
                if (processedIds.contains(msgId)) {
                    return false; // Duplicate ignored
                }
                processedIds.add(msgId);
            }
            log.add(event);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RoomEvent> getEventsSince(long timestamp) {
        lock.readLock().lock();
        try {
            List<RoomEvent> result = new ArrayList<>();
            // Optimization: Binary search could be used if strictly ordered by TS
            // For now, linear scan from end is fine for small-medium logs or v1
            for (RoomEvent e : log) {
                if (e.getTimestamp() > timestamp) {
                    result.add(e);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public RoomEvent getLastEvent() {
        lock.readLock().lock();
        try {
            if (log.isEmpty())
                return null;
            return log.get(log.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return log.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
