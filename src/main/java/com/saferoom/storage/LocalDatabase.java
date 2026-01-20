package com.saferoom.storage;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

/**
 * SQLite Database with Application-level AES-256 encryption
 * 
 * Architecture:
 * - Single database file per user
 * - Content-level encryption (encrypt data before storing)
 * - Pure Java implementation (no native dependencies)
 * - Full-Text Search (FTS5) support
 * - Auto-migration and schema versioning
 * 
 * Tables:
 * - messages: Main message storage (encrypted content)
 * - messages_fts: Full-text search virtual table
 * - conversations: Conversation metadata
 * 
 * Note: Since we're not using SQLCipher native extension,
 * we encrypt the message content at application level using BouncyCastle
 * AES-256.
 */
public class LocalDatabase {

    private static final Logger LOGGER = Logger.getLogger(LocalDatabase.class.getName());
    private static final String DB_NAME = "saferoom.db";
    private static final int DB_VERSION = 1;

    private static LocalDatabase instance;
    private Connection connection;
    private String encryptionKey;
    private final String dbPath;

    private LocalDatabase(String userDataDir, String encryptionKey) {
        this.dbPath = userDataDir + File.separator + DB_NAME;
        this.encryptionKey = encryptionKey;
    }

    /**
     * Initialize database for a user
     * 
     * @param username    User's username
     * @param password    User's master password
     * @param userDataDir Directory to store database
     * @return LocalDatabase instance
     */
    public static synchronized LocalDatabase initialize(String username, String password, String userDataDir) {
        if (instance != null) {
            // Check if we are re-initializing for the same user
            if (instance.dbPath.equals(userDataDir + File.separator + DB_NAME)) {
                LOGGER.info("Database already initialized for this user, reusing instance.");
                return instance;
            }

            LOGGER.warning("Database initialized for different user/path, closing existing connection");
            instance.close();
        }

        // Create data directory if not exists
        File dataDir = new File(userDataDir);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        String key = SqlCipherHelper.deriveKey(username, password);
        instance = new LocalDatabase(userDataDir, key);
        instance.open();
        return instance;
    }

    /**
     * Get the singleton instance
     */
    public static LocalDatabase getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Database not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Check if database is initialized
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Get encryption key for content encryption
     */
    public String getEncryptionKey() {
        return encryptionKey;
    }

    /**
     * Open database connection and initialize schema
     */
    private void open() {
        try {
            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            // Create connection
            String url = "jdbc:sqlite:" + dbPath;
            connection = DriverManager.getConnection(url);

            // Configure SQLite for performance
            try (Statement stmt = connection.createStatement()) {
                // Performance optimizations
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                stmt.execute("PRAGMA temp_store = MEMORY");
                stmt.execute("PRAGMA mmap_size = 30000000000");
                stmt.execute("PRAGMA cache_size = -64000"); // 64MB cache

                LOGGER.info("Database opened successfully at: " + dbPath);
                LOGGER.info("Encryption: Content-level AES-256 (BouncyCastle)");
            }

            // Initialize schema
            initializeSchema();

        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open database", e);
        }
    }

    /**
     * Initialize or migrate database schema
     */
    private void initializeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {

            // Messages table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT,
                        thumbnail BLOB,
                        file_path TEXT,
                        is_outgoing INTEGER NOT NULL,
                        sender_id TEXT NOT NULL,
                        sender_avatar_char TEXT
                    )
                    """);

            // Indexes for performance
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_conv_time
                    ON messages(conversation_id, timestamp ASC)
                    """);

            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_timestamp
                    ON messages(timestamp DESC)
                    """);

            // Full-Text Search virtual table
            stmt.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
                    USING fts5(
                        message_id UNINDEXED,
                        content,
                        conversation_id UNINDEXED,
                        tokenize = 'unicode61'
                    )
                    """);

            // Triggers to keep FTS in sync
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS messages_ai
                    AFTER INSERT ON messages
                    BEGIN
                        INSERT INTO messages_fts(rowid, message_id, content, conversation_id)
                        VALUES (new.rowid, new.id, new.content, new.conversation_id);
                    END
                    """);

            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS messages_ad
                    AFTER DELETE ON messages
                    BEGIN
                        DELETE FROM messages_fts WHERE rowid = old.rowid;
                    END
                    """);

            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS messages_au
                    AFTER UPDATE ON messages
                    BEGIN
                        UPDATE messages_fts
                        SET content = new.content
                        WHERE rowid = new.rowid;
                    END
                    """);

            // Conversations metadata table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        conversation_id TEXT PRIMARY KEY,
                        participant_username TEXT NOT NULL,
                        last_message_time INTEGER,
                        unread_count INTEGER DEFAULT 0,
                        is_archived INTEGER DEFAULT 0
                    )
                    """);

            LOGGER.info("Database schema initialized (version " + DB_VERSION + ")");
        }
    }

    /**
     * Get database connection
     */
    public Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("Database not opened");
        }
        return connection;
    }

    /**
     * Execute a query and return ResultSet
     */
    public ResultSet executeQuery(String sql, Object... params) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(sql);
        setParameters(stmt, params);
        return stmt.executeQuery();
    }

    /**
     * Execute an update/insert/delete
     */
    public int executeUpdate(String sql, Object... params) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setParameters(stmt, params);
            return stmt.executeUpdate();
        }
    }

    /**
     * Set prepared statement parameters
     */
    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param instanceof String) {
                stmt.setString(i + 1, (String) param);
            } else if (param instanceof Integer) {
                stmt.setInt(i + 1, (Integer) param);
            } else if (param instanceof Long) {
                stmt.setLong(i + 1, (Long) param);
            } else if (param instanceof byte[]) {
                stmt.setBytes(i + 1, (byte[]) param);
            } else if (param == null) {
                stmt.setNull(i + 1, Types.NULL);
            } else {
                stmt.setObject(i + 1, param);
            }
        }
    }

    /**
     * Begin transaction
     */
    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    /**
     * Commit transaction
     */
    public void commit() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    /**
     * Rollback transaction
     */
    public void rollback() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    /**
     * Close database connection
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                LOGGER.info("Database connection closed");
            } catch (SQLException e) {
                LOGGER.warning("Error closing database: " + e.getMessage());
            }
            connection = null;
        }
    }

    /**
     * Get database file path
     */
    public String getDbPath() {
        return dbPath;
    }

    // ---------------------------------------------------------
    // ENCRYPTION HELPERS (AES-256-GCM)
    // ---------------------------------------------------------

    /**
     * Encrypt string using the derived key
     */
    public String encrypt(String plaintext) {
        if (plaintext == null)
            return null;
        try {
            byte[] keyBytes = hexToBytes(encryptionKey);
            byte[] iv = new byte[12]; // GCM standard IV size
            new java.security.SecureRandom().nextBytes(iv);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Setup buffer for IV + Ciphertext
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return java.util.Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (Exception e) {
            LOGGER.warning("Encryption failed: " + e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt string using the derived key
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null)
            return null;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(encryptedText);
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(decoded);

            byte[] iv = new byte[12];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            byte[] keyBytes = hexToBytes(encryptionKey);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, spec);
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            LOGGER.warning("Decryption failed: " + e.getMessage());
            // Return original text if decryption fails (backward compatibility or
            // corruption)
            return encryptedText;
        }
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
