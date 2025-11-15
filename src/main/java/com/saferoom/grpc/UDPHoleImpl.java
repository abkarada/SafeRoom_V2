package com.saferoom.grpc;

import java.sql.SQLException;
import java.util.Base64;

import java.util.List;
import java.util.Map;

import com.saferoom.crypto.KeyExchange;
import com.saferoom.crypto.VerificationCodeGenerator;
import com.saferoom.grpc.SafeRoomProto.Menu;
import com.saferoom.grpc.SafeRoomProto.Request_Client;
import com.saferoom.grpc.SafeRoomProto.Status;
import com.saferoom.grpc.SafeRoomProto.Verification;
import com.saferoom.grpc.SafeRoomProto.Create_User;
import com.saferoom.grpc.SafeRoomProto.SearchRequest;
import com.saferoom.grpc.SafeRoomProto.SearchResponse;
import com.saferoom.grpc.SafeRoomProto.UserResult;
import com.saferoom.db.*;
import com.saferoom.email.EmailSender;
import com.saferoom.grpc.SafeRoomProto.ProfileRequest;
import com.saferoom.grpc.SafeRoomProto.ProfileResponse;
import com.saferoom.grpc.SafeRoomProto.UserProfile;
import com.saferoom.grpc.SafeRoomProto.UserStats;
import com.saferoom.grpc.SafeRoomProto.UserActivity;
import com.saferoom.grpc.SafeRoomProto.FriendRequest;
import com.saferoom.grpc.SafeRoomProto.FriendResponse;
import com.saferoom.webrtc.WebRTCSessionManager;
import com.saferoom.webrtc.WebRTCSessionManager.CallSession;
import com.saferoom.server.SDRTCoordinator;
import com.saferoom.crypto.GroupKeyManager;
import java.text.SimpleDateFormat;
import java.sql.Timestamp;


import io.grpc.stub.StreamObserver;

public class UDPHoleImpl extends UDPHoleGrpc.UDPHoleImplBase {
	
	// SDRT Components (Serverless Distributed Relay Tree)
	private final SDRTCoordinator sdrtCoordinator = new SDRTCoordinator();
	private final GroupKeyManager groupKeyManager = new GroupKeyManager(true); // Auto-rotation enabled
	
	@Override
	public void menuAns(Menu request, StreamObserver<Status> response){
	String usernameOrEmail = request.getUsername(); // Artık username veya email olabilir
	String hash_password = request.getHashPassword();
	try {
	boolean user_exist = DBManager.userExists(usernameOrEmail);
		if(user_exist){

			if(!DBManager.isUserBlocked(usernameOrEmail)){ // ! eklendi (blocked değilse)
				
				if(DBManager.verifyPassword(usernameOrEmail, hash_password)){
					// Doğru şifre: attempt sayısını sıfırla
					DBManager.resetLoginAttempts(usernameOrEmail);
					
					// Kullanıcının email ile mi username ile mi giriş yaptığını kontrol et
					String responseMessage;
					if (usernameOrEmail.contains("@")) {
						// Email ile giriş yapmış, username'i döndür
						String username = DBManager.getUsernameByEmail(usernameOrEmail);
						responseMessage = username != null ? username : "UNKNOWN_USER";
					} else {
						// Username ile giriş yapmış, email'i döndür  
						String email = DBManager.getEmailByUsername(usernameOrEmail);
						responseMessage = email != null ? email : "UNKNOWN_EMAIL";
					}

					Status stat = Status.newBuilder()
						.setMessage(responseMessage) // "ALL_GOOD" yerine eksik bilgiyi gönder
						.setCode(0)
						.build();
					DBManager.updateLastLogin(usernameOrEmail);
					response.onNext(stat);
				}else{
					// Yanlış şifre: attempt sayısını artır
					DBManager.updateLoginAttempts(usernameOrEmail);
					Status stat = Status.newBuilder()
						.setMessage("WRONG_PASSWORD")
						.setCode(1)
						.build();
					response.onNext(stat);
				}
			}else{
				Status blocked_stat = Status.newBuilder()
					.setMessage("BLOCKED")
					.setCode(1)
					.build();
				response.onNext(blocked_stat);
			}
		}else{
			Status not_ex = Status.newBuilder()
				.setMessage("N_REGISTER")
				.setCode(1)
				.build();
			response.onNext(not_ex);
		}
		response.onCompleted();	
	
	}catch(Exception e ) {
		System.out.println("DB Error Accoured: " + e);
		response.onError(e);

	}
	}
	
