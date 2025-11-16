package com.saferoom.server;

import com.saferoom.webrtc.relay.TreeNode;
import com.saferoom.webrtc.relay.TreeNode.NodeRole;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * SDRTCoordinator - Server-side coordinator for SDRT (Serverless Distributed Relay Tree)
 * 
 * Responsibilities:
 * 1. Maintain tree topology per Room ID
 * 2. Assign parents for new joiners based on metrics
 * 3. Select ROOT nodes (best performing participants)
 * 4. Handle role promotions/demotions
 * 5. Coordinate tree rebalancing
 * 
 * KEY: Each Room ID has its own independent tree topology!
 */
public class SDRTCoordinator {
    
    private static final Logger logger = Logger.getLogger(SDRTCoordinator.class.getName());
    
    // Room ID → Tree topology mapping
    private final Map<String, RoomTree> roomTrees = new ConcurrentHashMap<>();
    
    // User ID → Active rooms mapping (for multi-room participation)
    private final Map<String, Set<String>> userRooms = new ConcurrentHashMap<>();
    
    // Configuration
    private static final int MAX_CHILDREN_PER_NODE = 5;
    private static final int MIN_NODES_FOR_RELAY = 4;  // When to start using RELAYs
    private static final int MIN_NODES_FOR_ROOT = 8;   // When to promote to ROOT
    
    /**
     * RoomTree - Represents the tree topology for a specific room
     */
    private static class RoomTree {
        private final String roomId;
        private final Map<String, TreeNode> nodes = new ConcurrentHashMap<>();
        private final Set<String> rootNodeIds = ConcurrentHashMap.newKeySet();
        private final Map<String, NodeMetrics> metricsCache = new ConcurrentHashMap<>();
        
        public RoomTree(String roomId) {
            this.roomId = roomId;
        }
        
        public void addNode(TreeNode node) {
            nodes.put(node.getUserId(), node);
            logger.info(String.format("[Room %s] Node added: %s (role=%s)", 
                                     roomId, node.getUserId(), node.getRole()));
        }
        
        public void removeNode(String userId) {
            TreeNode node = nodes.remove(userId);
            if (node != null) {
                rootNodeIds.remove(userId);
                metricsCache.remove(userId);
                logger.info(String.format("[Room %s] Node removed: %s", roomId, userId));
            }
        }
        
        public TreeNode getNode(String userId) {
            return nodes.get(userId);
        }
        
        public Collection<TreeNode> getAllNodes() {
            return nodes.values();
        }
        
        public int getNodeCount() {
            return nodes.size();
        }
        
        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }
    
    /**
     * NodeMetrics - Cached metrics for a node
     */
    private static class NodeMetrics {
        double rtt;           // Round-trip time (ms)
        double bandwidth;     // Upload bandwidth (Mbps)
        double packetLoss;    // Packet loss rate (0-1)
        double cpuLoad;       // CPU load (0-1)
        long lastUpdate;      // Timestamp of last update
        
        // Calculate overall score (0-1, higher is better)
        public double calculateScore() {
            double rttScore = Math.max(0, 1.0 - (rtt / 200.0));        // 200ms threshold
            double bwScore = Math.min(1.0, bandwidth / 10.0);          // 10Mbps optimal
            double lossScore = Math.max(0, 1.0 - (packetLoss * 10));   // Loss penalty
            double cpuScore = Math.max(0, 1.0 - cpuLoad);              // CPU usage
            
            // Weighted average (RTT most important)
            return (rttScore * 0.4) + (bwScore * 0.3) + (lossScore * 0.2) + (cpuScore * 0.1);
        }
    }
    
    /**
     * User joins a room and requests to join the tree
     * 
     * IMPORTANT: This is a single-server implementation!
     * For multi-server deployments, you need:
     * 1. Distributed state store (Redis, etcd, Consul)
     * 2. Distributed locking (to prevent split-brain)
     * 3. Consensus algorithm for ROOT election (Raft, Paxos)
     * 
     * Without these, multiple servers can create conflicting ROOT nodes!
     * 
     * @param roomId Room ID to join
     * @param userId User ID of joiner
     * @return Assigned parent user ID (null if this user should be ROOT)
     */
    public synchronized String handleTreeJoin(String roomId, String userId) {
        logger.info(String.format("Tree join request: user=%s, room=%s", userId, roomId));
        
        // TODO: For production multi-server:
        // 1. Acquire distributed lock for this roomId
        // 2. Read tree state from shared store (Redis)
        // 3. Make assignment decision
        // 4. Write updated state to shared store
        // 5. Release distributed lock
        
        // Get or create room tree
        RoomTree tree = roomTrees.computeIfAbsent(roomId, RoomTree::new);
        
        // Track user → room mapping
        userRooms.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(roomId);
        
        // Create node (initially LEAF)
        TreeNode newNode = new TreeNode(userId, roomId, NodeRole.LEAF);
        tree.addNode(newNode);
        
        // Determine parent assignment based on tree size
        if (tree.getNodeCount() == 1) {
            // First user → ROOT
            // CAUTION: In multi-server, this can create split-brain!
            newNode.promoteRole(NodeRole.ROOT);
            tree.rootNodeIds.add(userId);
            logger.info(String.format("[Room %s] %s promoted to ROOT (first joiner)", roomId, userId));
            return null; // No parent
            
        } else if (tree.getNodeCount() < MIN_NODES_FOR_RELAY) {
            // Small room → direct connection to ROOT
            String rootId = tree.rootNodeIds.iterator().next();
            logger.info(String.format("[Room %s] %s assigned to ROOT %s (small room)", 
                                     roomId, userId, rootId));
            return rootId;
            
        } else {
            // Large room → find best parent (RELAY or ROOT)
            String parentId = findBestParent(tree);
            
            logger.info(String.format("[Room %s] %s assigned to parent %s", 
                                     roomId, userId, parentId));
            
            // Check if we need to promote someone to RELAY
            checkAndPromoteRelays(tree);
            
            return parentId;
        }
    }
    
