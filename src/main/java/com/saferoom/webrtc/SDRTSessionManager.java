package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.media_engine.core.MediaSessionConfig;
import com.saferoom.webrtc.relay.SDRTMediaBridge;
import com.saferoom.webrtc.relay.SDRTMediaBridge.MediaComponents;
import com.saferoom.webrtc.relay.TreeNode;
import com.saferoom.crypto.GroupKeyManager;
import com.saferoom.webrtc.relay.ConnectionMetrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SDRT Session Manager (Client-Side)
 * Manages TreeNode lifecycle per room, coordinates join/leave, handles parent assignments
 */
public class SDRTSessionManager {
	
	// roomId -> SDRTSession
	private final Map<String, SDRTSession> activeSessions = new ConcurrentHashMap<>();
	
	// Shared group key manager
	private final GroupKeyManager groupKeyManager;
	
	// Metrics reporting scheduler
	private final ScheduledExecutorService metricsExecutor;
	
	// Singleton
	private static SDRTSessionManager instance;
	
	/**
	 * Inner class representing a single SDRT session (one room)
	 */
	public static class SDRTSession {
		public String roomId;
		public String localUserId;
		public TreeNode treeNode;
		public SDRTMediaBridge mediaBridge;
		public ConnectionMetrics connectionMetrics;
		public String parentUserId;
		public MediaSessionConfig mediaConfig;
		public MediaComponents mediaComponents;
		
		public SDRTSession(String roomId, String localUserId, String groupKey, GroupKeyManager keyManager) {
			this.roomId = roomId;
			this.localUserId = localUserId;
			
			// Initialize TreeNode (starts as LEAF until server assigns role)
			this.treeNode = new TreeNode(localUserId, roomId, TreeNode.NodeRole.LEAF);
			
			this.mediaConfig = MediaSessionConfig.builder().build();
			this.mediaComponents = MediaComponents.builder().build();
			this.mediaBridge = new SDRTMediaBridge(localUserId, roomId, treeNode, mediaConfig, mediaComponents);
			
			// Initialize metrics
			this.connectionMetrics = new ConnectionMetrics();
		}
		
		public void updateRole(TreeNode.NodeRole role) {
			// Role update happens via TreeNode methods
		}
		
		public void setParent(String parentId) {
			this.parentUserId = parentId;
			// Parent connection will be established via WebRTC DataChannel
		}
		
		public synchronized void configureMediaEngine(MediaSessionConfig config, MediaComponents components) {
			this.mediaConfig = config != null ? config : MediaSessionConfig.builder().build();
			this.mediaComponents = components != null ? components : MediaComponents.builder().build();
			
			if (mediaBridge != null) {
				mediaBridge.stop();
			}
			this.mediaBridge = new SDRTMediaBridge(localUserId, roomId, treeNode, this.mediaConfig, this.mediaComponents);
		}
		
		public void ensureMediaEngineStarted() {
			if (mediaBridge == null) {
				return;
			}
			try {
				mediaBridge.start();
			} catch (Exception e) {
				System.err.printf("[SDRT] Failed to start media session for room %s: %s%n",
						roomId, e.getMessage());
				e.printStackTrace();
			}
		}
		
		public void cleanup() {
			if (mediaBridge != null) {
				mediaBridge.stop();
			}
		}
	}
	
	/**
	 * Get singleton instance
	 */
	public static synchronized SDRTSessionManager getInstance() {
		if (instance == null) {
			instance = new SDRTSessionManager();
		}
		return instance;
	}
	
	private SDRTSessionManager() {
		this.groupKeyManager = new GroupKeyManager();
		this.metricsExecutor = Executors.newScheduledThreadPool(1);
		
		// Start periodic metrics reporting (every 5 seconds)
		metricsExecutor.scheduleAtFixedRate(this::reportAllMetrics, 5, 5, TimeUnit.SECONDS);
	}
	
	/**
	 * Join a room's SDRT tree
	 * @param roomId Room ID
	 * @param localUserId Local user ID
	 * @param signalSender Callback to send signals to server
	 * @return SDRTSession
	 */
	public SDRTSession joinRoom(String roomId, String localUserId, 
			java.util.function.Consumer<SafeRoomProto.WebRTCSignal> signalSender) {
		
		if (activeSessions.containsKey(roomId)) {
			System.out.printf("[SDRT] Already in room %s%n", roomId);
			return activeSessions.get(roomId);
		}
		
		System.out.printf("[SDRT] Joining room %s (user=%s)%n", roomId, localUserId);
		
		// Send TREE_JOIN to server
		SafeRoomProto.WebRTCSignal joinSignal = SafeRoomProto.WebRTCSignal.newBuilder()
			.setType(SafeRoomProto.WebRTCSignal.SignalType.TREE_JOIN)
			.setFrom(localUserId)
			.setTo("server")
			.setRoomId(roomId)
			.build();
		
		signalSender.accept(joinSignal);
		
		// Create placeholder session (will be updated when TREE_PARENT_ASSIGN arrives)
		SDRTSession session = new SDRTSession(roomId, localUserId, "temp-key", groupKeyManager);
		activeSessions.put(roomId, session);
		
		return session;
	}
	