	@Override
	public void insertUser(Create_User request, StreamObserver<Status> response){
		String username = request.getUsername();
		String email =  request.getEmail();
		String password = request.getPassword();
		// verification_code ve is_verified kullanılmıyor, kaldırıldı
	
		boolean is_mail_valid = true;
		try {
			is_mail_valid = DBManager.check_email(email);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			response.onError(e);

		}
	
		if(is_mail_valid == false){
			try {
				if(DBManager.createUser(username, password, email))
				{
					// Her kullanıcı için yeni verification code üret
					String verificationCode = VerificationCodeGenerator.generateVerificationCode();
					DBManager.setVerificationCode(username, verificationCode);
					
					// Yeni HTML template ile email gönder
					if(EmailSender.sendVerificationEmail(email, username, verificationCode)) {
						System.out.println("Successfully Registered and verification email sent!");
					}
					Status stat = Status.newBuilder()
						.setMessage("SUCCESS")
						.setCode(0)
						.build();
					response.onNext(stat);	
				}
				else{
					System.out.println("Username already exists");
					Status not_valid = Status.newBuilder()
						.setMessage("VUSERNAME")
						.setCode(2)
						.build();
					response.onNext(not_valid);
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				response.onError(e);

			}
	
		}
		else{
			System.out.println("Email is already in use");
			Status invalid_mail = Status.newBuilder()
				.setMessage("INVALID_MAIL")
				.setCode(2)
				.build();
			response.onNext(invalid_mail);
		}
			response.onCompleted();
	}
	
	@Override 
	public void verifyUser(Verification verify_code, StreamObserver<Status> responseObserver)
	{
		String usernameOrEmail = verify_code.getUsername(); // Artık username veya email olabilir
		String verCode = verify_code.getVerify();
		try {
		String db_search = DBManager.getVerificationCode(usernameOrEmail);
		if(verCode.equals(db_search)) {
			DBManager.Verify(usernameOrEmail);
	        DBManager.updateVerificationAttempts(usernameOrEmail);

			Status situtation = Status.newBuilder()
					.setCode(0)
					.setMessage("MATCH")
					.build();
			responseObserver.onNext(situtation);
		}else {
			Status not_match = Status.newBuilder()
					.setMessage("NOT_MATCH")
					.setCode(1)
					.build();
			responseObserver.onNext(not_match);
		}
		}catch(Exception e) {
			System.out.println(e);
			responseObserver.onError(e);
		}
		responseObserver.onCompleted();
	}
	
	@Override
	public void verifyEmail(Request_Client req, StreamObserver<Status> responseObserver){
		String candicate_email = req.getUsername();
		Status response = null;
		try{
		boolean is_mail_exists = DBManager.check_email(candicate_email);
		if(is_mail_exists){
			 response = Status.newBuilder()
				.setMessage("EXISTS")
				.setCode(0)
				.build();
				
				String username = DBManager.return_usersname_from_email(candicate_email);
				String verificationCode = VerificationCodeGenerator.generateVerificationCode();
				
				if(DBManager.change_verification_code(username, verificationCode)){System.out.println("Code set");}
				else{System.out.println("IT FAILED");}					
					
				if(EmailSender.sendPasswordResetEmail(candicate_email, username, verificationCode)) {
						System.out.println("Successfully Reset Code Sent!");
					}

		}else{
			 response = Status.newBuilder()
				.setMessage("NOT_EXISTS")
				.setCode(1)
				.build();
		}
		responseObserver.onNext(response);
		responseObserver.onCompleted();
		}
		catch(Exception e){
			System.err.println("Database Error[Email Verification]: " + e);
		}
	}

	@Override
	public void changePassword(Request_Client request, StreamObserver<Status> responseObserver){
		String requestData = request.getUsername(); // Format: "email:newpassword"
		
		try {
			// Request formatını parse et
			if (!requestData.contains(":")) {
				Status errorResponse = Status.newBuilder()
					.setMessage("INVALID_FORMAT")
					.setCode(2)
					.build();
				responseObserver.onNext(errorResponse);
				responseObserver.onCompleted();
				return;
			}
			
			String[] parts = requestData.split(":", 2);
			String email = parts[0];
			String newPassword = parts[1];
			
			// Email'in kayıtlı olup olmadığını kontrol et
			if (!DBManager.check_email(email)) {
				Status notFoundResponse = Status.newBuilder()
					.setMessage("EMAIL_NOT_FOUND")
					.setCode(1)
					.build();
				responseObserver.onNext(notFoundResponse);
				responseObserver.onCompleted();
				return;
			}
			
			if (DBManager.change_users_password(email, newPassword)) {
				System.out.println("Password successfully changed for: " + email);
				
				Status successResponse = Status.newBuilder()
					.setMessage("PASSWORD_CHANGED")
					.setCode(0)
					.build();
				responseObserver.onNext(successResponse);
			} else {
				Status failResponse = Status.newBuilder()
					.setMessage("PASSWORD_CHANGE_FAILED")
					.setCode(2)
					.build();
				responseObserver.onNext(failResponse);
			}
			
		} catch (Exception e) {
			System.err.println("Password change error: " + e.getMessage());
			e.printStackTrace();
			
			Status errorResponse = Status.newBuilder()
				.setMessage("DATABASE_ERROR")
				.setCode(2)
				.build();
			responseObserver.onNext(errorResponse);
		}
		
		responseObserver.onCompleted();
	}
	@Override
	public void searchUsers(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
		try {
			String searchTerm = request.getSearchTerm();
			String currentUser = request.getCurrentUser();
			
			// Debug log
			System.out.println(" User Search Request:");
			System.out.println("  Search Term: '" + searchTerm + "'");
			System.out.println("  Current User: '" + currentUser + "'");
			
			// Minimum 2 karakter kontrolü
			if (searchTerm.length() < 2) {
				System.out.println("Search term too short (less than 2 characters)");
				responseObserver.onNext(SearchResponse.newBuilder()
					.setSuccess(false)
					.setMessage("En az 2 karakter girin")
					.build());
				responseObserver.onCompleted();
				return;
			}
			
			List<java.util.Map<String, Object>> results = DBManager.searchUsers(searchTerm, currentUser, 10);
			
			// Debug: Results log
			System.out.println("Search Results:");
			System.out.println("  Found " + results.size() + " users:");
			for (java.util.Map<String, Object> user : results) {
				System.out.println("    - " + user.get("username") + " (" + user.get("email") + ")");
			}

			SearchResponse.Builder responseBuilder = SearchResponse.newBuilder().setSuccess(true);
				
			for (java.util.Map<String, Object> user : results) {
				Boolean isFriend = (Boolean) user.get("is_friend");
				Boolean hasPending = (Boolean) user.get("has_pending_request");
				
				responseBuilder.addUsers(UserResult.newBuilder()
					.setUsername((String) user.get("username"))
					.setEmail((String) user.get("email"))
					.setIsOnline(false)
					.setLastSeen(user.get("lastLogin") != null ? user.get("lastLogin").toString() : "")
					.setIsFriend(isFriend != null ? isFriend : false)
					.setHasPendingRequest(hasPending != null ? hasPending : false)
					.build());
			}
			
			System.out.println("Search completed successfully, sending response to client");
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Search Error: " + e.getMessage());
			e.printStackTrace();
			responseObserver.onError(e);
		}
	}

// ===============================
// PROFILE SYSTEM METHODS
// ===============================

@Override
public void getProfile(ProfileRequest request, StreamObserver<ProfileResponse> responseObserver) {
    try {
        String username = request.getUsername();
        String requestedBy = request.getRequestedBy();
        
        System.out.println("Profile request for '" + username + "' by '" + requestedBy + "'");
        
        // Kullanıcı var mı kontrol et
        if (!DBManager.userExists(username)) {
            System.out.println("User '" + username + "' not found");
            responseObserver.onNext(ProfileResponse.newBuilder()
                .setSuccess(false)
                .setMessage("User not found")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Profile bilgilerini al
        java.util.Map<String, Object> profileData = DBManager.getUserProfile(username, requestedBy);
        
        if (profileData == null) {
            System.out.println("Failed to load profile for '" + username + "'");
            responseObserver.onNext(ProfileResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Failed to load profile")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Date formatter
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm");
        
        // Stats bilgilerini al - Null kontrolü ile
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> statsData = (java.util.Map<String, Object>) profileData.get("stats");
        
        UserStats stats = UserStats.newBuilder()
            .setRoomsCreated(statsData.get("roomsCreated") != null ? (Integer) statsData.get("roomsCreated") : 0)
            .setRoomsJoined(statsData.get("roomsJoined") != null ? (Integer) statsData.get("roomsJoined") : 0)
            .setFilesShared(statsData.get("filesShared") != null ? (Integer) statsData.get("filesShared") : 0)
            .setMessagesSent(statsData.get("messagesSent") != null ? (Integer) statsData.get("messagesSent") : 0)
            .setSecurityScore(statsData.get("activityScore") != null ? (Double) statsData.get("activityScore") : 0.0) // activityScore kullan ama proto'da securityScore field'ına koy
            .build();
        
        // Activities bilgilerini al
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> activitiesData = 
            (java.util.List<java.util.Map<String, Object>>) profileData.get("activities");
        
        UserProfile.Builder profileBuilder = UserProfile.newBuilder()
            .setUsername((String) profileData.get("username"))
            .setEmail((String) profileData.get("email"))
            .setJoinDate(dateFormat.format((Timestamp) profileData.get("joinDate")))
            .setLastSeen(timeFormat.format((Timestamp) profileData.get("lastSeen")))
            .setIsOnline(false) // TODO: Online status implementation
            .setStats(stats)
            .setFriendStatus((String) profileData.get("friendStatus"));
        
        // Activities ekle
        for (java.util.Map<String, Object> activity : activitiesData) {
            UserActivity userActivity = UserActivity.newBuilder()
                .setActivityType((String) activity.get("activityType"))
                .setDescription((String) activity.get("description"))
                .setTimestamp(timeFormat.format((Timestamp) activity.get("timestamp")))
                .setActivityData(activity.get("activityData") != null ? (String) activity.get("activityData") : "")
                .build();
            profileBuilder.addRecentActivities(userActivity);
        }
        
        // Friend status kontrolü
        String friendStatus = (String) profileData.get("friendStatus");
        profileBuilder.setIsFriend("friends".equals(friendStatus));
        
        UserProfile profile = profileBuilder.build();
        
        System.out.println("✅ Profile loaded for '" + username + "' - Friend status: " + friendStatus);
        
        responseObserver.onNext(ProfileResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Profile loaded successfully")
            .setProfile(profile)
            .build());
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        System.err.println("❌ Profile request error: " + e.getMessage());
        e.printStackTrace();
        responseObserver.onNext(ProfileResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Server error: " + e.getMessage())
            .build());
        responseObserver.onCompleted();
    }
}

@Override
public void sendFriendRequest(FriendRequest request, StreamObserver<FriendResponse> responseObserver) {
    try {
        String sender = request.getSender();
        String receiver = request.getReceiver();
        String message = request.getMessage();
        
        System.out.println("Friend request: '" + sender + "' -> '" + receiver + "'");
        
        // Kullanıcılar var mı kontrol et
        if (!DBManager.userExists(sender) || !DBManager.userExists(receiver)) {
            System.out.println("One or both users not found");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(false)
                .setMessage("User not found")
                .setStatus("error")
                .build());
            responseObserver.onCompleted();
            return;
        }
        
        // Friend request gönder
        boolean success = DBManager.sendFriendRequest(sender, receiver, message);
        
        if (success) {
            System.out.println("Friend request sent successfully");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Friend request sent successfully")
                .setStatus("sent")
                .build());
        } else {
            System.out.println("Friend request failed (already exists or blocked)");
            responseObserver.onNext(FriendResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Friend request already exists or users are blocked")
                .setStatus("failed")
                .build());
        }
        
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        System.err.println("Friend request error: " + e.getMessage());
        e.printStackTrace();
        responseObserver.onNext(FriendResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Server error: " + e.getMessage())
            .setStatus("error")
            .build());
        responseObserver.onCompleted();
    }
}
	// ===============================
	// FRIEND SYSTEM - EKSIK METODLAR
	// ===============================
	
	@Override
	public void getPendingFriendRequests(Request_Client request, StreamObserver<SafeRoomProto.PendingRequestsResponse> responseObserver) {
		try {
			String username = request.getUsername();
			
			System.out.println("Getting pending friend requests for: " + username);
			
			List<Map<String, Object>> requests = DBManager.getPendingFriendRequests(username);
			
			SafeRoomProto.PendingRequestsResponse.Builder responseBuilder = 
				SafeRoomProto.PendingRequestsResponse.newBuilder()
					.setSuccess(true)
					.setMessage("Pending requests retrieved successfully");
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm");
			
			for (Map<String, Object> friendRequest : requests) {
				SafeRoomProto.FriendRequestInfo friendRequestInfo = SafeRoomProto.FriendRequestInfo.newBuilder()
					.setRequestId((Integer) friendRequest.get("requestId"))
					.setSender((String) friendRequest.get("sender"))
					.setReceiver(username)
					.setMessage((String) friendRequest.get("message"))
					.setSentAt(dateFormat.format((Timestamp) friendRequest.get("sentAt")))
					.setSenderEmail((String) friendRequest.get("senderEmail"))
					.setSenderLastSeen(friendRequest.get("senderLastSeen") != null ? 
						dateFormat.format((Timestamp) friendRequest.get("senderLastSeen")) : "")
					.build();
				
				responseBuilder.addRequests(friendRequestInfo);
			}
			
			System.out.println("Found " + requests.size() + " pending requests");
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error getting pending requests: " + e.getMessage());
			responseObserver.onNext(SafeRoomProto.PendingRequestsResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void getSentFriendRequests(Request_Client request, StreamObserver<SafeRoomProto.SentRequestsResponse> responseObserver) {
		try {
			String username = request.getUsername();
			
			List<Map<String, Object>> requests = DBManager.getSentFriendRequests(username);
			
			SafeRoomProto.SentRequestsResponse.Builder responseBuilder = 
				SafeRoomProto.SentRequestsResponse.newBuilder()
					.setSuccess(true)
					.setMessage("Sent requests retrieved successfully");
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm");
			
			for (Map<String, Object> sentRequest : requests) {
				SafeRoomProto.FriendRequestInfo friendRequestInfo = SafeRoomProto.FriendRequestInfo.newBuilder()
					.setRequestId((Integer) sentRequest.get("requestId"))
					.setSender(username)
					.setReceiver((String) sentRequest.get("receiver"))
					.setMessage((String) sentRequest.get("message"))
					.setSentAt(dateFormat.format((Timestamp) sentRequest.get("sentAt")))
					.setSenderEmail((String) sentRequest.get("receiverEmail")) // Receiver's email in sender field for display
					.setSenderLastSeen(sentRequest.get("receiverLastSeen") != null ? 
						dateFormat.format((Timestamp) sentRequest.get("receiverLastSeen")) : "") // Receiver's last seen
					.build();
				
				responseBuilder.addRequests(friendRequestInfo);
			}
			
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error getting sent requests: " + e.getMessage());
			responseObserver.onNext(SafeRoomProto.SentRequestsResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void acceptFriendRequest(SafeRoomProto.FriendRequestAction request, StreamObserver<Status> responseObserver) {
		try {
			int requestId = request.getRequestId();
			String username = request.getUsername();
			
			System.out.println("Accepting friend request: " + requestId + " by " + username);
			
			boolean success = DBManager.acceptFriendRequest(requestId, username);
			
			Status response;
			if (success) {
				response = Status.newBuilder()
					.setMessage("Friend request accepted successfully")
					.setCode(0)
					.build();
			} else {
				response = Status.newBuilder()
					.setMessage("Failed to accept friend request")
					.setCode(1)
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error accepting friend request: " + e.getMessage());
			responseObserver.onNext(Status.newBuilder()
				.setMessage("Error: " + e.getMessage())
				.setCode(2)
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void rejectFriendRequest(SafeRoomProto.FriendRequestAction request, StreamObserver<Status> responseObserver) {
		try {
			int requestId = request.getRequestId();
			String username = request.getUsername();
			
			System.out.println("Rejecting friend request: " + requestId + " by " + username);
			
			boolean success = DBManager.rejectFriendRequest(requestId, username);
			
			Status response;
			if (success) {
				response = Status.newBuilder()
					.setMessage("Friend request rejected successfully")
					.setCode(0)
					.build();
			} else {
				response = Status.newBuilder()
					.setMessage("Failed to reject friend request")
					.setCode(1)
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error rejecting friend request: " + e.getMessage());
			responseObserver.onNext(Status.newBuilder()
				.setMessage("Error: " + e.getMessage())
				.setCode(2)
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void cancelFriendRequest(SafeRoomProto.FriendRequestAction request, StreamObserver<Status> responseObserver) {
		try {
			int requestId = request.getRequestId();
			String username = request.getUsername();
			
			boolean success = DBManager.cancelFriendRequest(requestId, username);
			
			Status response;
			if (success) {
				response = Status.newBuilder()
					.setMessage("Friend request cancelled successfully")
					.setCode(0)
					.build();
			} else {
				response = Status.newBuilder()
					.setMessage("Failed to cancel friend request")
					.setCode(1)
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			responseObserver.onNext(Status.newBuilder()
				.setMessage("Error: " + e.getMessage())
				.setCode(2)
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void getFriendsList(Request_Client request, StreamObserver<SafeRoomProto.FriendsListResponse> responseObserver) {
		try {
			String username = request.getUsername();
			
			List<Map<String, Object>> friends = DBManager.getFriendsList(username);
			
			SafeRoomProto.FriendsListResponse.Builder responseBuilder = 
				SafeRoomProto.FriendsListResponse.newBuilder()
					.setSuccess(true)
					.setMessage("Friends list retrieved successfully");
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy");
			
			for (Map<String, Object> friend : friends) {
				SafeRoomProto.FriendInfo friendInfo = SafeRoomProto.FriendInfo.newBuilder()
					.setUsername((String) friend.get("username"))
					.setEmail((String) friend.get("email"))
					.setFriendshipDate(dateFormat.format((Timestamp) friend.get("friendshipDate")))
					.setLastSeen(friend.get("lastSeen") != null ? 
						dateFormat.format((Timestamp) friend.get("lastSeen")) : "")
					.setIsVerified((Boolean) friend.get("isVerified"))
					.setIsOnline((Boolean) friend.get("isOnline"))
					.build();
				
				responseBuilder.addFriends(friendInfo);
			}
			
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			responseObserver.onNext(SafeRoomProto.FriendsListResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void removeFriend(SafeRoomProto.RemoveFriendRequest request, StreamObserver<Status> responseObserver) {
		try {
			String user1 = request.getUser1();
			String user2 = request.getUser2();
			
			boolean success = DBManager.removeFriend(user1, user2);
			
			Status response;
			if (success) {
				response = Status.newBuilder()
					.setMessage("Friend removed successfully")
					.setCode(0)
					.build();
			} else {
				response = Status.newBuilder()
					.setMessage("Failed to remove friend")
					.setCode(1)
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			responseObserver.onNext(Status.newBuilder()
				.setMessage("Error: " + e.getMessage())
				.setCode(2)
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void getFriendshipStats(Request_Client request, StreamObserver<SafeRoomProto.FriendshipStatsResponse> responseObserver) {
		try {
			String username = request.getUsername();
			
			Map<String, Object> stats = DBManager.getFriendshipStats(username);
			
			SafeRoomProto.FriendshipStats friendshipStats = SafeRoomProto.FriendshipStats.newBuilder()
				.setTotalFriends((Integer) stats.get("totalFriends"))
				.setPendingRequests((Integer) stats.get("pendingRequests"))
				.setSentRequests((Integer) stats.get("sentRequests"))
				.build();
			
			SafeRoomProto.FriendshipStatsResponse response = SafeRoomProto.FriendshipStatsResponse.newBuilder()
				.setSuccess(true)
				.setMessage("Stats retrieved successfully")
				.setStats(friendshipStats)
				.build();
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			responseObserver.onNext(SafeRoomProto.FriendshipStatsResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void sendHeartbeat(SafeRoomProto.HeartbeatRequest request, StreamObserver<SafeRoomProto.HeartbeatResponse> responseObserver) {
		try {
			String username = request.getUsername();
			String sessionId = request.getSessionId();
			
			System.out.println("Heartbeat from: " + username + " (session: " + sessionId + ")");
			
			boolean success = DBManager.updateHeartbeat(username, sessionId);
			
			SafeRoomProto.HeartbeatResponse response;
			if (success) {
				response = SafeRoomProto.HeartbeatResponse.newBuilder()
					.setSuccess(true)
					.setMessage("Heartbeat received")
					.build();
			} else {
				response = SafeRoomProto.HeartbeatResponse.newBuilder()
					.setSuccess(false)
					.setMessage("Failed to update heartbeat")
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error processing heartbeat: " + e.getMessage());
			responseObserver.onNext(SafeRoomProto.HeartbeatResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	@Override
	public void endUserSession(SafeRoomProto.HeartbeatRequest request, StreamObserver<Status> responseObserver) {
		try {
			String username = request.getUsername();
			String sessionId = request.getSessionId();
			
			System.out.println("️Ending session for: " + username + " (session: " + sessionId + ")");
			
			boolean success = DBManager.endUserSession(username, sessionId);
			
			Status response;
			if (success) {
				response = Status.newBuilder()
					.setMessage("Session ended successfully")
					.setCode(0)
					.build();
			} else {
				response = Status.newBuilder()
					.setMessage("Failed to end session")
					.setCode(1)
					.build();
			}
			
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			System.err.println("Error ending session: " + e.getMessage());
			responseObserver.onNext(Status.newBuilder()
				.setMessage("Error: " + e.getMessage())
				.setCode(2)
				.build());
			responseObserver.onCompleted();
		}
	}
	
	// ===============================
	// WebRTC Signaling Methods
	// ===============================
	
	/**
	 * Send WebRTC signal (one-way)
	 * Used for: CALL_REQUEST, CALL_ACCEPT, CALL_REJECT, CALL_END, etc.
	 */
	@Override
	public void sendWebRTCSignal(SafeRoomProto.WebRTCSignal request, StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		try {
			String from = request.getFrom();
			String to = request.getTo();
			SafeRoomProto.WebRTCSignal.SignalType type = request.getType();
			
			System.out.printf("[WebRTC-RPC] Received signal: %s from %s to %s%n", type, from, to);
			
			// Handle different signal types
			switch (type) {
				case CALL_REQUEST:
					handleCallRequest(request, responseObserver);
					break;
					
				case CALL_ACCEPT:
					handleCallAccept(request, responseObserver);
					break;
					
				case CALL_REJECT:
				case CALL_CANCEL:
					handleCallReject(request, responseObserver);
					break;
					
				case CALL_END:
					handleCallEnd(request, responseObserver);
					break;
					
				case OFFER:
				case ANSWER:
				case ICE_CANDIDATE:
					// Forward SDP/ICE to target user
					forwardSignal(request, responseObserver);
					break;
					
				// ===============================
				// SDRT (Serverless Distributed Relay Tree) Signals
				// ===============================
				case TREE_JOIN:
					handleTreeJoin(request, responseObserver);
					break;
					
				case TREE_METRICS:
					handleTreeMetrics(request, responseObserver);
					break;
					
				case TREE_REBALANCE:
					handleTreeRebalance(request, responseObserver);
					break;
					
				default:
					responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
						.setSuccess(false)
						.setMessage("Unknown signal type")
						.build());
					responseObserver.onCompleted();
			}
			
		} catch (Exception e) {
			System.err.printf("[WebRTC-RPC] Error handling signal: %s%n", e.getMessage());
			e.printStackTrace();
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	/**
	 * Bi-directional streaming for WebRTC signaling
	 * Keeps persistent connection for real-time signaling
	 */
	@Override
	public StreamObserver<SafeRoomProto.WebRTCSignal> streamWebRTCSignals(
			StreamObserver<SafeRoomProto.WebRTCSignal> responseObserver) {
		
		return new StreamObserver<SafeRoomProto.WebRTCSignal>() {
			private String username = null;
			
			@Override
			public void onNext(SafeRoomProto.WebRTCSignal signal) {
				try {
					System.out.printf("[WebRTC-Stream] Received signal: type=%s, from=%s, to=%s%n",
						signal.getType(), signal.getFrom(), signal.getTo());
					
					// Handle REGISTRATION signal type
					if (signal.getType() == SafeRoomProto.WebRTCSignal.SignalType.REGISTRATION) {
						username = signal.getFrom();
						WebRTCSessionManager.registerSignalingStream(username, responseObserver);
						System.out.printf("[WebRTC-Stream] User registered: %s%n", username);
						return; // Don't forward registration
					}
					
					// If not registered yet, use first signal's sender as username (backward compatibility)
					if (username == null) {
						username = signal.getFrom();
						WebRTCSessionManager.registerSignalingStream(username, responseObserver);
						System.out.printf("[WebRTC-Stream] User connected (legacy): %s%n", username);
						// Continue to forward this signal since it's not a registration
					}
					
					// Forward signal to target user
					String target = signal.getTo();
					if (target == null || target.isEmpty()) {
						System.err.printf("[WebRTC-Stream] Empty target from %s - ignoring signal type: %s%n", 
							username, signal.getType());
						return; // Ignore signals with no target
					}
					
					System.out.printf("[WebRTC-Stream] Attempting to forward %s from %s to %s%n",
						signal.getType(), username, target);
					
					if (WebRTCSessionManager.hasSignalingStream(target)) {
						boolean sent = WebRTCSessionManager.sendSignalToUser(target, signal);
						if (sent) {
							System.out.printf("[WebRTC-Stream] Successfully forwarded %s: %s -> %s%n", 
								signal.getType(), username, target);
						} else {
							System.err.printf("[WebRTC-Stream] Failed to forward signal to %s%n", target);
						}
					} else {
						System.err.printf("[WebRTC-Stream] Target user not connected: %s (registered users: %s)%n", 
							target, WebRTCSessionManager.getRegisteredUsers());
					}
					
				} catch (Exception e) {
					System.err.printf("[WebRTC-Stream] Error processing signal: %s%n", e.getMessage());
					e.printStackTrace();
				}
			}
			
			@Override
			public void onError(Throwable t) {
				System.err.printf("[WebRTC-Stream] Stream error for %s: %s%n", username, t.getMessage());
				if (username != null) {
					WebRTCSessionManager.unregisterSignalingStream(username);
				}
			}
			
			@Override
			public void onCompleted() {
				System.out.printf("[WebRTC-Stream] User disconnected: %s%n", username);
				if (username != null) {
					WebRTCSessionManager.unregisterSignalingStream(username);
				}
				responseObserver.onCompleted();
			}
		};
	}
	
	// ===============================
	// Private Helper Methods
	// ===============================
	
	private void handleCallRequest(SafeRoomProto.WebRTCSignal request, 
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String caller = request.getFrom();
		String callee = request.getTo();
		
		// Check if callee is busy
		if (WebRTCSessionManager.isUserInCall(callee)) {
			System.out.printf("[WebRTC] User busy: %s%n", callee);
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("User is busy")
				.build());
			responseObserver.onCompleted();
			return;
		}
		
		// Create call session
		CallSession session = WebRTCSessionManager.createCall(
			caller, 
			callee, 
			request.getAudioEnabled(), 
			request.getVideoEnabled()
		);
		
		// Forward call request to callee
		if (WebRTCSessionManager.sendSignalToUser(callee, 
				SafeRoomProto.WebRTCSignal.newBuilder(request)
					.setCallId(session.callId)
					.build())) {
			
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(true)
				.setMessage("Call request sent")
				.setCallId(session.callId)
				.build());
		} else {
			// Callee offline or no signaling connection
			WebRTCSessionManager.endCall(session.callId);
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("User is offline")
				.build());
		}
		responseObserver.onCompleted();
	}
	
	private void handleCallAccept(SafeRoomProto.WebRTCSignal request, 
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String callId = request.getCallId();
		CallSession session = WebRTCSessionManager.getCall(callId);
		
		if (session == null) {
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Call not found")
				.build());
			responseObserver.onCompleted();
			return;
		}
		
		// Update call state
		session.state = WebRTCSessionManager.CallState.CONNECTED;
		
		// Forward acceptance to caller
		WebRTCSessionManager.sendSignalToUser(session.caller, request);
		
		responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
			.setSuccess(true)
			.setMessage("Call accepted")
			.setCallId(callId)
			.build());
		responseObserver.onCompleted();
	}
	
	private void handleCallReject(SafeRoomProto.WebRTCSignal request, 
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String callId = request.getCallId();
		CallSession session = WebRTCSessionManager.getCall(callId);
		
		if (session != null) {
			// Notify other party
			String target = request.getFrom().equals(session.caller) ? session.callee : session.caller;
			WebRTCSessionManager.sendSignalToUser(target, request);
			
			// End call session
			WebRTCSessionManager.endCall(callId);
		}
		
		responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
			.setSuccess(true)
			.setMessage("Call ended")
			.build());
		responseObserver.onCompleted();
	}
	
	private void handleCallEnd(SafeRoomProto.WebRTCSignal request, 
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String callId = request.getCallId();
		CallSession session = WebRTCSessionManager.getCall(callId);
		
		if (session != null) {
			// Notify other party
			String target = request.getFrom().equals(session.caller) ? session.callee : session.caller;
			WebRTCSessionManager.sendSignalToUser(target, request);
			
			// End call session
			WebRTCSessionManager.endCall(callId);
		}
		
		responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
			.setSuccess(true)
			.setMessage("Call ended")
			.build());
		responseObserver.onCompleted();
	}
	
	private void forwardSignal(SafeRoomProto.WebRTCSignal request, 
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String target = request.getTo();
		
		if (WebRTCSessionManager.sendSignalToUser(target, request)) {
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(true)
				.setMessage("Signal forwarded")
				.build());
		} else {
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Target user not available")
				.build());
		}
		responseObserver.onCompleted();
	}
	
	// ===============================
	// SDRT (Serverless Distributed Relay Tree) Handlers
	// ===============================
	
	/**
	 * Handle TREE_JOIN: User wants to join the tree for a room
	 */
	private void handleTreeJoin(SafeRoomProto.WebRTCSignal request,
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String userId = request.getFrom();
		String roomId = request.getRoomId();
		
		System.out.printf("[SDRT] TREE_JOIN: user=%s, room=%s%n", userId, roomId);
		
		if (roomId == null || roomId.isEmpty()) {
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Room ID required")
				.build());
			responseObserver.onCompleted();
			return;
		}
		
		try {
			// Assign parent
			String parentId = sdrtCoordinator.handleTreeJoin(roomId, userId);
			
			// Get/generate group key
			String groupKey;
			if (!groupKeyManager.hasKeyForRoom(roomId)) {
				groupKey = groupKeyManager.generateKeyForRoom(roomId);
			} else {
				groupKey = groupKeyManager.generateKeyForRoom(roomId); // Re-generate for now
			}
			
			// Determine role
			String nodeRole = parentId == null ? "ROOT" : "LEAF";
			
			// Send TREE_PARENT_ASSIGN
			SafeRoomProto.WebRTCSignal.Builder signal = SafeRoomProto.WebRTCSignal.newBuilder()
				.setType(SafeRoomProto.WebRTCSignal.SignalType.TREE_PARENT_ASSIGN)
				.setFrom("server")
				.setTo(userId)
				.setRoomId(roomId)
				.setNodeRole(nodeRole)
				.setGroupKey(groupKey);
			
			if (parentId != null) {
				signal.setParentUserId(parentId);
			}
			
			if (WebRTCSessionManager.hasSignalingStream(userId)) {
				WebRTCSessionManager.sendSignalToUser(userId, signal.build());
			}
			
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(true)
				.setMessage("Parent assigned: " + (parentId != null ? parentId : "ROOT"))
				.build());
			responseObserver.onCompleted();
			
		} catch (Exception e) {
			e.printStackTrace();
			responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
				.setSuccess(false)
				.setMessage("Error: " + e.getMessage())
				.build());
			responseObserver.onCompleted();
		}
	}
	
	/**
	 * Handle TREE_METRICS: Update node metrics
	 */
	private void handleTreeMetrics(SafeRoomProto.WebRTCSignal request,
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		String userId = request.getFrom();
		String roomId = request.getRoomId();
		String metricsJson = request.getMetricsData();
		
		if (metricsJson != null) {
			try {
				// Simple JSON parsing
				double rtt = 100.0, bandwidth = 1.0, loss = 0.0, cpu = 0.5;
				if (metricsJson.contains("rtt")) 
					rtt = Double.parseDouble(metricsJson.split("\"rtt\":")[1].split(",")[0]);
				if (metricsJson.contains("bandwidth")) 
					bandwidth = Double.parseDouble(metricsJson.split("\"bandwidth\":")[1].split(",")[0]);
				if (metricsJson.contains("loss")) 
					loss = Double.parseDouble(metricsJson.split("\"loss\":")[1].split(",")[0]);
				if (metricsJson.contains("cpu")) 
					cpu = Double.parseDouble(metricsJson.split("\"cpu\":")[1].split("}")[0]);
				
				sdrtCoordinator.updateMetrics(roomId, userId, rtt, bandwidth, loss, cpu);
			} catch (Exception e) {
				System.err.println("[SDRT] Error parsing metrics: " + e.getMessage());
			}
		}
		
		responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
			.setSuccess(true)
			.setMessage("Metrics recorded")
			.build());
		responseObserver.onCompleted();
	}
	
	/**
	 * Handle TREE_REBALANCE: Parent change request
	 */
	private void handleTreeRebalance(SafeRoomProto.WebRTCSignal request,
			StreamObserver<SafeRoomProto.WebRTCResponse> responseObserver) {
		
		responseObserver.onNext(SafeRoomProto.WebRTCResponse.newBuilder()
			.setSuccess(true)
			.setMessage("Rebalance acknowledged")
			.build());
		responseObserver.onCompleted();
	}
}
