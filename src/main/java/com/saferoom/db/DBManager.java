package com.saferoom.db;

import java.io.FileInputStream;
import java.io.IOException;

import java.util.Properties;

// import com.saferoom.p2p.*; // P2P system removed
import com.saferoom.crypto.CryptoUtils;
import com.saferoom.email.*;
import com.saferoom.log.Logger;

import java.sql.*;
import com.saferoom.rooms.grpc.RoomMetadata;

public class DBManager {

	private static final String CONFIG_FILE = "src/main/resources/dbconfig.properties";
	private static final String ICON_PATH = "src/main/resources/Verificate.png";
	private static String DB_URL;
	private static String DB_USER;
	private static String DB_PASSWORD;

	public static Logger LOGGER = Logger.getLogger(DBManager.class);

	static {
		try {
			loadDataBaseConfig();
			initRoomTables();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			try {
				LOGGER.error("DataBase Config loading failed." + e.getMessage());
				throw new RuntimeException("Database Config File Unreadable", e);
			} catch (Exception e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			e.printStackTrace();
		}
	}

	private static void loadDataBaseConfig() throws Exception {
		try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {

			Properties props = new Properties();
			props.load(fis);

			DB_URL = props.getProperty("db.url");
			DB_USER = props.getProperty("db.user");
			DB_PASSWORD = props.getProperty("db.password");

			LOGGER.info("Database config loaded successfully.");
		} catch (IOException e) {
			LOGGER.error("Database Config loaded successfully.");
			throw new RuntimeException("Database Config File Unreadable");

		}

	}

	public static Connection getConnection() throws SQLException {

		return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
	}

	public static boolean setSTUN_INFO(String myUsername) throws SQLException {
		// P2P system removed - method disabled
		System.out.println("⚠️ STUN_INFO setting disabled - P2P system removed");
		return false; // Temporarily disabled

		/*
		 * String query =
		 * "INSERT INTO STUN_INFO(Public_IP, Public_Port) VALUES(?, ?) WHERE username = (?);"
		 * ;
		 * String[] returns = MultiStunClient.StunClient();
		 * 
		 * if(returns[0].equals("true")) {
		 * System.out.println("Public IP:   " + returns[1]);
		 * System.out.println("Public Port:   " + returns[2]);
		 * System.out.println("OpenAccess? : " + returns[3]);
		 * 
		 * try(Connection contact = getConnection();
		 * PreparedStatement ptpt = contact.prepareStatement(query)){
		 * ptpt.setString(1, returns[1]);
		 * ptpt.setInt(2, Integer.parseInt(returns[2]));
		 * ptpt.setString(3, myUsername);
		 * 
		 * return ptpt.executeUpdate() > 0;
		 * }
		 * 
		 * }
		 * 
		 * return false;
		 */
	}

	public static String[] getSTUN_INFO(String username) throws SQLException {
		String query = "SELECT (Public_IP, Public_Port) FROM STUN_INFO WHERE username = (?);";
		String[] infos = new String[2];
		int port_num = 0;
		try (Connection con = getConnection();
				PreparedStatement ptpt = con.prepareStatement(query)) {
			ptpt.setString(1, username);
			ResultSet rs = ptpt.executeQuery();
			if (rs.next()) {
				port_num = rs.getInt("Public_Port");
				infos[0] = rs.getString("Public_IP");
				infos[1] = Integer.toString(port_num);
			}
			return infos;
		}

	}

	public static String getEmailByUsername(String username) throws SQLException {
		String query = "SELECT email FROM users WHERE username = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getString("email");
			} else {
				return null;
			}
		}
	}

	public static String getUsernameByEmail(String email) throws SQLException {
		String query = "SELECT username FROM users WHERE email = (?);";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, email);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getString("username");
			} else {
				return null;
			}
		}
	}

	public static boolean check_email(String mail) throws SQLException {
		String query = "SELECT COUNT(*) username FROM users WHERE email = (?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, mail);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getInt(1) > 0;
			}
			return false;
		}
	}

	public static String return_usersname_from_email(String mail) throws SQLException {
		String query = "SELECT username FROM users WHERE email = (?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, mail);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getString(1);
			}
		}
		return null;
	}

	public static boolean change_verification_code(String username, String new_code) throws SQLException {
		String query = "UPDATE users SET verification_code = (?) WHERE username = (?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, new_code);
			stmt.setString(2, username);

			if (stmt.executeUpdate() > 0) {
				return true;
			}
		}
		return false;
	}

	public static boolean userExists(String usernameOrEmail) throws SQLException {
		String query = "SELECT COUNT(*) FROM users WHERE username = (?) OR email = (?)";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, usernameOrEmail);
			stmt.setString(2, usernameOrEmail);
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getInt(1) > 0;
			}
			return false;
		}

	}

	public static boolean createUser(String username, String rawPassword, String email) throws Exception {
		String salt = CryptoUtils.generateSalt();
		String hashedPassword = CryptoUtils.hashPasswordWithSalt(rawPassword, salt);

		String query = "INSERT INTO users(username, password_hash, salt, email) VALUES (?, ?, ?, ?);";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			stmt.setString(2, hashedPassword);
			stmt.setString(3, salt);
			stmt.setString(4, email);

			if (stmt.executeUpdate() > 0) {
				LOGGER.info("Yeni kullanıcı oluşturuldu: " + username);
				return true;
			} else {
				LOGGER.warn("Kullanıcı oluşturulamadı: " + username);
				return false;
			}

		}
	}

	public static boolean change_users_password(String usernameOrEmail, String rawPassword) throws Exception {
		String salt = CryptoUtils.generateSalt();
		String hashedPassword = CryptoUtils.hashPasswordWithSalt(rawPassword, salt);

		String query = "UPDATE users SET password_hash = (?), salt = (?), last_login = NOW() WHERE username = (?) OR email = (?)";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, hashedPassword);
			stmt.setString(2, salt);
			stmt.setString(3, usernameOrEmail);
			stmt.setString(4, usernameOrEmail);

			int rowsUpdated = stmt.executeUpdate();

			if (rowsUpdated > 0) {
				LOGGER.info("Password successfully changed for user: " + usernameOrEmail);

				resetLoginAttempts(usernameOrEmail);

				return true;
			} else {
				LOGGER.warn("Password change failed - user not found: " + usernameOrEmail);
				return false;
			}
		} catch (SQLException e) {
			LOGGER.error("Database error during password change: " + e.getMessage());
			throw new Exception("Failed to change password: " + e.getMessage(), e);
		}
	}

	public static boolean isUserBlocked(String usernameOrEmail) throws Exception {
		String query = "SELECT username FROM blocked_users WHERE username =(?) OR username = (SELECT username FROM users WHERE email = (?));";
		try (Connection con = getConnection();
				PreparedStatement st = con.prepareStatement(query)) {
			st.setString(1, usernameOrEmail);
			st.setString(2, usernameOrEmail);

			ResultSet rs = st.executeQuery();

			if (rs.next()) {
				return true;
			}
			return false;

		}
	}

	public static boolean blockUser(String username, String ipAddress) throws SQLException {
		String query = "INSERT INTO blocked_users (username, blocked_at, reason, ip_address) " +
				"VALUES (?, CURRENT_TIMESTAMP, 'Too many failed attempts', ?) " +
				"ON DUPLICATE KEY UPDATE blocked_at = CURRENT_TIMESTAMP, reason = 'Too many failed attempts', ip_address = ?";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, username);
			stmt.setString(2, ipAddress);
			stmt.setString(3, ipAddress);
			return stmt.executeUpdate() > 0;
		}
	}

	public static boolean unblockUser(String username) throws Exception {
		String query = "DELETE FROM blocked_users WHERE username = (?);";

		try (Connection con = getConnection();
				PreparedStatement st = con.prepareStatement(query)) {

			st.setString(1, username);

			return st.executeUpdate() > 0;

		}
	}

	public static void setVerificationCode(String username, String code) throws SQLException {
		String query = "UPDATE users SET verification_code = ? WHERE username = ?";
		try (Connection contact = getConnection();
				PreparedStatement ptpt = contact.prepareStatement(query)) {
			ptpt.setString(1, code);
			ptpt.setString(2, username);
			ptpt.executeUpdate();
		}
	}

	public static String getVerificationCode(String usernameOrEmail) throws SQLException {
		String query = "SELECT verification_code FROM users WHERE username = (?) OR email = (?)";
		String code = null;

		try (Connection contact = getConnection();
				PreparedStatement ptpt = contact.prepareStatement(query)) {
			ptpt.setString(1, usernameOrEmail);
			ptpt.setString(2, usernameOrEmail);

			try (ResultSet rs = ptpt.executeQuery()) {

				if (rs.next()) {
					code = rs.getString("verification_code");
					return code;

				}
				return "Method failed";

			}
		}
	}

	public static boolean Verify(String usernameOrEmail) throws SQLException {

		String query = "UPDATE users SET is_verified = TRUE WHERE username = (?) OR email = (?)";

		try (Connection contact = getConnection();
				PreparedStatement ptpt = contact.prepareStatement(query)) {

			ptpt.setString(1, usernameOrEmail);
			ptpt.setString(2, usernameOrEmail);

			return ptpt.executeUpdate() > 0;
		}

	}

	public static boolean updateLastLogin(String usernameOrEmail) throws SQLException {
		// Önce gerçek username'i bul
		String actualUsername = getUsernameFromUsernameOrEmail(usernameOrEmail);

		String query = "UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE username = ?";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, actualUsername);

			return stmt.executeUpdate() > 0;
		}
	}

	public static boolean resetLoginAttempts(String usernameOrEmail) throws SQLException {
		// Önce gerçek username'i bul (email girilmişse)
		String actualUsername = getUsernameFromUsernameOrEmail(usernameOrEmail);

		String query = "DELETE FROM login_attempts WHERE username = ?";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, actualUsername);
			return stmt.executeUpdate() >= 0; // 0 da olabilir (kayıt yoksa)
		}
	}

	public static boolean updateLoginAttempts(String usernameOrEmail) throws SQLException {
		// Önce gerçek username'i bul (email girilmişse)
		String actualUsername = getUsernameFromUsernameOrEmail(usernameOrEmail);

		String query = "INSERT INTO login_attempts (username, attempt_count, last_attempt) " +
				"VALUES (?, 1, CURRENT_TIMESTAMP) " +
				"ON DUPLICATE KEY UPDATE attempt_count = attempt_count + 1, last_attempt = CURRENT_TIMESTAMP";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, actualUsername);

			boolean updated = stmt.executeUpdate() > 0;

			String checkQuery = "SELECT attempt_count FROM login_attempts WHERE username = ?";

			try (PreparedStatement checkStmt = conn.prepareStatement(checkQuery)) {
				checkStmt.setString(1, actualUsername);
				ResultSet rs = checkStmt.executeQuery();
				if (rs.next() && rs.getInt("attempt_count") >= 5) {
					blockUser(actualUsername, "0.0.0.0");
				}
			}
			return updated;
		}
	}

	/**
	 * Username veya email'den gerçek username'i döndürür
	 */
	private static String getUsernameFromUsernameOrEmail(String usernameOrEmail) throws SQLException {
		String query = "SELECT username FROM users WHERE username = ? OR email = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, usernameOrEmail);
			stmt.setString(2, usernameOrEmail);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getString("username");
			}
			return usernameOrEmail; // Fallback
		}
	}

	public static boolean updateVerificationAttempts(String usernameOrEmail) throws Exception {
		// Önce gerçek username'i bul (email girilmişse)
		String actualUsername = getUsernameFromUsernameOrEmail(usernameOrEmail);

		String selectQuery = "SELECT attempts, last_attempt FROM verification_attempts WHERE username = ?;";
		String insertQuery = "INSERT INTO verification_attempts (username, attempts, last_attempt) VALUES (?, 1, CURRENT_TIMESTAMP) "
				+
				"ON DUPLICATE KEY UPDATE attempts = attempts + 1, last_attempt = CURRENT_TIMESTAMP;";
		String blockQuery = "UPDATE users SET is_blocked = TRUE WHERE username = ?;";

		try (Connection conn = getConnection()) {

			try (PreparedStatement selectStmt = conn.prepareStatement(selectQuery)) {
				selectStmt.setString(1, actualUsername);
				ResultSet rs = selectStmt.executeQuery();

				int attempts = 0;
				long lastAttemptMillis = 0;

				if (rs.next()) {
					attempts = rs.getInt("attempts");
					lastAttemptMillis = rs.getTimestamp("last_attempt").getTime();
				}
				long now = System.currentTimeMillis();
				if ((now - lastAttemptMillis) >= 5 * 60 * 1000) {
					attempts = 0;
				}

				if (attempts + 1 >= 3) {
					try (PreparedStatement blockStmt = conn.prepareStatement(blockQuery)) {
						blockStmt.setString(1, actualUsername);
						blockStmt.executeUpdate();
					}

					EmailSender.notifyAccountLock(actualUsername);
					return false;
				}

				try (PreparedStatement insertStmt = conn.prepareStatement(insertQuery)) {
					insertStmt.setString(1, actualUsername);
					insertStmt.executeUpdate();

					if (checkGlobalAnomaly("LOGIN")) {
						LOGGER.warn("Global Brute Force Attempt Detected! Temporarily blocking all verification.");
						Thread.sleep(10000);
					}

				}

				return true;
			}
		}
	}

	public static void notifyAccountLock(String username) throws Exception {
		String email = DBManager.getEmailByUsername(username);
		String subject = "Urgent: Your SafeRoom Account has been Locked";
		String message = "Dear " + username + ",\n\n"
				+ "Due to multiple incorrect verification attempts, your account has been temporarily locked.\n"
				+ "If this was not you, please contact SafeRoom Security Team immediately.\n\n"
				+ "Regards,\nSafeRoom Security Team";

		EmailSender.sendEmail(email, subject, message, ICON_PATH);
	}

	public static boolean checkGlobalAnomaly(String actionType) throws Exception {
		String table = "";
		int threshold = 500;

		switch (actionType) {
			case "LOGIN":
				table = "login_attempts";
				threshold = 500;
				break;
			case "REGISTER":
				table = "registration_attempts";
				threshold = 300;
				break;
			case "VERIFY":
				table = "verification_attempts";
				threshold = 200;
				break;
			default:
				throw new IllegalArgumentException("Unknown actionType: " + actionType);
		}

		String query = "SELECT COUNT(*) FROM " + table
				+ " WHERE TIMESTAMPDIFF(SECOND, last_attempt, CURRENT_TIMESTAMP) = 0;";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				int count = rs.getInt(1);
				if (count >= threshold) {
					LOGGER.warn("Global Anomaly Detected in " + actionType + " - Count: " + count);
					return true;
				}
			}
		}
		return false;
	}

	public static boolean checkVerificationCode(String username, String code) throws Exception {

		String query = "SELECT verification_code FROM users WHERE username = (?);";
		String update = "UPDATE users SET is_verified = TRUE WHERE username = (?);";

		try (Connection con = getConnection();
				PreparedStatement prpr = con.prepareStatement(query)) {

			prpr.setString(1, username);
			ResultSet rs = prpr.executeQuery();
			if (rs.next()) {
				String verificationCode = rs.getString("verification_code");
				if (code.equals(verificationCode)) {
					try (PreparedStatement updateStmt = con.prepareStatement(update)) {
						updateStmt.setString(1, username);
						return updateStmt.executeUpdate() > 0;
					}
				} else {
					return false;
				}
			} else {
				return false;
			}
		}
	}

	public static boolean verifyPassword(String usernameOrEmail, String plainPassword) throws Exception {

		String query = "SELECT username, salt, password_hash FROM users WHERE username = (?) OR email = (?)";

		try (Connection conn = getConnection();
				PreparedStatement prpstmt = conn.prepareStatement(query)) {

			prpstmt.setString(1, usernameOrEmail);
			prpstmt.setString(2, usernameOrEmail);

			ResultSet rsm = prpstmt.executeQuery();
			if (rsm.next()) {

				String stored_password = rsm.getString("password_hash");
				String salt = rsm.getString("salt");

				String users_hashed_password = CryptoUtils.hashPasswordWithSalt(plainPassword, salt);

				return CryptoUtils.constantTimeEquals(stored_password, users_hashed_password);

			} else {
				CryptoUtils.hashPasswordWithSalt(plainPassword, CryptoUtils.generateSalt());// Fake Hash
																							// Creator.(Constant Time
																							// Verification )
				return false;
			}

		}

	}

	/**
	 * Kullanıcı arama metodu - username veya email'e göre arama yapar
	 */
	public static java.util.List<java.util.Map<String, Object>> searchUsers(String searchTerm, String currentUser,
			int limit) throws SQLException {
		String query = """
					SELECT u.username, u.email, u.last_login, u.is_verified,
					       CASE WHEN f.user1 IS NOT NULL THEN TRUE ELSE FALSE END as is_friend,
					       CASE WHEN fr.id IS NOT NULL THEN TRUE ELSE FALSE END as has_pending_request
					FROM users u
					LEFT JOIN friendships f ON (f.user1 = ? AND f.user2 = u.username) OR (f.user2 = ? AND f.user1 = u.username)
					LEFT JOIN friend_requests fr ON fr.sender = ? AND fr.receiver = u.username AND fr.status = 'pending'
					WHERE (u.username LIKE ? OR u.email LIKE ?)
					AND u.username != ?
					AND u.is_verified = TRUE
					ORDER BY
						CASE WHEN u.username LIKE ? THEN 1 ELSE 2 END,
						u.last_login DESC
					LIMIT ?
				""";

		java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
		String searchPattern = "%" + searchTerm + "%";
		String exactPattern = searchTerm + "%";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, currentUser); // f.user1 = ?
			stmt.setString(2, currentUser); // f.user2 = ?
			stmt.setString(3, currentUser); // fr.sender = ?
			stmt.setString(4, searchPattern);
			stmt.setString(5, searchPattern);
			stmt.setString(6, currentUser);
			stmt.setString(7, exactPattern);
			stmt.setInt(8, limit);

			System.out.println("🔍 SearchUsers SQL Debug:");
			System.out.println("  - currentUser: " + currentUser);
			System.out.println("  - searchPattern: " + searchPattern);

			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				java.util.Map<String, Object> user = new java.util.HashMap<>();
				user.put("username", rs.getString("username"));
				user.put("email", rs.getString("email"));
				user.put("lastLogin", rs.getTimestamp("last_login"));
				user.put("isVerified", rs.getBoolean("is_verified"));
				user.put("is_friend", rs.getBoolean("is_friend"));
				user.put("has_pending_request", rs.getBoolean("has_pending_request"));

				System.out.println("  - Found user: " + rs.getString("username"));
				System.out.println("    - is_friend from DB: " + rs.getBoolean("is_friend"));
				System.out.println("    - has_pending_request from DB: " + rs.getBoolean("has_pending_request"));

				results.add(user);
			}
		}
		return results;
	}
	// ===============================
	// PROFILE & FRIEND SYSTEM METHODS
	// ===============================

	/**
	 * Kullanıcı profil bilgilerini getir
	 */
	public static java.util.Map<String, Object> getUserProfile(String username, String requestedBy)
			throws SQLException {
		String query = """
					SELECT u.username, u.email, u.last_login, u.is_verified,
						   COALESCE(s.rooms_created, 0) as rooms_created,
						   COALESCE(s.rooms_joined, 0) as rooms_joined,
						   COALESCE(s.files_shared, 0) as files_shared,
						   COALESCE(s.messages_sent, 0) as messages_sent,
						   COALESCE(s.activity_score, 0.0) as activity_score
					FROM users u
					LEFT JOIN user_stats s ON u.username = s.username
					WHERE u.username = ? AND u.is_verified = TRUE
				""";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				java.util.Map<String, Object> profile = new java.util.HashMap<>();
				profile.put("username", rs.getString("username"));
				profile.put("email", rs.getString("email"));
				profile.put("joinDate", rs.getTimestamp("last_login")); // last_login'i joinDate olarak kullan
				profile.put("lastSeen", rs.getTimestamp("last_login"));
				profile.put("isVerified", rs.getBoolean("is_verified"));

				// Stats
				java.util.Map<String, Object> stats = new java.util.HashMap<>();
				stats.put("roomsCreated", rs.getInt("rooms_created"));
				stats.put("roomsJoined", rs.getInt("rooms_joined"));
				stats.put("filesShared", rs.getInt("files_shared"));
				stats.put("messagesSent", rs.getInt("messages_sent"));
				stats.put("activityScore", rs.getDouble("activity_score"));
				profile.put("stats", stats);

				// Friend status
				profile.put("friendStatus", getFriendshipStatus(requestedBy, username));

				// Recent activities
				profile.put("activities", getUserActivities(username, 5));

				return profile;
			}
		}
		return null;
	}

	/**
	 * Kullanıcının son aktivitelerini getir
	 */
	public static java.util.List<java.util.Map<String, Object>> getUserActivities(String username, int limit)
			throws SQLException {
		String query = """
					SELECT activity_type, activity_description, created_at, activity_data
					FROM user_activities
					WHERE username = ?
					ORDER BY created_at DESC
					LIMIT ?
				""";

		java.util.List<java.util.Map<String, Object>> activities = new java.util.ArrayList<>();

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			stmt.setInt(2, limit);

			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				java.util.Map<String, Object> activity = new java.util.HashMap<>();
				activity.put("activityType", rs.getString("activity_type"));
				activity.put("description", rs.getString("activity_description"));
				activity.put("timestamp", rs.getTimestamp("created_at"));
				activity.put("activityData", rs.getString("activity_data"));
				activities.add(activity);
			}
		}
		return activities;
	}

	/**
	 * İki kullanıcı arasındaki arkadaşlık durumunu kontrol et
	 */
	public static String getFriendshipStatus(String user1, String user2) throws SQLException {
		if (user1 == null || user2 == null || user1.equals(user2)) {
			return "none";
		}

		// Blocked check
		String blockQuery = "SELECT 1 FROM blocked_users WHERE username = ? OR username = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(blockQuery)) {
			stmt.setString(1, user1);
			stmt.setString(2, user2);

			if (stmt.executeQuery().next()) {
				return "blocked";
			}
		}

		// Friend check - CHECK constraint kaldırıldı, her iki yönde ara
		String friendQuery = "SELECT 1 FROM friendships WHERE (user1 = ? AND user2 = ?) OR (user1 = ? AND user2 = ?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(friendQuery)) {
			stmt.setString(1, user1);
			stmt.setString(2, user2);
			stmt.setString(3, user2);
			stmt.setString(4, user1);
			if (stmt.executeQuery().next()) {
				return "friends";
			}
		}

		// Pending request check
		String pendingQuery = "SELECT sender FROM friend_requests WHERE ((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?)) AND status = 'pending'";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(pendingQuery)) {
			stmt.setString(1, user1);
			stmt.setString(2, user2);
			stmt.setString(3, user2);
			stmt.setString(4, user1);

			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				String sender = rs.getString("sender");
				return sender.equals(user1) ? "request_sent" : "request_received";
			}
		}

		return "none";
	}

	/**
	 * Arkadaşlık isteği gönder
	 */
	public static boolean sendFriendRequest(String sender, String receiver, String message) throws SQLException {
		// Self request check
		if (sender.equals(receiver)) {
			return false;
		}

		// Existing request check
		String checkQuery = "SELECT status FROM friend_requests WHERE ((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?))";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(checkQuery)) {
			stmt.setString(1, sender);
			stmt.setString(2, receiver);
			stmt.setString(3, receiver);
			stmt.setString(4, sender);

			if (stmt.executeQuery().next()) {
				return false; // Already exists
			}
		}

		// Check if already friends
		if ("friends".equals(getFriendshipStatus(sender, receiver))) {
			return false;
		}

		// Check if blocked
		if ("blocked".equals(getFriendshipStatus(sender, receiver))) {
			return false;
		}

		// Insert friend request
		String insertQuery = "INSERT INTO friend_requests (sender, receiver, message, status) VALUES (?, ?, ?, 'pending')";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
			stmt.setString(1, sender);
			stmt.setString(2, receiver);
			stmt.setString(3, message);

			int affected = stmt.executeUpdate();

			// Log activity
			if (affected > 0) {
				logUserActivity(sender, "friend_request_sent", "Sent friend request to " + receiver, null);
				logUserActivity(receiver, "friend_request_received", "Received friend request from " + sender, null);
			}

			return affected > 0;
		}
	}

	/**
	 * Kullanıcı aktivitesi kaydet
	 */
	/**
	 * Kullanıcı aktivitesini logla - Gizlilik odaklı, detay bilgi yok
	 */
	public static void logUserActivity(String username, String activityType, String description, String activityData) {
		// Gizlilik için detay bilgileri kaydetme - sadece genel aktivite türü
		String privacyFriendlyDescription = getPrivacyFriendlyDescription(activityType);

		String query = "INSERT INTO user_activities (username, activity_type, activity_description) VALUES (?, ?, ?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, username);
			stmt.setString(2, activityType);
			stmt.setString(3, privacyFriendlyDescription);
			stmt.executeUpdate();
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	/**
	 * Aktivite türü için gizlilik dostu açıklama
	 */
	private static String getPrivacyFriendlyDescription(String activityType) {
		return switch (activityType) {
			case "room_created" -> "Created a secure room";
			case "room_joined" -> "Joined a room";
			case "file_shared" -> "Shared a file";
			case "message_sent" -> "Sent a message";
			case "login" -> "Logged in";
			case "logout" -> "Logged out";
			case "friend_request_sent" -> "Sent a friend request";
			case "friend_request_received" -> "Received a friend request";
			default -> "Activity performed";
		};
	}

	/**
	 * Kullanıcı istatistiklerini güncelle
	 */
	public static void updateUserStats(String username, String statType, int increment) {
		String query = """
					INSERT INTO user_stats (username, %s) VALUES (?, ?)
					ON DUPLICATE KEY UPDATE %s = %s + ?, last_updated = CURRENT_TIMESTAMP
				""".formatted(statType, statType, statType);

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, username);
			stmt.setInt(2, increment);
			stmt.setInt(3, increment);
			stmt.executeUpdate();
		} catch (SQLException e) {
			System.out.println("Failed to update user stats: " + e.getMessage());
		}
	}

	/**
	 * Güvenlik skorunu hesapla ve güncelle
	 */
	public static void updateSecurityScore(String username) {
		try {
			java.util.Map<String, Object> stats = getUserProfile(username, username);
			if (stats != null) {
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> userStats = (java.util.Map<String, Object>) stats.get("stats");

				int roomsCreated = (Integer) userStats.get("roomsCreated");
				int filesShared = (Integer) userStats.get("filesShared");
				int messagesCount = (Integer) userStats.get("messagesSent");

				// Simple security score calculation
				double score = Math.min(100.0, (roomsCreated * 10) + (filesShared * 2) + (messagesCount * 0.1));

				String updateQuery = "UPDATE user_stats SET security_score = ? WHERE username = ?";
				try (Connection conn = getConnection();
						PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
					stmt.setDouble(1, score);
					stmt.setString(2, username);
					stmt.executeUpdate();
				}
			}
		} catch (SQLException e) {
			System.out.println("Failed to update security score: " + e.getMessage());
		}
	}
	// ...existing code...

	/**
	 * Kullanıcının bekleyen arkadaşlık isteklerini getir (gelen istekler)
	 */
	public static java.util.List<java.util.Map<String, Object>> getPendingFriendRequests(String username)
			throws SQLException {
		String query = """
				    SELECT fr.id, fr.sender, fr.message, fr.created_at, u.email, u.last_login
				    FROM friend_requests fr
				    JOIN users u ON fr.sender = u.username
				    WHERE fr.receiver = ? AND fr.status = 'pending'
				    ORDER BY fr.created_at DESC
				""";

		java.util.List<java.util.Map<String, Object>> requests = new java.util.ArrayList<>();

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				java.util.Map<String, Object> request = new java.util.HashMap<>();
				request.put("requestId", rs.getInt("id"));
				request.put("sender", rs.getString("sender"));
				request.put("message", rs.getString("message"));
				request.put("sentAt", rs.getTimestamp("created_at"));
				request.put("senderEmail", rs.getString("email"));
				request.put("senderLastSeen", rs.getTimestamp("last_login"));
				requests.add(request);
			}
		}
		return requests;
	}

	/**
	 * Kullanıcının gönderdiği arkadaşlık isteklerini getir (giden istekler)
	 */
	public static java.util.List<java.util.Map<String, Object>> getSentFriendRequests(String username)
			throws SQLException {
		String query = """
				    SELECT fr.id, fr.receiver, fr.message, fr.created_at, fr.status, u.email, u.last_login
				    FROM friend_requests fr
				    JOIN users u ON fr.receiver = u.username
				    WHERE fr.sender = ? AND fr.status = 'pending'
				    ORDER BY fr.created_at DESC
				""";

		java.util.List<java.util.Map<String, Object>> requests = new java.util.ArrayList<>();

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				java.util.Map<String, Object> request = new java.util.HashMap<>();
				request.put("requestId", rs.getInt("id"));
				request.put("receiver", rs.getString("receiver"));
				request.put("message", rs.getString("message"));
				request.put("sentAt", rs.getTimestamp("created_at"));
				request.put("status", rs.getString("status"));
				request.put("receiverEmail", rs.getString("email"));
				request.put("receiverLastSeen", rs.getTimestamp("last_login"));
				requests.add(request);
			}
		}
		return requests;
	}

	/**
	 * Arkadaşlık isteğini kabul et
	 */
	public static boolean acceptFriendRequest(int requestId, String receiver) throws SQLException {
		String selectQuery = "SELECT sender, receiver FROM friend_requests WHERE id = ? AND receiver = ? AND status = 'pending'";
		String updateQuery = "UPDATE friend_requests SET status = 'accepted', responded_at = CURRENT_TIMESTAMP WHERE id = ?";
		String insertFriendshipQuery = "INSERT INTO friendships (user1, user2, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)";

		try (Connection conn = getConnection()) {
			conn.setAutoCommit(false); // Transaction başlat

			try {
				// İsteği kontrol et
				String sender = null;
				try (PreparedStatement stmt = conn.prepareStatement(selectQuery)) {
					stmt.setInt(1, requestId);
					stmt.setString(2, receiver);

					ResultSet rs = stmt.executeQuery();
					if (rs.next()) {
						sender = rs.getString("sender");
					} else {
						return false; // İstek bulunamadı
					}
				}

				// İsteği kabul edildi olarak işaretle
				try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
					stmt.setInt(1, requestId);
					stmt.executeUpdate();
				}

				// Arkadaşlık kaydı oluştur - CHECK constraint kaldırıldı, sıralama gereksiz
				String user1 = sender;
				String user2 = receiver;

				System.out.println("🔍 DEBUG: Adding friendship: " + user1 + " <-> " + user2);

				try (PreparedStatement stmt = conn.prepareStatement(insertFriendshipQuery)) {
					stmt.setString(1, user1);
					stmt.setString(2, user2);
					stmt.executeUpdate();
				}

				// Aktivite kayıtları
				logUserActivity(sender, "friend_request_accepted", "Friend request accepted by " + receiver, null);
				logUserActivity(receiver, "friend_added", "Added " + sender + " as friend", null);

				conn.commit(); // Transaction'ı tamamla
				return true;

			} catch (SQLException e) {
				conn.rollback(); // Hata durumunda geri al
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
		}
	}

	/**
	 * Arkadaşlık isteğini reddet
	 */
	public static boolean rejectFriendRequest(int requestId, String receiver) throws SQLException {
		String selectQuery = "SELECT sender FROM friend_requests WHERE id = ? AND receiver = ? AND status = 'pending'";
		String updateQuery = "UPDATE friend_requests SET status = 'rejected', responded_at = CURRENT_TIMESTAMP WHERE id = ?";

		try (Connection conn = getConnection()) {
			// İsteği kontrol et
			String sender = null;
			try (PreparedStatement stmt = conn.prepareStatement(selectQuery)) {
				stmt.setInt(1, requestId);
				stmt.setString(2, receiver);

				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					sender = rs.getString("sender");
				} else {
					return false; // İstek bulunamadı
				}
			}

			// İsteği reddet
			try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
				stmt.setInt(1, requestId);
				int affected = stmt.executeUpdate();

				if (affected > 0) {
					// Aktivite kayıtları
					logUserActivity(sender, "friend_request_rejected", "Friend request rejected by " + receiver, null);
					logUserActivity(receiver, "friend_request_rejected", "Rejected friend request from " + sender,
							null);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Gönderilen arkadaşlık isteğini iptal et
	 */
	public static boolean cancelFriendRequest(int requestId, String sender) throws SQLException {
		String deleteQuery = "DELETE FROM friend_requests WHERE id = ? AND sender = ? AND status = 'pending'";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(deleteQuery)) {

			stmt.setInt(1, requestId);
			stmt.setString(2, sender);

			int affected = stmt.executeUpdate();
			if (affected > 0) {
				logUserActivity(sender, "friend_request_cancelled", "Cancelled a friend request", null);
				return true;
			}
		}
		return false;
	}

	/**
	 * Kullanıcının arkadaş listesini getir
	 */
	public static java.util.List<java.util.Map<String, Object>> getFriendsList(String username) throws SQLException {
		String query = """
				    SELECT
				        CASE
				            WHEN f.user1 = ? THEN f.user2
				            ELSE f.user1
				        END as friend_username,
				        f.created_at as friendship_date,
				        u.email, u.last_login, u.is_verified
				    FROM friendships f
				    JOIN users u ON (
				        CASE
				            WHEN f.user1 = ? THEN f.user2 = u.username
				            ELSE f.user1 = u.username
				        END
				    )
				    WHERE f.user1 = ? OR f.user2 = ?
				    ORDER BY f.created_at DESC
				""";

		java.util.List<java.util.Map<String, Object>> friends = new java.util.ArrayList<>();

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			stmt.setString(2, username);
			stmt.setString(3, username);
			stmt.setString(4, username);

			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				String friendUsername = rs.getString("friend_username");
				java.util.Map<String, Object> friend = new java.util.HashMap<>();
				friend.put("username", friendUsername);
				friend.put("email", rs.getString("email"));
				friend.put("friendshipDate", rs.getTimestamp("friendship_date"));
				friend.put("lastSeen", rs.getTimestamp("last_login"));
				friend.put("isVerified", rs.getBoolean("is_verified"));

				// Online durumunu kontrol et
				try {
					friend.put("isOnline", isUserOnline(friendUsername));
				} catch (SQLException e) {
					friend.put("isOnline", false); // Hata durumunda offline olarak kabul et
				}

				friends.add(friend);
			}
		}
		return friends;
	}

	/**
	 * Arkadaşlığı sonlandır
	 */
	public static boolean removeFriend(String user1, String user2) throws SQLException {
		String deleteQuery = "DELETE FROM friendships WHERE (user1 = ? AND user2 = ?) OR (user1 = ? AND user2 = ?)";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(deleteQuery)) {

			System.out.println("🔍 DEBUG removeFriend: " + user1 + " <-> " + user2);

			stmt.setString(1, user1);
			stmt.setString(2, user2);
			stmt.setString(3, user2);
			stmt.setString(4, user1);

			int affected = stmt.executeUpdate();
			if (affected > 0) {
				logUserActivity(user1, "friend_removed", "Removed " + user2 + " from friends", null);
				logUserActivity(user2, "friend_removed", "Removed by " + user1 + " from friends", null);
				return true;
			}
		}
		return false;
	}

	/**
	 * Arkadaşlık istatistiklerini getir
	 */
	public static java.util.Map<String, Object> getFriendshipStats(String username) throws SQLException {
		java.util.Map<String, Object> stats = new java.util.HashMap<>();

		try (Connection conn = getConnection()) {
			// Toplam arkadaş sayısı
			String friendCountQuery = "SELECT COUNT(*) FROM friendships WHERE user1 = ? OR user2 = ?";
			try (PreparedStatement stmt = conn.prepareStatement(friendCountQuery)) {
				stmt.setString(1, username);
				stmt.setString(2, username);
				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					stats.put("totalFriends", rs.getInt(1));
				}
			}

			// Bekleyen gelen istekler
			String pendingInQuery = "SELECT COUNT(*) FROM friend_requests WHERE receiver = ? AND status = 'pending'";
			try (PreparedStatement stmt = conn.prepareStatement(pendingInQuery)) {
				stmt.setString(1, username);
				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					stats.put("pendingRequests", rs.getInt(1));
				}
			}

			// Bekleyen giden istekler
			String pendingOutQuery = "SELECT COUNT(*) FROM friend_requests WHERE sender = ? AND status = 'pending'";
			try (PreparedStatement stmt = conn.prepareStatement(pendingOutQuery)) {
				stmt.setString(1, username);
				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					stats.put("sentRequests", rs.getInt(1));
				}
			}
		}

		return stats;
	}

	// ===============================
	// HEARTBEAT & ONLINE STATUS METHODS
	// ===============================

	/**
	 * Kullanıcının heartbeat'ini güncelle
	 */
	public static boolean updateHeartbeat(String username, String sessionId) throws SQLException {
		// First ensure user exists in users table (quick fix for foreign key
		// constraint)
		String checkUserQuery = "SELECT COUNT(*) FROM users WHERE username = ?";
		String insertUserQuery = "INSERT IGNORE INTO users (username, email, password_hash, salt) VALUES (?, ?, 'temp_hash', 'temp_salt')";

		String sessionQuery = """
				    INSERT INTO user_sessions (username, session_id, last_heartbeat)
				    VALUES (?, ?, CURRENT_TIMESTAMP)
				    ON DUPLICATE KEY UPDATE
				        last_heartbeat = CURRENT_TIMESTAMP
				""";

		try (Connection conn = getConnection()) {
			// Check if user exists
			try (PreparedStatement checkStmt = conn.prepareStatement(checkUserQuery)) {
				checkStmt.setString(1, username);
				ResultSet rs = checkStmt.executeQuery();
				if (rs.next() && rs.getInt(1) == 0) {
					// User doesn't exist, create placeholder
					try (PreparedStatement insertStmt = conn.prepareStatement(insertUserQuery)) {
						insertStmt.setString(1, username);
						insertStmt.setString(2, username + "@temp.com");
						insertStmt.executeUpdate();
						System.out.println("🔧 Created placeholder user: " + username);
					}
				}
			}

			// Now update heartbeat
			try (PreparedStatement stmt = conn.prepareStatement(sessionQuery)) {
				stmt.setString(1, username);
				stmt.setString(2, sessionId);
				return stmt.executeUpdate() > 0;
			}
		}
	}

	/**
	 * Kullanıcının online durumunu kontrol et (son 30 saniye)
	 */
	public static boolean isUserOnline(String username) throws SQLException {
		String query = """
				    SELECT COUNT(*) FROM user_sessions
				    WHERE username = ?
				    AND last_heartbeat >= DATE_SUB(NOW(), INTERVAL 30 SECOND)
				""";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getInt(1) > 0;
			}
		}
		return false;
	}

	/**
	 * Eski session'ları temizle (5 dakika önce)
	 */
	public static void cleanupOldSessions() throws SQLException {
		String query = """
				    DELETE FROM user_sessions
				    WHERE last_heartbeat < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
				""";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			int deleted = stmt.executeUpdate();
			if (deleted > 0) {
				System.out.println("🧹 Cleaned up " + deleted + " old sessions");
			}
		}
	}

	/**
	 * Kullanıcının session'ını sonlandır
	 */
	public static boolean endUserSession(String username, String sessionId) throws SQLException {
		String query = "DELETE FROM user_sessions WHERE username = ? AND session_id = ?";

		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setString(1, username);
			stmt.setString(2, sessionId);

			return stmt.executeUpdate() > 0;
		}
	}

	// ...existing code...

	// ===============================
	// ROOMS v1 PERSISTENCE METHODS
	// ===============================

	/**
	 * Initialize tables for Rooms v1 if they don't exist.
	 * Called statically or on demand.
	 */
	public static void initRoomTables() {
		String createRooms = """
				    CREATE TABLE IF NOT EXISTS rooms (
				        room_id VARCHAR(255) PRIMARY KEY,
				        name VARCHAR(255) NOT NULL,
				        owner_username VARCHAR(50) NOT NULL,
				        is_private BOOLEAN DEFAULT FALSE,
				        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
				                    FOREIGN KEY (owner_username) REFERENCES users(username) ON DELETE CASCADE
				    );
				""";

		String createMembers = """
				    CREATE TABLE IF NOT EXISTS room_members (
				        room_id VARCHAR(255) NOT NULL,
				        username VARCHAR(50) NOT NULL,
				        role VARCHAR(50) DEFAULT 'MEMBER',
				        joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
				        PRIMARY KEY (room_id, username),
				        FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
				                    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
				    );
				""";

		// Sprint 11: Channel persistence
		String createChannels = """
				    CREATE TABLE IF NOT EXISTS room_channels (
				        channel_id VARCHAR(255) PRIMARY KEY,
				        room_id VARCHAR(255) NOT NULL,
				        name VARCHAR(255) NOT NULL,
				        type ENUM('TEXT', 'VOICE', 'FILE') DEFAULT 'TEXT',
				        category VARCHAR(255) DEFAULT 'GENERAL',
				        position INT DEFAULT 0,
				        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
				        FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE
				    );
				""";

		// Sprint 12: Message persistence
		String createMessages = """
				    CREATE TABLE IF NOT EXISTS room_messages (
				        message_id VARCHAR(255) PRIMARY KEY,
				        channel_id VARCHAR(255) NOT NULL,
				        sender_username VARCHAR(50) NOT NULL,
				        content TEXT NOT NULL,
				        sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
				        FOREIGN KEY (channel_id) REFERENCES room_channels(channel_id) ON DELETE CASCADE,
				        FOREIGN KEY (sender_username) REFERENCES users(username) ON DELETE CASCADE
				    );
				""";

		try (Connection conn = getConnection();
				Statement stmt = conn.createStatement()) {
			stmt.execute(createRooms);
			stmt.execute(createMembers);
			stmt.execute(createChannels);
			stmt.execute(createMessages);
			LOGGER.info("Rooms v1 tables initialized (including channels and messages).");
		} catch (SQLException e) {
			LOGGER.error("Failed to init Rooms tables: " + e.getMessage());
		}
	}

	public static boolean createRoom(String roomId, String name, String ownerNodeId, boolean isPrivate)
			throws SQLException {
		// created_at is automatic (DEFAULT CURRENT_TIMESTAMP) usually, but we can set
		// it explicitly valid for TIMESTAMP column
		String sql = "INSERT INTO rooms (room_id, name, owner_username, is_private) VALUES (?, ?, ?, ?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			stmt.setString(2, name);
			stmt.setString(3, ownerNodeId); // ownerNodeId maps to owner_username
			stmt.setBoolean(4, isPrivate);
			return stmt.executeUpdate() > 0;
		}
	}

	public static java.util.List<com.saferoom.rooms.grpc.RoomMetadata> getRooms(String searchQuery)
			throws SQLException {
		String sql = "SELECT * FROM rooms WHERE is_private = FALSE";
		if (searchQuery != null && !searchQuery.isEmpty()) {
			sql += " AND name LIKE ?";
		}

		java.util.List<com.saferoom.rooms.grpc.RoomMetadata> list = new java.util.ArrayList<>();
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {

			if (searchQuery != null && !searchQuery.isEmpty()) {
				stmt.setString(1, "%" + searchQuery + "%");
			}

			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Timestamp ts = rs.getTimestamp("created_at");
				list.add(com.saferoom.rooms.grpc.RoomMetadata.newBuilder()
						.setRoomId(rs.getString("room_id"))
						.setName(rs.getString("name"))
						.setOwnerNodeId(rs.getString("owner_username"))
						.setIsPrivate(rs.getBoolean("is_private"))
						.setCreatedAt(ts != null ? ts.getTime() : 0L)
						// Online stats can be joined/calculated, keeping simple for now
						.build());
			}
		}
		return list;
	}

	public static com.saferoom.rooms.grpc.RoomMetadata getRoom(String roomId) throws SQLException {
		String sql = "SELECT * FROM rooms WHERE room_id = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				Timestamp ts = rs.getTimestamp("created_at");
				return com.saferoom.rooms.grpc.RoomMetadata.newBuilder()
						.setRoomId(rs.getString("room_id"))
						.setName(rs.getString("name"))
						.setOwnerNodeId(rs.getString("owner_username"))
						.setIsPrivate(rs.getBoolean("is_private"))
						.setCreatedAt(ts != null ? ts.getTime() : 0L)
						.build();
			}
		}
		return null;
	}

	public static boolean addRoomMember(String roomId, String nodeId) throws SQLException {
		String sql = "INSERT INTO room_members (room_id, username, joined_at) VALUES (?, ?, CURRENT_TIMESTAMP) " +
				"ON DUPLICATE KEY UPDATE joined_at = joined_at";
		// Safe for re-joins
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			stmt.setString(2, nodeId); // nodeId maps to username
			// joined_at handled by DB or explicit CURRENT_TIMESTAMP
			return stmt.executeUpdate() > 0;
		}
	}

	public static boolean removeRoomMember(String roomId, String nodeId) throws SQLException {
		String sql = "DELETE FROM room_members WHERE room_id = ? AND username = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			stmt.setString(2, nodeId);
			return stmt.executeUpdate() > 0;
		}
	}

	// ====================== Sprint 11: Channel CRUD ======================

	public static boolean createChannel(String channelId, String roomId, String name, String type, String category)
			throws SQLException {
		String sql = "INSERT INTO room_channels (channel_id, room_id, name, type, category, position) VALUES (?, ?, ?, ?, ?, ?)";
		int position = getChannelCount(roomId); // Append to end
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, channelId);
			stmt.setString(2, roomId);
			stmt.setString(3, name);
			stmt.setString(4, type);
			stmt.setString(5, category);
			stmt.setInt(6, position);
			return stmt.executeUpdate() > 0;
		}
	}

	private static int getChannelCount(String roomId) throws SQLException {
		String sql = "SELECT COUNT(*) FROM room_channels WHERE room_id = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		}
		return 0;
	}

	public static ChannelInfo getChannel(String channelId) throws SQLException {
		String sql = "SELECT * FROM room_channels WHERE channel_id = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, channelId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return new ChannelInfo(
							rs.getString("channel_id"),
							rs.getString("room_id"),
							rs.getString("name"),
							rs.getString("type"),
							rs.getString("category"),
							rs.getInt("position"));
				}
			}
		}
		return null;
	}

	public static java.util.List<ChannelInfo> getChannels(String roomId) throws SQLException {
		String sql = "SELECT * FROM room_channels WHERE room_id = ? ORDER BY category, position";
		java.util.List<ChannelInfo> channels = new java.util.ArrayList<>();
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, roomId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					channels.add(new ChannelInfo(
							rs.getString("channel_id"),
							rs.getString("room_id"),
							rs.getString("name"),
							rs.getString("type"),
							rs.getString("category"),
							rs.getInt("position")));
				}
			}
		}
		return channels;
	}

	public static boolean deleteChannel(String channelId) throws SQLException {
		String sql = "DELETE FROM room_channels WHERE channel_id = ?";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, channelId);
			return stmt.executeUpdate() > 0;
		}
	}

	// Channel Info DTO
	public static class ChannelInfo {
		public final String channelId;
		public final String roomId;
		public final String name;
		public final String type;
		public final String category;
		public final int position;

		public ChannelInfo(String channelId, String roomId, String name, String type, String category, int position) {
			this.channelId = channelId;
			this.roomId = roomId;
			this.name = name;
			this.type = type;
			this.category = category;
			this.position = position;
		}
	}

	// ====================== Sprint 12: Message CRUD ======================

	public static boolean saveMessage(String messageId, String channelId, String senderUsername, String content)
			throws SQLException {
		String sql = "INSERT INTO room_messages (message_id, channel_id, sender_username, content) VALUES (?, ?, ?, ?)";
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, messageId);
			stmt.setString(2, channelId);
			stmt.setString(3, senderUsername);
			stmt.setString(4, content);
			return stmt.executeUpdate() > 0;
		}
	}

	public static java.util.List<MessageInfo> getMessages(String channelId, int limit) throws SQLException {
		String sql = "SELECT * FROM room_messages WHERE channel_id = ? ORDER BY sent_at DESC LIMIT ?";
		java.util.List<MessageInfo> messages = new java.util.ArrayList<>();
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, channelId);
			stmt.setInt(2, limit);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					messages.add(0, new MessageInfo( // Add at 0 to reverse order (oldest first)
							rs.getString("message_id"),
							rs.getString("channel_id"),
							rs.getString("sender_username"),
							rs.getString("content"),
							rs.getTimestamp("sent_at").getTime()));
				}
			}
		}
		return messages;
	}

	// Message Info DTO
	public static class MessageInfo {
		public final String messageId;
		public final String channelId;
		public final String senderUsername;
		public final String content;
		public final long sentAt;

		public MessageInfo(String messageId, String channelId, String senderUsername, String content, long sentAt) {
			this.messageId = messageId;
			this.channelId = channelId;
			this.senderUsername = senderUsername;
			this.content = content;
			this.sentAt = sentAt;
		}
	}

	static {
		// Ensure tables exist on load
		initRoomTables();
	}
}