    /**
     * Find the best parent for a new joiner
     * 
     * Criteria (in order of priority):
     * 1. Has available child slots
     * 2. Best performance metrics (score)
     * 3. Prefer RELAY over ROOT (balance load)
     */
    private String findBestParent(RoomTree tree) {
        List<TreeNode> candidates = tree.getAllNodes().stream()
            .filter(node -> node.getRole() != NodeRole.LEAF)  // Only RELAY/ROOT can be parents
            .filter(TreeNode::canAcceptChildren)              // Has space for children
            .collect(Collectors.toList());
        
        if (candidates.isEmpty()) {
            // No available parents → create new ROOT
            return promoteNewRoot(tree);
        }
        
        // Sort by score (best first)
        candidates.sort((a, b) -> {
            NodeMetrics metricsA = tree.metricsCache.get(a.getUserId());
            NodeMetrics metricsB = tree.metricsCache.get(b.getUserId());
            
            double scoreA = metricsA != null ? metricsA.calculateScore() : 0.5;
            double scoreB = metricsB != null ? metricsB.calculateScore() : 0.5;
            
            // Prefer RELAY over ROOT (balance)
            if (a.getRole() == NodeRole.RELAY && b.getRole() == NodeRole.ROOT) {
                scoreA += 0.1;
            } else if (a.getRole() == NodeRole.ROOT && b.getRole() == NodeRole.RELAY) {
                scoreB += 0.1;
            }
            
            return Double.compare(scoreB, scoreA);
        });
        
        return candidates.get(0).getUserId();
    }
    
    /**
     * Promote a new ROOT node (when current ROOTs are full)
     */
    private String promoteNewRoot(RoomTree tree) {
        // Find best RELAY candidate based on metrics
        Optional<TreeNode> bestRelay = tree.getAllNodes().stream()
            .filter(node -> node.getRole() == NodeRole.RELAY)
            .max((a, b) -> {
                NodeMetrics metricsA = tree.metricsCache.get(a.getUserId());
                NodeMetrics metricsB = tree.metricsCache.get(b.getUserId());
                
                double scoreA = metricsA != null ? metricsA.calculateScore() : 0.5;
                double scoreB = metricsB != null ? metricsB.calculateScore() : 0.5;
                
                return Double.compare(scoreA, scoreB);
            });
        
        if (bestRelay.isPresent()) {
            TreeNode node = bestRelay.get();
            node.promoteRole(NodeRole.ROOT);
            tree.rootNodeIds.add(node.getUserId());
            logger.info(String.format("[Room %s] %s promoted to ROOT (capacity)", 
                                     tree.roomId, node.getUserId()));
            return node.getUserId();
        }
        
        // Fallback: use existing ROOT (will be overloaded temporarily)
        return tree.rootNodeIds.iterator().next();
    }
    
    /**
     * Check if any nodes should be promoted to RELAY role
     */
    private void checkAndPromoteRelays(RoomTree tree) {
        int nodeCount = tree.getNodeCount();
        
        // Calculate ideal RELAY count (roughly nodeCount / 5)
        int idealRelayCount = Math.max(1, nodeCount / MAX_CHILDREN_PER_NODE);
        int currentRelayCount = (int) tree.getAllNodes().stream()
            .filter(node -> node.getRole() == NodeRole.RELAY)
            .count();
        
        if (currentRelayCount < idealRelayCount) {
            // Find best LEAF candidates for promotion
            List<TreeNode> leafCandidates = tree.getAllNodes().stream()
                .filter(node -> node.getRole() == NodeRole.LEAF)
                .sorted((a, b) -> {
                    NodeMetrics metricsA = tree.metricsCache.get(a.getUserId());
                    NodeMetrics metricsB = tree.metricsCache.get(b.getUserId());
                    
                    double scoreA = metricsA != null ? metricsA.calculateScore() : 0.5;
                    double scoreB = metricsB != null ? metricsB.calculateScore() : 0.5;
                    
                    return Double.compare(scoreB, scoreA);
                })
                .limit(idealRelayCount - currentRelayCount)
                .collect(Collectors.toList());
            
            for (TreeNode node : leafCandidates) {
                node.promoteRole(NodeRole.RELAY);
                logger.info(String.format("[Room %s] %s promoted to RELAY", 
                                         tree.roomId, node.getUserId()));
            }
        }
    }
    
