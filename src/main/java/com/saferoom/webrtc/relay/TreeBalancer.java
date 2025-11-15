package com.saferoom.webrtc.relay;

import com.saferoom.webrtc.relay.ConnectionMetrics.PeerMetrics;
import com.saferoom.webrtc.relay.TreeNode.NodeRole;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * TreeBalancer - Dynamic tree rebalancing for SDRT
 * 
 * Responsibilities:
 * 1. Monitor connection metrics for all peers
 * 2. Find better parents based on quality scores
 * 3. Execute seamless parent switching
 * 4. Redistribute children when nodes are overloaded
 * 5. Maintain tree health and balance
 * 
 * Algorithm:
 * - Every 5-10 seconds, evaluate if current parent is optimal
 * - If a better parent exists (significantly better score), switch
 * - If node is overloaded (too many children), redistribute
 * - Minimize disruption during transitions
 */
public class TreeBalancer {
    
    private static final Logger logger = Logger.getLogger(TreeBalancer.class.getName());
    
    // Components
    private final TreeNode localNode;
    private final ConnectionMetrics metrics;
    private final String roomId;
    
    // Rebalancing configuration
    private static final long REBALANCE_INTERVAL_SEC = 10;      // Check every 10 seconds
    private static final double MIN_SCORE_IMPROVEMENT = 0.15;   // 15% improvement needed to switch
    private static final int MAX_CHILDREN_THRESHOLD = 5;        // Max children before redistribution
    private static final double CPU_OVERLOAD_THRESHOLD = 0.85;  // 85% CPU = overloaded
    
    // Scheduler
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    // Callback for parent switch events
    private ParentSwitchCallback switchCallback;
    
    /**
     * Callback interface for parent switch notifications
     */
    public interface ParentSwitchCallback {
        void onParentSwitch(String oldParentId, String newParentId);
    }
    
    /**
     * Constructor
     */
    public TreeBalancer(TreeNode localNode, ConnectionMetrics metrics, String roomId) {
        this.localNode = localNode;
        this.metrics = metrics;
        this.roomId = roomId;
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        logger.info(String.format("TreeBalancer created for node %s in room %s", 
                                  localNode.getUserId(), roomId));
    }
    
    /**
     * Start automatic rebalancing
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        
        // Schedule periodic rebalancing
        scheduler.scheduleWithFixedDelay(
            this::rebalanceTree,
            REBALANCE_INTERVAL_SEC,
            REBALANCE_INTERVAL_SEC,
            TimeUnit.SECONDS
        );
        
        logger.info("TreeBalancer started (interval: " + REBALANCE_INTERVAL_SEC + "s)");
    }
    
    /**
     * Stop automatic rebalancing
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("TreeBalancer stopped");
    }
    
    /**
     * Main rebalancing logic (called periodically)
     */
    private void rebalanceTree() {
        try {
            logger.fine("Running tree rebalance check...");
            
            NodeRole role = localNode.getRole();
            
            // ROOT nodes don't need parents
            if (role == NodeRole.ROOT) {
                // Check if ROOT is overloaded
                if (isOverloaded()) {
                    handleRootOverload();
                }
                return;
            }
            
            // LEAF and RELAY: Check if we need a better parent
            if (role == NodeRole.LEAF || role == NodeRole.RELAY) {
                String betterParent = findBetterParent();
                if (betterParent != null) {
                    switchParent(betterParent);
                }
            }
            
            // RELAY: Check if we need to redistribute children
            if (role == NodeRole.RELAY && isOverloaded()) {
                redistributeChildren();
            }
            
        } catch (Exception e) {
            logger.warning("Error during tree rebalancing: " + e.getMessage());
        }
    }
    
    /**
     * Find a better parent based on metrics
     * 
     * @return User ID of better parent, or null if current parent is optimal
     */
    private String findBetterParent() {
        TreeNode currentParent = localNode.getParent();
        if (currentParent == null) {
            logger.fine("No current parent to evaluate");
            return null;
        }
        
        String currentParentId = currentParent.getUserId();
        PeerMetrics currentMetrics = metrics.getMetrics(currentParentId);
        
        if (currentMetrics == null || metrics.areMetricsStale(currentParentId)) {
            logger.warning("Current parent metrics unavailable or stale");
            return null;
        }
        
        double currentScore = currentMetrics.calculateScore();
        
        // Get all potential parent candidates
        Map<String, PeerMetrics> allMetrics = metrics.getAllMetrics();
        
        // Find best candidate
        String bestCandidateId = null;
        double bestScore = currentScore;
        
        for (Map.Entry<String, PeerMetrics> entry : allMetrics.entrySet()) {
            String candidateId = entry.getKey();
            PeerMetrics candidateMetrics = entry.getValue();
            
            // Skip self and current parent
            if (candidateId.equals(localNode.getUserId()) || 
                candidateId.equals(currentParentId)) {
                continue;
            }
            
            // Skip if metrics are stale
            if (metrics.areMetricsStale(candidateId)) {
                continue;
            }
            
            // Calculate score
            double score = candidateMetrics.calculateScore();
            
            // Check if significantly better
            if (score > bestScore + MIN_SCORE_IMPROVEMENT) {
                bestCandidateId = candidateId;
                bestScore = score;
            }
        }
        
        if (bestCandidateId != null) {
            logger.info(String.format(
                "Found better parent: %s (score: %.3f) vs current %s (score: %.3f)",
                bestCandidateId, bestScore, currentParentId, currentScore
            ));
            return bestCandidateId;
        }
        
        logger.fine("Current parent is optimal");
        return null;
    }
    
