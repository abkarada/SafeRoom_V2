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
import java.io.File;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class ClientMenu{
	public static String Server = SafeRoomServer.ServerIP;
	public static int Port = SafeRoomServer.grpcPort;
	public static int UDP_Port = SafeRoomServer.udpPort1;
	private static String CERT_PATH = "certs/server.crt";
	public static ManagedChannel STUB_CHANNEL;
	
	// P2P bağlantı yönetimi
	private static Map<String, ICEManager> activeP2PConnections = new HashMap<>();

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
        
        // P2P bağlantısını başlat (STUN server olarak google stun server'ı kullan)
        iceManager.initiateConnection("stun.l.google.com", 19302);
        
        // Bağlantı başarılı olana kadar bekle (max 30 saniye)
        long startTime = System.currentTimeMillis();
        while (!iceManager.isConnected() && (System.currentTimeMillis() - startTime) < 30000) {
            Thread.sleep(100);
        }
        
        if (iceManager.isConnected()) {
            // Başarılı, aktif bağlantılara ekle
            activeP2PConnections.put(targetUser, iceManager);
            System.out.println("P2P connection established with " + targetUser);
            return iceManager;
        } else {
            // Başarısız, temizle
            iceManager.close();
            throw new Exception("P2P connection timeout");
        }
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


	
