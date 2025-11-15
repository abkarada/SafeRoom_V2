package com.saferoom.webrtc.relay;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelState;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TreeNode - Represents a node in the SDRT (Serverless Distributed Relay Tree)
 * 
 * Each participant in a Room is represented by a TreeNode. The node's role determines
 * how it processes and forwards media packets.
 * 
 * Architecture:
 * - LEAF: End user, only consumes media (1 parent, 0 children)
 * - RELAY: Intermediate node, forwards media (1 parent, N children, typically 3-5)
 * - ROOT: Top-level node, source of tree (0 parents, N children)
 * 
 * Room Integration:
 * - Each Room ID has its own independent tree topology
 * - Nodes can participate in multiple rooms simultaneously
 * - Room ID is used for packet routing and parent assignment
 */
public class TreeNode {
    
    private static final Logger logger = Logger.getLogger(TreeNode.class.getName());
    
    /**
     * Node role in the tree hierarchy
     */
    public enum NodeRole {
        LEAF,    // End user node (consumer only)
        RELAY,   // Forwarding node (relays to children)
        ROOT     // Top-level source node (multi-root semi-mesh)
    }
    
    // Node identity
    private final String userId;           // Unique user identifier
    private final String roomId;           // Room this node belongs to
    private NodeRole role;                 // Current role in tree
    
    // Tree structure
    private volatile TreeNode parent;                           // Parent node (null for ROOT)
    private final List<TreeNode> children;                      // Child nodes
    private final Map<String, RTCDataChannel> childChannels;    // DataChannels to children
    private RTCDataChannel parentChannel;                       // DataChannel to parent
    
    // Connection management
    private final Map<String, TreeNode> peerNodes;              // All known nodes in room
    private final Set<String> rootNodes;                        // Current ROOT node IDs
    private volatile String preferredRootId;                    // Preferred ROOT for connection
    
    // Packet forwarding state
    private volatile boolean forwardingEnabled = true;
    private final Map<String, Integer> lastSequenceNumbers;     // Per-source sequence tracking
    private final Map<String, Long> lastPacketTime;             // For timeout detection
    
    // Configuration
    private static final int MAX_CHILDREN = 5;                  // Maximum children per RELAY
    private static final int PACKET_TIMEOUT_MS = 5000;          // Packet timeout threshold
    private static final int SEQUENCE_BUFFER_SIZE = 1000;       // Sequence number history
    
    /**
     * Constructor for creating a new TreeNode
     * 
     * @param userId Unique identifier for this user
     * @param roomId Room ID this node belongs to
     * @param initialRole Initial role (typically LEAF)
     */
    public TreeNode(String userId, String roomId, NodeRole initialRole) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (roomId == null || roomId.isEmpty()) {
            throw new IllegalArgumentException("Room ID cannot be null or empty");
        }
        
        this.userId = userId;
        this.roomId = roomId;
        this.role = initialRole;
        
        // Initialize collections (thread-safe)
        this.children = new CopyOnWriteArrayList<>();
        this.childChannels = new ConcurrentHashMap<>();
        this.peerNodes = new ConcurrentHashMap<>();
        this.rootNodes = ConcurrentHashMap.newKeySet();
        this.lastSequenceNumbers = new ConcurrentHashMap<>();
        this.lastPacketTime = new ConcurrentHashMap<>();
        