    /**
     * Update metrics for a node
     */
    public void updateMetrics(String roomId, String userId, double rtt, double bandwidth, 
                              double packetLoss, double cpuLoad) {
        RoomTree tree = roomTrees.get(roomId);
        if (tree == null) {
            logger.warning(String.format("Unknown room: %s", roomId));
            return;
        }
        
        NodeMetrics metrics = tree.metricsCache.computeIfAbsent(userId, k -> new NodeMetrics());
        metrics.rtt = rtt;
        metrics.bandwidth = bandwidth;
        metrics.packetLoss = packetLoss;
        metrics.cpuLoad = cpuLoad;
        metrics.lastUpdate = System.currentTimeMillis();
        
        logger.fine(String.format("[Room %s] Metrics updated: user=%s, score=%.2f", 
                                 roomId, userId, metrics.calculateScore()));
    }
    
    /**
     * Handle user leaving a room
     */
    public synchronized void handleTreeLeave(String roomId, String userId) {
        RoomTree tree = roomTrees.get(roomId);
        if (tree == null) {
            return;
        }
        
        TreeNode node = tree.getNode(userId);
        if (node == null) {
            return;
        }
        
        logger.info(String.format("[Room %s] User leaving: %s (role=%s, children=%d)", 
                                 roomId, userId, node.getRole(), node.getChildCount()));
        
        // Reassign children to other parents
        if (node.getRole() != NodeRole.LEAF) {
            reassignChildren(tree, node);
        }
        
        // Remove node
        tree.removeNode(userId);
        
        // Update user → room mapping
        Set<String> rooms = userRooms.get(userId);
        if (rooms != null) {
            rooms.remove(roomId);
            if (rooms.isEmpty()) {
                userRooms.remove(userId);
            }
        }
        
        // Clean up empty room
        if (tree.isEmpty()) {
            roomTrees.remove(roomId);
            logger.info(String.format("[Room %s] Tree removed (empty)", roomId));
        }
    }
    
    /**
     * Reassign children when a parent leaves
     */
    private void reassignChildren(RoomTree tree, TreeNode departingNode) {
        List<TreeNode> children = departingNode.getChildren();
        if (children.isEmpty()) {
            return;
        }
        
        logger.info(String.format("[Room %s] Reassigning %d children from %s", 
                                 tree.roomId, children.size(), departingNode.getUserId()));
        
        for (TreeNode child : children) {
            String newParentId = findBestParent(tree);
            TreeNode newParent = tree.getNode(newParentId);
            
            if (newParent != null) {
                // This will be handled by client via signaling
                logger.info(String.format("[Room %s] Child %s reassigned: %s → %s", 
                                         tree.roomId, child.getUserId(), 
                                         departingNode.getUserId(), newParentId));
            }
        }
    }
    
    /**
     * Get current tree topology for a room (for monitoring/debugging)
     */
    public Map<String, Object> getTreeTopology(String roomId) {
        RoomTree tree = roomTrees.get(roomId);
        if (tree == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Object> topology = new HashMap<>();
        topology.put("roomId", roomId);
        topology.put("nodeCount", tree.getNodeCount());
        topology.put("rootCount", tree.rootNodeIds.size());
        
        List<Map<String, Object>> nodes = tree.getAllNodes().stream()
            .map(node -> {
                Map<String, Object> nodeInfo = new HashMap<>();
                nodeInfo.put("userId", node.getUserId());
                nodeInfo.put("role", node.getRole().toString());
                nodeInfo.put("childCount", node.getChildCount());
                nodeInfo.put("parentId", node.getParent() != null ? 
                                         node.getParent().getUserId() : null);
                return nodeInfo;
            })
            .collect(Collectors.toList());
        
        topology.put("nodes", nodes);
        return topology;
    }
    
    /**
     * Get active rooms
     */
    public Set<String> getActiveRooms() {
        return new HashSet<>(roomTrees.keySet());
    }
    
    /**
     * Get rooms for a specific user
     */
    public Set<String> getUserRooms(String userId) {
        Set<String> rooms = userRooms.get(userId);
        return rooms != null ? new HashSet<>(rooms) : Collections.emptySet();
    }
}
