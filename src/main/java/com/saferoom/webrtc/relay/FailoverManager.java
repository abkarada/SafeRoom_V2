package com.saferoom.webrtc.relay;

import com.saferoom.webrtc.relay.TreeNode.NodeRole;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * FailoverManager - Handles connection failures and automatic recovery in SDRT
 * 
 * Responsibilities:
 * 1. Monitor heartbeat from parent and children
 * 2. Detect connection failures (timeout)
 * 3. Calculate backup parents proactively
 * 4. Execute automatic reconnection on parent loss
 * 5. Promote to ROOT if necessary
 * 
 * Failover Strategy:
 * - Send heartbeat every 1 second
 * - Declare connection dead after 3 seconds without heartbeat
 * - Maintain list of backup parents (pre-calculated)
 * - Switch to backup immediately on failure
 * - If no backup available, request new parent from server
 */
public class FailoverManager {
    
    private static final Logger logger = Logger.getLogger(FailoverManager.class.getName());
    
    // Components
    private final TreeNode localNode;
    private final ConnectionMetrics metrics;
    
    // Heartbeat configuration
    private static final long HEARTBEAT_INTERVAL_MS = 1000;     // Send every 1 second
    private static final long HEARTBEAT_TIMEOUT_MS = 3000;      // Declare dead after 3 seconds
    private static final int BACKUP_PARENT_COUNT = 2;           // Number of backup parents to maintain
    
    // Schedulers
    private final ScheduledExecutorService heartbeatScheduler;
    private final ScheduledExecutorService monitorScheduler;
    private volatile boolean running = false;
    
    // Heartbeat tracking
    private final Map<String, Long> lastHeartbeatReceived = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeatSent = new ConcurrentHashMap<>();
    
    // Backup parents (ordered by quality score)
    private final ConcurrentLinkedQueue<String> backupParents = new ConcurrentLinkedQueue<>();
    
    // Callbacks
    private ConnectionFailureCallback failureCallback;
    private RecoveryCallback recoveryCallback;
    
    /**
     * Callback interface for connection failure events
     */
    public interface ConnectionFailureCallback {
        void onParentConnectionLost(String parentId);
        void onChildConnectionLost(String childId);
    }
    
    /**
     * Callback interface for recovery events
     */
    public interface RecoveryCallback {
        void onRecoveryNeeded(String oldParentId, String newParentId);
        void onPromotionNeeded(); // Promote to ROOT
    }
    
    /**
     * Constructor
     */
    public FailoverManager(TreeNode localNode, ConnectionMetrics metrics) {
        this.localNode = localNode;
        this.metrics = metrics;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(1);
        this.monitorScheduler = Executors.newScheduledThreadPool(1);
        
        logger.info(String.format("FailoverManager created for node %s", 
                                  localNode.getUserId()));
    }
    
    /**
     * Start failover monitoring
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        
        // Send heartbeats periodically
        heartbeatScheduler.scheduleAtFixedRate(
            this::sendHeartbeats,
            0,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        // Monitor heartbeats periodically
        monitorScheduler.scheduleAtFixedRate(
            this::monitorHeartbeats,
            HEARTBEAT_TIMEOUT_MS,
            1000, // Check every second
            TimeUnit.MILLISECONDS
        );
        
        // Update backup parents periodically
        monitorScheduler.scheduleAtFixedRate(
            this::updateBackupParents,
            5000, // Start after 5 seconds
            10000, // Update every 10 seconds
            TimeUnit.MILLISECONDS
        );
        
        logger.info("FailoverManager started");
    }
    
    /**
     * Stop failover monitoring
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        heartbeatScheduler.shutdown();
        monitorScheduler.shutdown();
        
        try {
            heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS);
            monitorScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            monitorScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("FailoverManager stopped");
    }
    
    /**
     * Send heartbeats to parent and all children
     */
    private void sendHeartbeats() {
        try {
            long now = System.currentTimeMillis();
            
            // Send to parent (if exists and not ROOT)
            TreeNode parent = localNode.getParent();
            if (parent != null && localNode.getRole() != NodeRole.ROOT) {
                sendHeartbeatToParent(parent.getUserId());
                lastHeartbeatSent.put(parent.getUserId(), now);
            }
            
            // Send to all children
            for (TreeNode child : localNode.getChildren()) {
                sendHeartbeatToChild(child.getUserId());
                lastHeartbeatSent.put(child.getUserId(), now);
            }
            
        } catch (Exception e) {
            logger.warning("Error sending heartbeats: " + e.getMessage());
        }
    }
    
    /**
     * Send heartbeat to parent
     */
    private void sendHeartbeatToParent(String parentId) {
        // This would send a HEARTBEAT MediaPacket via DataChannel
        // For now, just log it
        logger.finest("Heartbeat → parent: " + parentId);
    }
    
    /**
     * Send heartbeat to child
     */
    private void sendHeartbeatToChild(String childId) {
        // This would send a HEARTBEAT MediaPacket via DataChannel
        logger.finest("Heartbeat → child: " + childId);
    }
    
    /**
     * Record heartbeat received from peer
     * 
     * @param peerId Peer that sent heartbeat
     */
    public void recordHeartbeat(String peerId) {
        long now = System.currentTimeMillis();
        lastHeartbeatReceived.put(peerId, now);
        logger.finest("Heartbeat received from: " + peerId);
    }
    
