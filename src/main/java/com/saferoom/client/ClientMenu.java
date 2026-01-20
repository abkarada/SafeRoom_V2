package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.UDPHoleGrpc;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.server.SafeRoomServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;

public class ClientMenu {
	public static String Server = SafeRoomServer.ServerIP;
	public static int Port = SafeRoomServer.grpcPort;

	public static ManagedChannel channel;
	private static UDPHoleGrpc.UDPHoleBlockingStub blockingStub;

	// Rooms v1 Signaling
	public static com.saferoom.rooms.client.RoomSignalingClient roomClient;

	static {
		channel = ManagedChannelBuilder.forAddress(Server, Port)
				.usePlaintext()
				.keepAliveTime(20, TimeUnit.SECONDS)
				.keepAliveTimeout(10, TimeUnit.SECONDS)
				.keepAliveWithoutCalls(true)
				.idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS)
				.maxInboundMessageSize(10 * 1024 * 1024)
				.enableRetry()
				.maxRetryAttempts(5)
				.build();

		// Cache the blocking stub to avoid repeated allocation
		blockingStub = UDPHoleGrpc.newBlockingStub(channel);

		// Initialize Rooms Client
		roomClient = new com.saferoom.rooms.client.RoomSignalingClient();
		// Connection will be established when needed or via initRoomsV1
	}

	public static void initRoomsV1() {
		try {
			if (roomClient != null) {
				System.out.println("[Rooms] Connecting to Signaling V1...");
				roomClient.connect(Server, Port);
			}
		} catch (Exception e) {
			System.err.println("[Rooms] Failed to init: " + e.getMessage());
		}
	}

	public static String Login(String username, String Password) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub client = blockingStub
					.withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
			SafeRoomProto.Menu main_menu = SafeRoomProto.Menu.newBuilder()
					.setUsername(username)
					.setHashPassword(Password)
					.build();
			SafeRoomProto.Status stats = client.menuAns(main_menu);

			String message = stats.getMessage();
			int code = stats.getCode();
			switch (code) {
				case 0:
					System.out.println("Success!");
					System.out.printf("Logged in as: %s%n", username);
					return message; // Server'dan gelen eksik bilgiyi döndür (email veya username)
				case 1:
					if (message.equals("N_REGISTER")) {
						System.out.println("Not Registered");
						return "N_REGISTER";
					} else if (message.equals("WRONG_PASSWORD")) {
						System.out.println("Wrong Password");
						return "WRONG_PASSWORD";
					} else {
						System.out.println("Blocked User");
						return "BLOCKED_USER";
					}
				default:
					System.out.println("Message has broken");
					return "ERROR";
			}
		} catch (io.grpc.StatusRuntimeException e) {
			// gRPC specific errors
			System.err.println("gRPC Login Error: " + e.getStatus().getCode() + " - " + e.getMessage());
			if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
				return "ERROR_SERVER_UNAVAILABLE";
			} else if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
				return "ERROR_TIMEOUT";
			} else if (e.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
				return "ERROR_CONNECTION_FAILED";
			} else {
				return "ERROR";
			}
		} catch (Exception e) {
			// Generic errors
			System.err.println("Login Error: " + e.getMessage());
			e.printStackTrace();
			return "ERROR";
		}
	}

	public static int register_client(String username, String password, String mail) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub stub = blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.Create_User insert_obj = SafeRoomProto.Create_User.newBuilder()
					.setUsername(username)
					.setEmail(mail)
					.setPassword(password)
					.setIsVerified(false)
					.build();
			SafeRoomProto.Status stat = stub.insertUser(insert_obj);

			int code = stat.getCode();
			String message = stat.getMessage();

			switch (code) {
				case 0:
					System.out.println("Success!");
					return 0;
				case 2:
					if (message.equals("VUSERNAME")) {
						System.out.println("Username already taken");
						return 1;
					} else {
						System.out.println("Invalid E-mail");
						return 2;
					}
				default:
					System.out.println("Message has broken");
					return 3;
			}
		} catch (Exception e) {
			System.err.println("Register Error: " + e);
			return -5;

		}
	}

	public static int verify_user(String username, String verify_code) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub stub = blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			Verification verification_info = Verification.newBuilder()
					.setUsername(username)
					.setVerify(verify_code)
					.build();

			SafeRoomProto.Status response = stub.verifyUser(verification_info);

			int code = response.getCode();

			switch (code) {
				case 0:
					System.out.println("Verification Completed");
					return 0;
				case 1:
					System.out.println("Not Matched");
					return 1;

				default:
					System.out.println("Connection is not safe");
					return 2;
			}
		} catch (Exception e) {
			System.err.println("Verificate Client Error: " + e);
			return -7;
		}
	}

	public static boolean verify_email(String mail) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub client = blockingStub
					.withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);

			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
					.setUsername(mail)
					.build();

			SafeRoomProto.Status status = client.verifyEmail(request);

			int code = status.getCode();

			if (code == 1) {
				return true;
			}

		} catch (Exception e) {
			System.err.println("Verify Channel Error: " + e);
		}
		return false;
	}

	public static int changePassword(String email, String newPassword) {
		try {

			UDPHoleGrpc.UDPHoleBlockingStub stub = blockingStub;
			// Format: "email:newpassword"
			String requestData = email + ":" + newPassword;
			Request_Client request = Request_Client.newBuilder()
					.setUsername(requestData)
					.build();

			Status response = stub.changePassword(request);
			int code = response.getCode();
			String message = response.getMessage();

			System.out.println("Change Password Response: " + message + " (Code: " + code + ")");

			return code;

		} catch (Exception e) {
			System.err.println("Change Password Channel Error: " + e);
			return 2; // Error code
		}
	}

	public static java.util.List<java.util.Map<String, Object>> searchUsers(String searchTerm, String currentUser)
			throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.SearchRequest request = SafeRoomProto.SearchRequest.newBuilder()
					.setSearchTerm(searchTerm)
					.setCurrentUser(currentUser)
					.build();

			SafeRoomProto.SearchResponse response = blockingStub.searchUsers(request);

			java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
			if (response.getSuccess()) {
				for (SafeRoomProto.UserResult user : response.getUsersList()) {
					java.util.Map<String, Object> userMap = new java.util.HashMap<>();
					userMap.put("username", user.getUsername());
					userMap.put("email", user.getEmail());
					userMap.put("isOnline", user.getIsOnline());
					userMap.put("lastSeen", user.getLastSeen());
					userMap.put("is_friend", user.getIsFriend());
					userMap.put("has_pending_request", user.getHasPendingRequest());

					// Debug log
					System.out.println("Search Result for " + user.getUsername() + ":");
					System.out.println("  - is_friend: " + user.getIsFriend());
					System.out.println("  - has_pending_request: " + user.getHasPendingRequest());

					results.add(userMap);
				}
			}
			return results;
		} catch (Exception e) {
			System.err.println("Search User Client Error: " + e);
			return null;
		}
	}

	public static SafeRoomProto.ProfileResponse getProfile(String targetUsername, String currentUser) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.ProfileRequest request = SafeRoomProto.ProfileRequest.newBuilder()
					.setUsername(targetUsername)
					.setRequestedBy(currentUser)
					.build();

			SafeRoomProto.ProfileResponse response = blockingStub.getProfile(request);
			return response;
		} catch (Exception e) {
			System.err.println("Get Profile Error: " + e);
			return null;
		}
	}

	public static SafeRoomProto.FriendResponse sendFriendRequest(String fromUser, String toUser) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.FriendRequest request = SafeRoomProto.FriendRequest.newBuilder()
					.setSender(fromUser)
					.setReceiver(toUser)
					.setMessage("") // Boş mesaj
					.build();

			SafeRoomProto.FriendResponse response = blockingStub.sendFriendRequest(request);
			return response;
		} catch (Exception e) {
			System.err.println("Send Friend Request Error: " + e);
			return null;
		}
	}

	// ===============================
	// FRIEND SYSTEM CLIENT METHODS
	// ===============================

	/**
	 * Bekleyen arkadaşlık isteklerini getir (gelen istekler)
	 */
	public static SafeRoomProto.PendingRequestsResponse getPendingFriendRequests(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
					.setUsername(username)
					.build();

			return blockingStub.getPendingFriendRequests(request);
		} catch (Exception e) {
			System.err.println("Get Pending Request Error: " + e);
			return null;
		}
	}

	/**
	 * Gönderilen arkadaşlık isteklerini getir (giden istekler)
	 */
	public static SafeRoomProto.SentRequestsResponse getSentFriendRequests(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
					.setUsername(username)
					.build();

			return blockingStub.getSentFriendRequests(request);
		} catch (Exception e) {
			System.err.println("Get Friend Request Error: " + e);
			return null;
		}
	}

	/**
	 * Arkadaşlık isteğini kabul et
	 */
	public static SafeRoomProto.Status acceptFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
					.setRequestId(requestId)
					.setUsername(username)
					.build();

			return blockingStub.acceptFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Accept Friend Error: " + e);
			return null;
		}
	}

	/**
	 * Arkadaşlık isteğini reddet
	 */
	public static SafeRoomProto.Status rejectFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
					.setRequestId(requestId)
					.setUsername(username)
					.build();

			return blockingStub.rejectFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Reject Frind Client Error: " + e);
			return null;
		}
	}

	/**
	 * Gönderilen arkadaşlık isteğini iptal et
	 */
	public static SafeRoomProto.Status cancelFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
					.setRequestId(requestId)
					.setUsername(username)
					.build();

			return blockingStub.cancelFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Cancel Frind Client Error: " + e);
			return null;
		}
	}

	/**
	 * Arkadaş listesini getir
	 */
	public static SafeRoomProto.FriendsListResponse getFriendsList(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
					.setUsername(username)
					.build();

			return blockingStub.getFriendsList(request);
		} catch (Exception e) {
			System.err.println("Get Friend List: " + e);
			return null;
		}
	}

	/**
	 * Arkadaşı kaldır
	 */
	public static SafeRoomProto.Status removeFriend(String user1, String user2) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.RemoveFriendRequest request = SafeRoomProto.RemoveFriendRequest.newBuilder()
					.setUser1(user1)
					.setUser2(user2)
					.build();

			return blockingStub.removeFriend(request);
		} catch (Exception e) {
			System.err.println("Remove Friend Client Error: " + e);
			return null;
		}
	}

	/**
	 * Arkadaşlık istatistiklerini getir
	 */
	public static SafeRoomProto.FriendshipStatsResponse getFriendshipStats(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(10, TimeUnit.SECONDS);

			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
					.setUsername(username)
					.build();

			return blockingStub.getFriendshipStats(request);
		} catch (Exception e) {
			System.err.println("GetFriendShipStats Client Error: " + e);
			return null;
		}
	}

	/**
	 * Heartbeat gönder
	 */
	public static SafeRoomProto.HeartbeatResponse sendHeartbeat(String username, String sessionId) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(5, TimeUnit.SECONDS);

			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
					.setUsername(username)
					.setSessionId(sessionId)
					.build();

			return blockingStub.sendHeartbeat(request);
		} catch (Exception e) {
			System.err.println("Heartbeat Error Client: " + e);
			return null;
		}
	}

	/**
	 * User session'ını sonlandır
	 */
	public static SafeRoomProto.Status endUserSession(String username, String sessionId) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = ClientMenu.blockingStub
					.withDeadlineAfter(5, TimeUnit.SECONDS);

			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
					.setUsername(username)
					.setSessionId(sessionId)
					.build();

			return blockingStub.endUserSession(request);
		} catch (Exception e) {
			System.err.println("End User Sessions Error: " + e);
			return null;
		}
	}

	// ============================================
	// P2P HOLE PUNCHING METHODS
	// ============================================

	/**
	 * Register user with P2P signaling server on application startup
	 * 
	 * @param username Username to register
	 * @return true if registration successful
	 */
	public static boolean registerP2PUser(String username) {
		try {
			System.out.println("[P2P] Registering user with server: " + username);

			// Set current username in ChatService for message rendering
			com.saferoom.gui.service.ChatService.getInstance().setCurrentUsername(username);

			// Initialize Persistent Storage (if password available)
			// Removed insecure initialization call. Storage should already be initialized
			// by Login logic.
			if (com.saferoom.storage.LocalDatabase.isInitialized()) {
				System.out.println("[Storage] Persistent storage is active.");
			} else {
				System.out.println("[Storage] Persistent storage not initialized (awaiting login).");
			}

			// WEBRTC P2P: Initialize P2PConnectionManager for messaging
			System.out.println("[P2P] Initializing WebRTC P2P messaging for: " + username);

			// Initialize P2PConnectionManager (shares WebRTCSignalingClient with
			// CallManager)
			com.saferoom.p2p.P2PConnectionManager.getInstance().initialize(username);

			System.out.println("[P2P] WebRTC P2P ready (connections will establish when friends come online)");

			// WebRTC callbacks are registered in registerP2PUser() method
			// (See setupWebRTCCallbacks in P2PConnectionManager)

			return true;

		} catch (Exception e) {
			System.err.println("[P2P] Error during user registration: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Initialize Persistent Storage for encrypted message history
	 * This should be called after successful login when password is available
	 * 
	 * @param username User's username
	 * @param password User's password (for encryption key derivation)
	 */
	public static void initializePersistentStorage(String username, String password) {
		try {
			System.out.println("[Storage] Initializing persistent storage...");

			// If password not provided, check if already initialized
			if (password == null || password.isEmpty()) {
				if (com.saferoom.storage.LocalDatabase.isInitialized()) {
					System.out.println("[Storage] Database already initialized. Skipping re-init.");
					return;
				}
				System.err.println("[Storage] ERROR: No password provided for persistent storage initialization!");
				return;
			}

			// Data directory
			String userHome = System.getProperty("user.home");
			String dataDir = userHome + "/.saferoom/data";

			// Initialize database
			com.saferoom.storage.LocalDatabase database = com.saferoom.storage.LocalDatabase.initialize(username,
					password, dataDir);

			System.out.println("[Storage] Database initialized at: " + database.getDbPath());

			// Initialize repository
			com.saferoom.storage.LocalMessageRepository repository = com.saferoom.storage.LocalMessageRepository
					.initialize(database);

			// Initialize persister and loader
			com.saferoom.chat.MessagePersister persister = com.saferoom.chat.MessagePersister.initialize(repository);

			com.saferoom.chat.PersistentChatLoader loader = new com.saferoom.chat.PersistentChatLoader(repository);

			// Connect to ChatService
			com.saferoom.gui.service.ChatService chatService = com.saferoom.gui.service.ChatService.getInstance();
			chatService.initializePersistence(persister, loader);

			System.out.println("[Storage] ✅ Persistent storage enabled!");
			System.out.println("[Storage] Messages will be encrypted and saved to disk");

		} catch (Exception e) {
			System.err.println("[Storage] Failed to initialize persistent storage: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Check if P2P messaging is available with specific peer
	 */
	public static boolean isP2PMessagingAvailable(String username) {
		// Use WebRTC DataChannel (P2PConnectionManager) instead of NatAnalyzer
		return com.saferoom.p2p.P2PConnectionManager.getInstance().hasActiveConnection(username);
	}

	/**
	 * Check if any P2P messaging is available
	 */
	public static boolean isP2PMessagingAvailable() {
		// WebRTC DataChannel doesn't need global check - check per user
		return true; // If user has P2P button, they can attempt connection
	}

}