	/**
	 * Handle TREE_PARENT_ASSIGN from server
	 */
	public void handleParentAssignment(String roomId, String parentUserId, String nodeRole, String groupKey) {
		SDRTSession session = activeSessions.get(roomId);
		if (session == null) {
 			System.err.printf("[SDRT] No session for room %s%n", roomId);
			return;
		}
		
		System.out.printf("[SDRT] Parent assigned: %s -> %s (role=%s)%n", 
			session.localUserId, parentUserId != null ? parentUserId : "ROOT", nodeRole);
		
		// Update role
		TreeNode.NodeRole role = TreeNode.NodeRole.valueOf(nodeRole);
		session.updateRole(role);
		
		// Update parent
		if (parentUserId != null && !parentUserId.isEmpty()) {
			session.setParent(parentUserId);
		}
		
		// Update group key
		groupKeyManager.importKeyForRoom(roomId, groupKey);
		
		session.ensureMediaEngineStarted();
	}
	
	/**
	 * Add a child to the tree (when another node connects to us)
	 */
	public void addChild(String roomId, String childUserId) {
		SDRTSession session = activeSessions.get(roomId);
		if (session == null) {
			System.err.printf("[SDRT] No session for room %s%n", roomId);
			return;
		}
		
		System.out.printf("[SDRT] Adding child: %s -> %s%n", session.localUserId, childUserId);
		// Children are added via TreeNode.addChild(TreeNode) when DataChannel is established
	}
	
	/**
	 * Remove a child from the tree
	 */
	public void removeChild(String roomId, String childUserId) {
		SDRTSession session = activeSessions.get(roomId);
		if (session == null) return;
		
		System.out.printf("[SDRT] Removing child: %s%n", childUserId);
		// Children are removed via TreeNode.removeChild(TreeNode) when DataChannel closes
	}
	
	/**
	 * Leave a room
	 */
	public void leaveRoom(String roomId, java.util.function.Consumer<SafeRoomProto.WebRTCSignal> signalSender) {
		SDRTSession session = activeSessions.remove(roomId);
		if (session == null) {
			System.out.printf("[SDRT] Not in room %s%n", roomId);
			return;
		}
		
		System.out.printf("[SDRT] Leaving room %s%n", roomId);
		
		// Send leave notification (using TREE_REBALANCE as placeholder)
		// TODO: Add TREE_LEAVE to proto
		SafeRoomProto.WebRTCSignal leaveSignal = SafeRoomProto.WebRTCSignal.newBuilder()
			.setType(SafeRoomProto.WebRTCSignal.SignalType.TREE_REBALANCE)
			.setFrom(session.localUserId)
			.setTo("server")
			.setRoomId(roomId)
			.build();
		
		signalSender.accept(leaveSignal);
		
		// Cleanup
		session.cleanup();
	}
	
	/**
	 * Get session for a room
	 */
	public SDRTSession getSession(String roomId) {
		return activeSessions.get(roomId);
	}
	
	/**
	 * Check if in a room
	 */
	public boolean isInRoom(String roomId) {
		return activeSessions.containsKey(roomId);
	}
	
	/**
	 * Get all active room IDs
	 */
	public java.util.Set<String> getActiveRooms() {
		return activeSessions.keySet();
	}
	
	/**
	 * Report metrics for all active sessions
	 */
	private void reportAllMetrics() {
		for (SDRTSession session : activeSessions.values()) {
			try {
				// Simple placeholder metrics (real metrics would come from PeerMetrics)
				double rtt = 100.0;
				double bandwidth = 2.5;
				double loss = 0.01;
				double cpu = 0.3;
				
				// Format as JSON
				String metricsJson = String.format(
					"{\"rtt\":%.2f,\"bandwidth\":%.2f,\"loss\":%.4f,\"cpu\":%.2f}",
					rtt, bandwidth, loss, cpu
				);
				
				System.out.printf("[SDRT] Metrics for room %s: %s%n", session.roomId, metricsJson);
				
				// TODO: Send via WebRTC signaling channel
				// signalSender.accept(SafeRoomProto.WebRTCSignal.newBuilder()
				// 	.setType(SafeRoomProto.WebRTCSignal.SignalType.TREE_METRICS)
				// 	.setFrom(session.localUserId)
				// 	.setTo("server")
				// 	.setRoomId(session.roomId)
				// 	.setMetricsData(metricsJson)
				// 	.build());
				
			} catch (Exception e) {
				System.err.printf("[SDRT] Error reporting metrics: %s%n", e.getMessage());
			}
		}
	}
	
	/**
	 * Shutdown manager
	 */
	public void shutdown() {
		metricsExecutor.shutdownNow();
		for (SDRTSession session : activeSessions.values()) {
			session.cleanup();
		}
		activeSessions.clear();
	}
}