    /**
     * Monitor heartbeats and detect timeouts
     */
    private void monitorHeartbeats() {
        try {
            long now = System.currentTimeMillis();
            
            // Check parent heartbeat
            TreeNode parent = localNode.getParent();
            if (parent != null) {
                String parentId = parent.getUserId();
                Long lastHeartbeat = lastHeartbeatReceived.get(parentId);
                
                if (lastHeartbeat != null && 
                    (now - lastHeartbeat) > HEARTBEAT_TIMEOUT_MS) {
                    handleParentTimeout(parentId);
                }
            }
            
            // Check children heartbeats
            for (TreeNode child : localNode.getChildren()) {
                String childId = child.getUserId();
                Long lastHeartbeat = lastHeartbeatReceived.get(childId);
                
                if (lastHeartbeat != null && 
                    (now - lastHeartbeat) > HEARTBEAT_TIMEOUT_MS) {
                    handleChildTimeout(childId);
                }
            }
            
        } catch (Exception e) {
            logger.warning("Error monitoring heartbeats: " + e.getMessage());
        }
    }
    
    /**
     * Handle parent connection timeout
     */
    private void handleParentTimeout(String parentId) {
        logger.warning(String.format("Parent connection timeout: %s", parentId));
        
        // Notify callback
        if (failureCallback != null) {
            failureCallback.onParentConnectionLost(parentId);
        }
        
        // Attempt recovery
        attemptParentRecovery(parentId);
    }
    
    /**
     * Handle child connection timeout
     */
    private void handleChildTimeout(String childId) {
        logger.warning(String.format("Child connection timeout: %s", childId));
        
        // Notify callback
        if (failureCallback != null) {
            failureCallback.onChildConnectionLost(childId);
        }
        
        // Remove child from tree
        TreeNode child = localNode.getChildren().stream()
            .filter(c -> c.getUserId().equals(childId))
            .findFirst()
            .orElse(null);
        
        if (child != null) {
            localNode.removeChild(child);
            lastHeartbeatReceived.remove(childId);
            lastHeartbeatSent.remove(childId);
        }
    }
    
    /**
     * Attempt to recover from parent failure
     */
    private void attemptParentRecovery(String oldParentId) {
        logger.info("Attempting parent recovery...");
        
        // Remove old parent tracking
        lastHeartbeatReceived.remove(oldParentId);
        lastHeartbeatSent.remove(oldParentId);
        
        // Try backup parent
        String backupParentId = backupParents.poll();
        
        if (backupParentId != null) {
            logger.info(String.format("Switching to backup parent: %s", backupParentId));
            
            if (recoveryCallback != null) {
                recoveryCallback.onRecoveryNeeded(oldParentId, backupParentId);
            }
            
        } else {
            // No backup available - check if we should become ROOT
            if (localNode.getRole() == NodeRole.RELAY && 
                localNode.getChildCount() > 0) {
                logger.info("No backup parent available - promoting to ROOT");
                
                if (recoveryCallback != null) {
                    recoveryCallback.onPromotionNeeded();
                }
                
            } else {
                logger.warning("No backup parent available - requesting new parent from server");
                // This would trigger signaling to request new parent
            }
        }
    }
    
    /**
     * Update backup parent list based on metrics
     */
    private void updateBackupParents() {
        try {
            // Clear old backups
            backupParents.clear();
            
            // Get current parent
            TreeNode currentParent = localNode.getParent();
            String currentParentId = currentParent != null ? 
                                     currentParent.getUserId() : null;
            
            // Get all peers sorted by score
            metrics.getAllMetrics().entrySet().stream()
                .filter(e -> !e.getKey().equals(localNode.getUserId())) // Not self
                .filter(e -> !e.getKey().equals(currentParentId))       // Not current parent
                .filter(e -> !metrics.areMetricsStale(e.getKey()))     // Fresh metrics
                .filter(e -> e.getValue().isHealthy())                  // Healthy connection
                .sorted((a, b) -> Double.compare(
                    b.getValue().calculateScore(),
                    a.getValue().calculateScore()
                ))
                .limit(BACKUP_PARENT_COUNT)
                .forEach(e -> {
                    backupParents.offer(e.getKey());
                    logger.fine(String.format("Backup parent: %s (score: %.3f)", 
                                             e.getKey(), e.getValue().calculateScore()));
                });
            
            logger.fine(String.format("Updated backup parents: %d available", 
                                     backupParents.size()));
            
        } catch (Exception e) {
            logger.warning("Error updating backup parents: " + e.getMessage());
        }
    }
    
    /**
     * Get current backup parent count
     */
    public int getBackupParentCount() {
        return backupParents.size();
    }
    
    /**
     * Get heartbeat statistics for a peer
     */
    public Map<String, Object> getHeartbeatStats(String peerId) {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        
        Long lastReceived = lastHeartbeatReceived.get(peerId);
        Long lastSent = lastHeartbeatSent.get(peerId);
        long now = System.currentTimeMillis();
        
        if (lastReceived != null) {
            stats.put("lastReceivedMs", now - lastReceived);
            stats.put("isAlive", (now - lastReceived) < HEARTBEAT_TIMEOUT_MS);
        } else {
            stats.put("lastReceivedMs", -1);
            stats.put("isAlive", false);
        }
        
        if (lastSent != null) {
            stats.put("lastSentMs", now - lastSent);
        } else {
            stats.put("lastSentMs", -1);
        }
        
        return stats;
    }
    
    /**
     * Set failure callback
     */
    public void setFailureCallback(ConnectionFailureCallback callback) {
        this.failureCallback = callback;
    }
    
    /**
     * Set recovery callback
     */
    public void setRecoveryCallback(RecoveryCallback callback) {
        this.recoveryCallback = callback;
    }
    
    /**
     * Force heartbeat check (for testing)
     */
    public void forceHeartbeatCheck() {
        logger.info("Forced heartbeat check triggered");
        monitorHeartbeats();
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        stop();
        lastHeartbeatReceived.clear();
        lastHeartbeatSent.clear();
        backupParents.clear();
        logger.info("FailoverManager cleanup complete");
    }
}