        logger.info(String.format("TreeNode created: userId=%s, roomId=%s, role=%s", 
                                  userId, roomId, initialRole));
    }
    
    /**
     * Set parent node and establish DataChannel connection
     * 
     * @param parent Parent TreeNode
     * @param channel DataChannel to parent
     */
    public synchronized void setParent(TreeNode parent, RTCDataChannel channel) {
        if (this.role == NodeRole.ROOT) {
            logger.warning("Cannot set parent for ROOT node");
            return;
        }
        
        // Disconnect old parent if exists
        if (this.parent != null) {
            this.parent.removeChild(this);
            if (this.parentChannel != null) {
                closeDataChannel(this.parentChannel);
            }
        }
        
        this.parent = parent;
        this.parentChannel = channel;
        
        if (parent != null) {
            parent.addChild(this);
            logger.info(String.format("Parent set: %s → %s", userId, parent.getUserId()));
        }
    }
    
    /**
     * Add a child node
     * 
     * @param child Child TreeNode
     * @return true if added successfully, false if max children reached
     */
    public synchronized boolean addChild(TreeNode child) {
        if (this.role == NodeRole.LEAF) {
            logger.warning("LEAF nodes cannot have children");
            return false;
        }
        
        if (children.size() >= MAX_CHILDREN) {
            logger.warning(String.format("Max children (%d) reached for node %s", 
                                        MAX_CHILDREN, userId));
            return false;
        }
        
        if (!children.contains(child)) {
            children.add(child);
            logger.info(String.format("Child added: %s → %s (total children: %d)", 
                                     userId, child.getUserId(), children.size()));
            return true;
        }
        
        return false;
    }
    
    /**
     * Remove a child node
     */
    public synchronized void removeChild(TreeNode child) {
        if (children.remove(child)) {
            RTCDataChannel channel = childChannels.remove(child.getUserId());
            if (channel != null) {
                closeDataChannel(channel);
            }
            logger.info(String.format("Child removed: %s → %s", userId, child.getUserId()));
        }
    }
    
    /**
     * Register DataChannel for a child
     */
    public void registerChildChannel(String childUserId, RTCDataChannel channel) {
        childChannels.put(childUserId, channel);
        logger.fine(String.format("Child channel registered: %s → %s", userId, childUserId));
    }
    
    /**
     * Process incoming MediaPacket (main forwarding logic)
     * 
     * This is the core of SDRT - packets are forwarded WITHOUT decode/re-encode
     * 
     * @param packet Received MediaPacket
     * @param fromUserId User ID of sender
     */
    public void processPacket(MediaPacket packet, String fromUserId) {
        if (!forwardingEnabled) {
            return;
        }
        
        // Validate packet belongs to this room
        if (!roomId.equals(packet.getRoomId())) {
            logger.warning(String.format("Packet from wrong room: expected=%s, got=%s", 
                                        roomId, packet.getRoomId()));
            return;
        }
        
        // Update packet tracking
        String sourceId = packet.getSourceId();
        lastPacketTime.put(sourceId, System.currentTimeMillis());
        
        // Check for duplicate/out-of-order packets
        if (isDuplicatePacket(packet)) {
            logger.fine(String.format("Duplicate packet dropped: source=%s, seq=%d", 
                                     sourceId, packet.getSequenceNumber()));
            return;
        }
        
        // Update sequence tracking
        lastSequenceNumbers.put(sourceId, packet.getSequenceNumber());
        
        // Role-specific processing
        switch (role) {
            case LEAF:
                // LEAF: Just consume (decrypt & decode happens in WebRTCClient)
                logger.fine(String.format("LEAF received packet: %s", packet));
                break;
                
            case RELAY:
                // RELAY: Forward to all children EXCEPT the sender
                forwardToChildren(packet, fromUserId);
                logger.fine(String.format("RELAY forwarded packet: %s to %d children", 
                                         packet, children.size()));
                break;
                
            case ROOT:
                // ROOT: Broadcast to all children (ROOT doesn't have parent)
                forwardToChildren(packet, null);
                logger.fine(String.format("ROOT broadcast packet: %s", packet));
                break;
        }
    }
    
    /**
     * Forward packet to all children (except sender)
     * 
     * This is ZERO-COPY forwarding - no decode/re-encode!
     */
    private void forwardToChildren(MediaPacket packet, String excludeUserId) {
        if (children.isEmpty()) {
            return;
        }
        
        // Serialize packet once (reuse for all children)
        byte[] packetBytes = packet.toBytes();
        ByteBuffer buffer = ByteBuffer.wrap(packetBytes);
        
        // Send to all children except the source
        for (TreeNode child : children) {
            if (child.getUserId().equals(excludeUserId)) {
                continue; // Don't send back to sender
            }
            
            RTCDataChannel channel = childChannels.get(child.getUserId());
            if (channel != null && channel.getState() == RTCDataChannelState.OPEN) {
                try {
                    // Create new buffer for each send (DataChannel may retain reference)
                    RTCDataChannelBuffer dcBuffer = new RTCDataChannelBuffer(
                        ByteBuffer.wrap(packetBytes), true);
                    channel.send(dcBuffer);
                    
                } catch (Exception e) {
                    logger.log(Level.WARNING, 
                              String.format("Failed to forward to child %s: %s", 
                                          child.getUserId(), e.getMessage()), e);
                }
            }
        }
    }
    
    /**
     * Send packet to parent (upstream)
     */
    public void sendToParent(MediaPacket packet) {
        if (parent == null || parentChannel == null) {
            logger.warning("Cannot send to parent: no parent connection");
            return;
        }
        
        if (parentChannel.getState() != RTCDataChannelState.OPEN) {
            logger.warning("Parent channel not open: " + parentChannel.getState());
            return;
        }
        
        try {
            byte[] packetBytes = packet.toBytes();
            RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(
                ByteBuffer.wrap(packetBytes), true);
            parentChannel.send(buffer);
            
            logger.fine(String.format("Sent packet to parent: %s → %s", 
                                     userId, parent.getUserId()));
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send to parent: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if packet is duplicate based on sequence number
     */
    private boolean isDuplicatePacket(MediaPacket packet) {
        String sourceId = packet.getSourceId();
        Integer lastSeq = lastSequenceNumbers.get(sourceId);
        
        if (lastSeq == null) {
            return false; // First packet from this source
        }
        
        int currentSeq = packet.getSequenceNumber();
        
        // Allow for sequence number wraparound
        int diff = currentSeq - lastSeq;
        if (diff < 0) {
            diff += 65536; // Assuming 16-bit sequence numbers
        }
        
        // Packet is duplicate if sequence is not newer
        return diff == 0 || diff > SEQUENCE_BUFFER_SIZE;
    }
    
    /**
     * Promote this node to a higher role
     */
    public synchronized void promoteRole(NodeRole newRole) {
        if (newRole.ordinal() <= role.ordinal()) {
            logger.warning(String.format("Cannot promote to same or lower role: %s → %s", 
                                        role, newRole));
            return;
        }
        
        NodeRole oldRole = this.role;
        this.role = newRole;
        
        logger.info(String.format("Node promoted: userId=%s, %s → %s", 
                                 userId, oldRole, newRole));
        
        // If promoted to ROOT, disconnect parent
        if (newRole == NodeRole.ROOT && parent != null) {
            setParent(null, null);
        }
    }
    
    /**
     * Demote this node to a lower role
     */
    public synchronized void demoteRole(NodeRole newRole) {
        if (newRole.ordinal() >= role.ordinal()) {
            logger.warning(String.format("Cannot demote to same or higher role: %s → %s", 
                                        role, newRole));
            return;
        }
        
        NodeRole oldRole = this.role;
        this.role = newRole;
        
        logger.info(String.format("Node demoted: userId=%s, %s → %s", 
                                 userId, oldRole, newRole));
    }
    
    /**
     * Check if node can accept more children
     */
    public boolean canAcceptChildren() {
        return role != NodeRole.LEAF && children.size() < MAX_CHILDREN;
    }
    
    /**
     * Get current child count
     */
    public int getChildCount() {
        return children.size();
    }
    
    /**
     * Check if node is overloaded
     */
    public boolean isOverloaded() {
        return children.size() >= MAX_CHILDREN;
    }
    
    /**
     * Clean up node resources
     */
    public synchronized void cleanup() {
        logger.info(String.format("Cleaning up TreeNode: %s", userId));
        
        forwardingEnabled = false;
        
        // Disconnect parent
        if (parentChannel != null) {
            closeDataChannel(parentChannel);
            parentChannel = null;
        }
        
        // Disconnect all children
        for (RTCDataChannel channel : childChannels.values()) {
            closeDataChannel(channel);
        }
        childChannels.clear();
        children.clear();
        
        // Clear tracking data
        lastSequenceNumbers.clear();
        lastPacketTime.clear();
        peerNodes.clear();
        rootNodes.clear();
    }
    
    /**
     * Safely close a DataChannel
     */
    private void closeDataChannel(RTCDataChannel channel) {
        try {
            if (channel != null && channel.getState() != RTCDataChannelState.CLOSED) {
                channel.close();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing DataChannel: " + e.getMessage(), e);
        }
    }
    
    // Getters
    public String getUserId() {
        return userId;
    }
    
    public String getRoomId() {
        return roomId;
    }
    
    public NodeRole getRole() {
        return role;
    }
    
    public TreeNode getParent() {
        return parent;
    }
    
    public List<TreeNode> getChildren() {
        return new ArrayList<>(children);
    }
    
    public RTCDataChannel getParentChannel() {
        return parentChannel;
    }
    
    public Map<String, RTCDataChannel> getChildChannels() {
        return new HashMap<>(childChannels);
    }
    
    public boolean isForwardingEnabled() {
        return forwardingEnabled;
    }
    
    public void setForwardingEnabled(boolean enabled) {
        this.forwardingEnabled = enabled;
    }
    
    @Override
    public String toString() {
        return String.format("TreeNode{userId=%s, roomId=%s, role=%s, parent=%s, children=%d}",
                           userId, roomId, role, 
                           parent != null ? parent.getUserId() : "null", 
                           children.size());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TreeNode)) return false;
        TreeNode other = (TreeNode) obj;
        return userId.equals(other.userId) && roomId.equals(other.roomId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(userId, roomId);
    }
}
