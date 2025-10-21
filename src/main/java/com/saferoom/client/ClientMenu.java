package com.saferoom.client;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.UDPHoleGrpc;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.server.SafeRoomServer;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import com.saferoom.client.ICEManager.TurnConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientMenu{
	public static String Server = SafeRoomServer.ServerIP;
	public static int Port = SafeRoomServer.grpcPort;
	public static int UDP_Port = SafeRoomServer.udpPort1;
	private static String CERT_PATH = "certs/server.crt";
	public static ManagedChannel STUB_CHANNEL;
	
        private static final String[][] STUN_SERVERS = {
                {"stun.l.google.com", "19302"},
                {"stun1.l.google.com", "19302"},
                {"stun.ipv6.google.com", "19305"},
                {"stun.nextcloud.com", "3478"}
        };

        // P2P bağlantı yönetimi
        private static Map<String, ICEManager> activeP2PConnections = new ConcurrentHashMap<>();

        private static volatile String currentUser;
        private static final ExecutorService SERVER_EVENT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "server-events-dispatch");
                t.setDaemon(true);
                return t;
        });
        private static final ScheduledExecutorService SERVER_EVENT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "server-events-reconnect");
                t.setDaemon(true);
                return t;
        });
        private static final AtomicBoolean SERVER_EVENT_ACTIVE = new AtomicBoolean(false);
        private static final AtomicBoolean SERVER_EVENT_STOPPED = new AtomicBoolean(false);
        private static final Object SERVER_EVENT_LOCK = new Object();
        private static volatile String serverEventUsername;
        private static ScheduledFuture<?> serverEventReconnectTask;

	static{
		try{
		STUB_CHANNEL = createChannel();
		}catch(Exception e){
			System.err.println("Channel Creation Error: " + e);
		}
	}
	
        private static ManagedChannel createChannel() {
                try {
                        SslContext sslContext = GrpcSslContexts.forClient()
                                .trustManager(new File(CERT_PATH))
                                .build();

			return NettyChannelBuilder.forAddress(Server, Port)
				.sslContext(sslContext)
				.overrideAuthority(Server)
				.keepAliveTime(30, TimeUnit.SECONDS)  // Her 30 saniyede bir keepalive gönder
				.keepAliveTimeout(10, TimeUnit.SECONDS)  // 10 saniye timeout
				.keepAliveWithoutCalls(true)  // Aktif çağrı olmasa bile keepalive gönder
				.maxInboundMessageSize(10 * 1024 * 1024)  // 10MB max message size
				.build();
		} catch (Exception e) {
			System.err.println("Channel oluşturulurken hata: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("gRPC channel başlatılamadı", e);
                }
        }

        private static String[] selectStunServer() {
                boolean hasIpv6 = hasLocalIpv6Interface();
                if (hasIpv6) {
                        for (String[] server : STUN_SERVERS) {
                                if (server[0].contains("ipv6")) {
                                        return server;
                                }
                        }
                }
                return STUN_SERVERS[0];
        }

        private static boolean hasLocalIpv6Interface() {
                try {
                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces != null && interfaces.hasMoreElements()) {
                                NetworkInterface networkInterface = interfaces.nextElement();
                                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                                        continue;
                                }
                                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                                while (addresses.hasMoreElements()) {
                                        InetAddress address = addresses.nextElement();
                                        if (address instanceof Inet6Address && !address.isLinkLocalAddress()) {
                                                return true;
                                        }
                                }
                        }
                } catch (Exception e) {
                        System.err.println("Failed to inspect local interfaces for IPv6: " + e.getMessage());
                }
                return false;
        }

        private static void startServerEventStream(String username) {
                if (username == null || username.isBlank()) {
                        return;
                }
                synchronized (SERVER_EVENT_LOCK) {
                        serverEventUsername = username;
                        SERVER_EVENT_STOPPED.set(false);
                        if (SERVER_EVENT_ACTIVE.get()) {
                                return;
                        }
                }
                openServerEventStream();
        }

        private static void openServerEventStream() {
                String usernameSnapshot;
                synchronized (SERVER_EVENT_LOCK) {
                        if (SERVER_EVENT_STOPPED.get()) {
                                return;
                        }
                        if (serverEventUsername == null || serverEventUsername.isBlank()) {
                                return;
                        }
                        if (SERVER_EVENT_ACTIVE.get()) {
                                return;
                        }
                        usernameSnapshot = serverEventUsername;
                        SERVER_EVENT_ACTIVE.set(true);
                }

                SafeRoomProto.ServerEventRequest request = SafeRoomProto.ServerEventRequest.newBuilder()
                        .setUsername(usernameSnapshot)
                        .build();

                UDPHoleGrpc.UDPHoleStub stub = UDPHoleGrpc.newStub(STUB_CHANNEL);
                stub.streamServerEvents(request, new StreamObserver<SafeRoomProto.ServerEvent>() {
                        @Override
                        public void onNext(SafeRoomProto.ServerEvent value) {
                                SERVER_EVENT_EXECUTOR.execute(() -> handleServerEvent(value));
                        }

                        @Override
                        public void onError(Throwable t) {
                                System.err.println("Server event stream error: " + t.getMessage());
                                scheduleServerEventReconnect();
                        }

                        @Override
                        public void onCompleted() {
                                System.out.println("Server event stream completed for " + usernameSnapshot);
                                scheduleServerEventReconnect();
                        }
                });
        }

        private static void scheduleServerEventReconnect() {
                synchronized (SERVER_EVENT_LOCK) {
                        SERVER_EVENT_ACTIVE.set(false);
                        if (SERVER_EVENT_STOPPED.get()) {
                                return;
                        }
                        if (serverEventReconnectTask != null && !serverEventReconnectTask.isDone()) {
                                return;
                        }
                        serverEventReconnectTask = SERVER_EVENT_SCHEDULER.schedule(() -> openServerEventStream(), 3, TimeUnit.SECONDS);
                }
        }

        private static void handleServerEvent(SafeRoomProto.ServerEvent event) {
                if (event == null) {
                        return;
                }

                SafeRoomProto.ServerEvent.EventType type = event.getType();
                if (type == SafeRoomProto.ServerEvent.EventType.START_ICE) {
                        String initiator = event.getFromUser();
                        String localUser;
                        synchronized (SERVER_EVENT_LOCK) {
                                localUser = serverEventUsername;
                        }
                        if (initiator == null || initiator.isBlank() || localUser == null || localUser.isBlank()) {
                                return;
                        }
                        if (initiator.equals(localUser)) {
                                return;
                        }
                        try {
                                startP2PConnection(localUser, initiator);
                        } catch (Exception e) {
                                System.err.println("Failed to auto-start ICE for " + initiator + ": " + e.getMessage());
                        }
                } else if (type == SafeRoomProto.ServerEvent.EventType.RELAY_MESSAGE) {
                        String fromUser = event.getFromUser();
                        String message = event.getMessage();
                        System.out.println("[Relay] Message from " + fromUser + ": " + message);
                } else {
                        System.out.println("Received server event: " + type);
                }
        }

        private static void stopServerEventStream() {
                synchronized (SERVER_EVENT_LOCK) {
                        SERVER_EVENT_STOPPED.set(true);
                        SERVER_EVENT_ACTIVE.set(false);
                        serverEventUsername = null;
                        if (serverEventReconnectTask != null) {
                                serverEventReconnectTask.cancel(false);
                                serverEventReconnectTask = null;
                        }
                }
        }

        public static String Login(String username, String Password) {
                try {
			UDPHoleGrpc.UDPHoleBlockingStub client = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Menu main_menu = SafeRoomProto.Menu.newBuilder()
				.setUsername(username)
				.setHashPassword(Password)
				.build();
			
			SafeRoomProto.Status stats = client.menuAns(main_menu);
			
			String message = stats.getMessage();
			int code = stats.getCode();
			
                        switch(code){
                                case 0:
                                        System.out.println("Success!");
                                        System.out.printf("Logged in as: %s%n", username);
                                        currentUser = username;
                                        startServerEventStream(username);
                                        return message;
                                case 1:
                                        if(message.equals("N_REGISTER")){
                                                System.out.println("Not Registered");
                                                return "N_REGISTER";
					} else if(message.equals("WRONG_PASSWORD")){
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
		} catch (Exception e) {
			System.err.println("Login hatası: " + e.getMessage());
			e.printStackTrace();
			return "ERROR";
		}
	}
	public static int register_client(String username, String password, String mail) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
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
			
			switch(code){
				case 0:
					System.out.println("Success!");
					return 0;
				case 2:
					if(message.equals("VUSERNAME")){
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
			System.err.println("Register hatası: " + e.getMessage());
			e.printStackTrace();
			return 3;
		}
	}
	public static int verify_user(String username, String verify_code) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
					.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			Verification verification_info = Verification.newBuilder()
					.setUsername(username)
					.setVerify(verify_code)
					.build();
			
			SafeRoomProto.Status response = stub.verifyUser(verification_info);
			
			int code = response.getCode();
			
			switch(code) {
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
			System.err.println("Verify user hatası: " + e.getMessage());
			e.printStackTrace();
			return 2;
		}
	}

	public static boolean verify_email(String mail){
		try{
			UDPHoleGrpc.UDPHoleBlockingStub client = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(mail)
				.build();
			
			SafeRoomProto.Status status = client.verifyEmail(request);

			int code = status.getCode();

			if(code == 1){
				return true;
			}
			
		}catch(Exception e){
			System.err.println("Verify email hatası: " + e.getMessage());
			e.printStackTrace();
		}
		return false;
	}

	public static int changePassword(String email, String newPassword) {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub stub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
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
			System.err.println("Change Password hatası: " + e.getMessage());
			e.printStackTrace();
			return 2; 
		}
	}

	public static java.util.List<java.util.Map<String, Object>> searchUsers(String searchTerm, String currentUser) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
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
					
					System.out.println("Search Result for " + user.getUsername() + ":");
					System.out.println("  - is_friend: " + user.getIsFriend());
					System.out.println("  - has_pending_request: " + user.getHasPendingRequest());
					
					results.add(userMap);
				}
			}
			return results;
		} catch (Exception e) {
			System.err.println("Search users hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	public static SafeRoomProto.ProfileResponse getProfile(String targetUsername, String currentUser) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.ProfileRequest request = SafeRoomProto.ProfileRequest.newBuilder()
				.setUsername(targetUsername)
				.setRequestedBy(currentUser)
				.build();
				
			SafeRoomProto.ProfileResponse response = blockingStub.getProfile(request);
			return response;
		} catch (Exception e) {
			System.err.println("Get profile hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	public static SafeRoomProto.FriendResponse sendFriendRequest(String fromUser, String toUser) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequest request = SafeRoomProto.FriendRequest.newBuilder()
				.setSender(fromUser)
				.setReceiver(toUser)
				.setMessage("") // Boş mesaj
				.build();
				
			SafeRoomProto.FriendResponse response = blockingStub.sendFriendRequest(request);
			return response;
		} catch (Exception e) {
			System.err.println("Send friend request hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
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
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getPendingFriendRequests(request);
		} catch (Exception e) {
			System.err.println("Get pending friend requests hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Gönderilen arkadaşlık isteklerini getir (giden istekler)
	 */
	public static SafeRoomProto.SentRequestsResponse getSentFriendRequests(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getSentFriendRequests(request);
		} catch (Exception e) {
			System.err.println("Get sent friend requests hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Arkadaşlık isteğini kabul et
	 */
	public static SafeRoomProto.Status acceptFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.acceptFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Accept friend request hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Arkadaşlık isteğini reddet
	 */
	public static SafeRoomProto.Status rejectFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.rejectFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Reject friend request hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Gönderilen arkadaşlık isteğini iptal et
	 */
	public static SafeRoomProto.Status cancelFriendRequest(int requestId, String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.FriendRequestAction request = SafeRoomProto.FriendRequestAction.newBuilder()
				.setRequestId(requestId)
				.setUsername(username)
				.build();
				
			return blockingStub.cancelFriendRequest(request);
		} catch (Exception e) {
			System.err.println("Cancel friend request hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Arkadaş listesini getir
	 */
	public static SafeRoomProto.FriendsListResponse getFriendsList(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getFriendsList(request);
		} catch (Exception e) {
			System.err.println("Get friends list hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Arkadaşı kaldır
	 */
	public static SafeRoomProto.Status removeFriend(String user1, String user2) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.RemoveFriendRequest request = SafeRoomProto.RemoveFriendRequest.newBuilder()
				.setUser1(user1)
				.setUser2(user2)
				.build();
				
			return blockingStub.removeFriend(request);
		} catch (Exception e) {
			System.err.println("Remove friend hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Arkadaşlık istatistiklerini getir
	 */
	public static SafeRoomProto.FriendshipStatsResponse getFriendshipStats(String username) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(10, TimeUnit.SECONDS);
			
			SafeRoomProto.Request_Client request = SafeRoomProto.Request_Client.newBuilder()
				.setUsername(username)
				.build();
				
			return blockingStub.getFriendshipStats(request);
		} catch (Exception e) {
			System.err.println("Get friendship stats hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Heartbeat gönder
	 */
	public static SafeRoomProto.HeartbeatResponse sendHeartbeat(String username, String sessionId) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(5, TimeUnit.SECONDS);
			
			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
				.setUsername(username)
				.setSessionId(sessionId)
				.build();
				
			return blockingStub.sendHeartbeat(request);
		} catch (Exception e) {
			System.err.println("Send heartbeat hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * User session'ını sonlandır
	 */
	public static SafeRoomProto.Status endUserSession(String username, String sessionId) throws Exception {
		try {
			UDPHoleGrpc.UDPHoleBlockingStub blockingStub = UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
				.withDeadlineAfter(5, TimeUnit.SECONDS);
			
			SafeRoomProto.HeartbeatRequest request = SafeRoomProto.HeartbeatRequest.newBuilder()
				.setUsername(username)
				.setSessionId(sessionId)
				.build();
				
			return blockingStub.endUserSession(request);
		} catch (Exception e) {
			System.err.println("End user session hatası: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Channel'ı düzgün bir şekilde kapatır
	 * Uygulamadan çıkarken çağrılmalı
	 */
                public static void shutdownChannel() {
                stopServerEventStream();
                SERVER_EVENT_STOPPED.set(true);
                SERVER_EVENT_ACTIVE.set(false);
                SERVER_EVENT_EXECUTOR.shutdownNow();
                SERVER_EVENT_SCHEDULER.shutdownNow();
                if (STUB_CHANNEL != null && !STUB_CHANNEL.isShutdown()) {
                        try {
                                System.out.println("gRPC channel kapatılıyor...");
                                STUB_CHANNEL.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                                System.out.println("gRPC channel başarıyla kapatıldı");
			} catch (InterruptedException e) {
				System.err.println("Channel kapatılırken hata: " + e.getMessage());
				STUB_CHANNEL.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}
	
	// ============================================
	// P2P HOLE PUNCHING METHODS
	// ============================================

	/**
     * P2P bağlantısı başlat (Trickle ICE ile)
     * 
     * @param currentUser Mevcut kullanıcı
     * @param targetUser Bağlanılacak kullanıcı
     * @return ICEManager instance (başarılıysa)
     * @throws Exception Bağlantı başarısız olursa
     */
    public static ICEManager startP2PConnection(String currentUser, String targetUser) throws Exception {
        System.out.println("Starting P2P connection: " + currentUser + " -> " + targetUser);

        // Zaten bağlantı varsa onu döndür
        if (activeP2PConnections.containsKey(targetUser)) {
            ICEManager existing = activeP2PConnections.get(targetUser);
            if (existing.isConnected()) {
                System.out.println("Already connected to " + targetUser);
                return existing;
            } else {
                // Bağlantı kopmuşsa kapat ve yeniden başlat
                existing.close();
                activeP2PConnections.remove(targetUser);
            }
        }
        
        // ICE Manager oluştur
        ICEManager iceManager = new ICEManager(currentUser, targetUser, STUB_CHANNEL);

        // Opsiyonel TURN desteğini çevre değişkenlerinden yükle
        TurnConfig turnConfig = TurnConfig.fromEnvironment();

        String[] stunSelection = selectStunServer();
        String stunHost = stunSelection[0];
        int stunPort = Integer.parseInt(stunSelection[1]);
        System.out.println("Using STUN server: " + stunHost + ":" + stunPort);

        iceManager.setListener(new ICEEventListener() {
            @Override
            public void onLocalCandidateDiscovered(org.ice4j.ice.LocalCandidate candidate) {
                // no-op
            }

            @Override
            public void onRemoteCandidateAdded(org.ice4j.ice.RemoteCandidate candidate) {
                // no-op
            }

            @Override
            public void onGatheringComplete() {
                System.out.println("Local ICE gathering complete for " + targetUser);
            }

            @Override
            public void onIceStateChanged(org.ice4j.ice.IceProcessingState state) {
                System.out.println("ICE state for " + targetUser + ": " + state);
            }

            @Override
            public void onConnected(org.ice4j.ice.CandidatePair pair) {
                activeP2PConnections.put(targetUser, iceManager);
                System.out.println("P2P connection established with " + targetUser);
            }

            @Override
            public void onFailure(String reason) {
                System.err.println("P2P connection failed for " + targetUser + ": " + reason);
                activeP2PConnections.remove(targetUser);
            }
        });

        try {
            iceManager.initiateConnection(stunHost, stunPort, turnConfig);
            return iceManager;
        } catch (Exception e) {
            iceManager.close();
            throw e;
        }
    }

    public static boolean sendMessageAutoICE(String fromUser, String toUser, String text) {
        if (fromUser == null || fromUser.isBlank() || toUser == null || toUser.isBlank()) {
            throw new IllegalArgumentException("fromUser and toUser must be provided");
        }

        startServerEventStream(fromUser);

        ICEManager iceManager = null;
        boolean sentViaP2P = false;

        try {
            iceManager = startP2PConnection(fromUser, toUser);
        } catch (Exception e) {
            System.err.println("Failed to start local ICE session: " + e.getMessage());
        }

        try {
            SafeRoomProto.StartIceForPeer request = SafeRoomProto.StartIceForPeer.newBuilder()
                .setFromUser(fromUser)
                .setToUser(toUser)
                .build();
            UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .requestPeerToStartIce(request);
        } catch (Exception e) {
            System.err.println("Failed to trigger remote ICE start: " + e.getMessage());
        }

        if (iceManager != null) {
            try {
                if (iceManager.awaitConnected(10, TimeUnit.SECONDS)) {
                    sentViaP2P = iceManager.sendTextMessage(text == null ? "" : text);
                } else {
                    System.err.println("Timed out waiting for ICE connectivity between " + fromUser + " and " + toUser);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        if (!sentViaP2P) {
            try {
                SafeRoomProto.StartIceForPeer relayRequest = SafeRoomProto.StartIceForPeer.newBuilder()
                    .setFromUser(fromUser)
                    .setToUser(toUser)
                    .setMessage(text == null ? "" : text)
                    .build();
                UDPHoleGrpc.newBlockingStub(STUB_CHANNEL)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .relayMessage(relayRequest);
            } catch (Exception e) {
                System.err.println("Failed to relay message via server: " + e.getMessage());
            }
        }

        return sentViaP2P;
    }

    /**
     * P2P bağlantısını kapat
     *
     * @param targetUser Bağlantısı kesilecek kullanıcı
     */
    public static void closeP2PConnection(String targetUser) {
        ICEManager iceManager = activeP2PConnections.get(targetUser);
        if (iceManager != null) {
            iceManager.close();
            activeP2PConnections.remove(targetUser);
            System.out.println("P2P connection closed with " + targetUser);
        }
    }
    
    /**
     * Belirli bir kullanıcıyla P2P bağlantısı var mı?
     * 
     * @param targetUser Kontrol edilecek kullanıcı
     * @return true ise bağlı, false ise değil
     */
    public static boolean isP2PConnected(String targetUser) {
        ICEManager iceManager = activeP2PConnections.get(targetUser);
        return iceManager != null && iceManager.isConnected();
    }
    
    /**
     * Aktif P2P bağlantısını al
     * 
     * @param targetUser Bağlantısı alınacak kullanıcı
     * @return ICEManager instance veya null
     */
    public static ICEManager getP2PConnection(String targetUser) {
        return activeP2PConnections.get(targetUser);
    }
    
    /**
     * Tüm P2P bağlantılarını kapat
     */
    public static void closeAllP2PConnections() {
        for (Map.Entry<String, ICEManager> entry : activeP2PConnections.entrySet()) {
            entry.getValue().close();
        }
        activeP2PConnections.clear();
        System.out.println("All P2P connections closed");
    }
    

}


	