    /**
     * Switch to a new parent
     * 
     * This needs to be coordinated with signaling server to:
     * 1. Request new parent connection
     * 2. Establish DataChannel to new parent
     * 3. Disconnect from old parent
     * 4. Update TreeNode state
     */
    private void switchParent(String newParentId) {
        TreeNode oldParent = localNode.getParent();
        String oldParentId = oldParent != null ? oldParent.getUserId() : "none";
        
        logger.info(String.format("Initiating parent switch: %s → %s", 
                                  oldParentId, newParentId));
        
        // Notify callback (this should trigger signaling)
        if (switchCallback != null) {
            switchCallback.onParentSwitch(oldParentId, newParentId);
        }
        
        // Note: Actual parent switch happens when new WebRTC connection is established
        // This is just the trigger/decision point
    }
    
    /**
     * Check if node is overloaded
     */
    private boolean isOverloaded() {
        // Check child count
        int childCount = localNode.getChildCount();
        if (childCount >= MAX_CHILDREN_THRESHOLD) {
            logger.fine("Node overloaded by child count: " + childCount);
            return true;
        }
        
        // Check CPU load
        Map<String, PeerMetrics> allMetrics = metrics.getAllMetrics();
        if (!allMetrics.isEmpty()) {
            PeerMetrics anyMetric = allMetrics.values().iterator().next();
            if (anyMetric.cpuLoad >= CPU_OVERLOAD_THRESHOLD) {
                logger.fine(String.format("Node overloaded by CPU: %.0f%%", 
                                         anyMetric.cpuLoad * 100));
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Handle ROOT node overload by promoting a RELAY to ROOT
     */
    private void handleRootOverload() {
        logger.warning("ROOT node overloaded - should promote RELAY to ROOT");
        
        // Find best RELAY candidate based on metrics
        String bestRelay = metrics.getAllMetrics().entrySet().stream()
            .filter(e -> !metrics.areMetricsStale(e.getKey()))
            .max((a, b) -> Double.compare(
                a.getValue().calculateScore(),
                b.getValue().calculateScore()
            ))
            .map(Map.Entry::getKey)
            .orElse(null);
        
        if (bestRelay != null) {
            logger.info("Candidate for ROOT promotion: " + bestRelay);
            // This would trigger signaling to promote the node
            // For now, just log it
        }
    }
    
    /**
     * Redistribute children to other nodes
     * 
     * Strategy:
     * 1. Sort children by metrics (worst connections first)
     * 2. Find alternative parents for them
     * 3. Signal server to reassign those children
     */
    private void redistributeChildren() {
        List<TreeNode> children = localNode.getChildren();
        
        if (children.isEmpty()) {
            return;
        }
        
        logger.info(String.format("Redistributing children (current count: %d)", 
                                  children.size()));
        
        // Sort children by connection quality (worst first)
        List<String> childIds = children.stream()
            .map(TreeNode::getUserId)
            .sorted((a, b) -> {
                PeerMetrics metricsA = metrics.getMetrics(a);
                PeerMetrics metricsB = metrics.getMetrics(b);
                
                double scoreA = metricsA != null ? metricsA.calculateScore() : 0.0;
                double scoreB = metricsB != null ? metricsB.calculateScore() : 0.0;
                
                return Double.compare(scoreA, scoreB); // Ascending (worst first)
            })
            .collect(Collectors.toList());
        
        // Try to reassign worst-performing children
        int toReassign = Math.min(2, children.size() / 2); // Reassign up to 2 or half
        
        for (int i = 0; i < toReassign; i++) {
            String childId = childIds.get(i);
            logger.info("Child marked for reassignment: " + childId);
            
            // This would trigger signaling to assign new parent for this child
            // For now, just log it
        }
    }
    
    /**
     * Force immediate rebalance check (for testing)
     */
    public void forceRebalance() {
        logger.info("Forced rebalance triggered");
        rebalanceTree();
    }
    
    /**
     * Set callback for parent switch events
     */
    public void setParentSwitchCallback(ParentSwitchCallback callback) {
        this.switchCallback = callback;
    }
    
    /**
     * Get rebalancing statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("running", running);
        stats.put("role", localNode.getRole().toString());
        stats.put("childCount", localNode.getChildCount());
        stats.put("isOverloaded", isOverloaded());
        stats.put("hasParent", localNode.getParent() != null);
        
        if (localNode.getParent() != null) {
            PeerMetrics parentMetrics = metrics.getMetrics(
                localNode.getParent().getUserId());
            if (parentMetrics != null) {
                stats.put("parentScore", parentMetrics.calculateScore());
                stats.put("parentRtt", parentMetrics.rttMs);
            }
        }
        
        return stats;
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        stop();
        logger.info("TreeBalancer cleanup complete");
    }
}
