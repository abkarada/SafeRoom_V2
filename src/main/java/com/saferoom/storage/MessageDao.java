package com.saferoom.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.saferoom.gui.model.FileAttachment;
import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.MessageType;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Data Access Object for Message persistence
 * 
 * Handles:
 * - Message serialization/deserialization
 * - Encrypted content storage
 * - Thumbnail BLOB handling
 * - CRUD operations
 */
public class MessageDao {

    private static final Logger LOGGER = Logger.getLogger(MessageDao.class.getName());
    private final LocalDatabase database;
    private final Gson gson;

    public MessageDao(LocalDatabase database) {
        this.database = database;
        this.gson = new Gson();
    }

    /**
     * Insert a new message into database
     */
    public void insertMessage(Message message, String conversationId) throws SQLException {
        // Logging sensitive content is bad practice in production, but keeping ID/Type
        // logs is fine
        System.out.printf("[MessageDao] 📝 Inserting message into database:%n");
        System.out.printf("   Message ID: %s%n", message.getId());

        String sql = """
                INSERT INTO messages (
                    id, conversation_id, timestamp, type, content,
                    thumbnail, file_path, is_outgoing, sender_id, sender_avatar_char
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, message.getId());
            stmt.setString(2, conversationId);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, message.getType().name());

            // Serialize message content
            String content = serializeContent(message);
            // ENCRYPT CONTENT
            String encryptedContent = database.encrypt(content);
            stmt.setString(5, encryptedContent);

            // Handle thumbnail
            byte[] thumbnailBytes = extractThumbnailBytes(message);
            if (thumbnailBytes != null) {
                stmt.setBytes(6, thumbnailBytes);
            } else {
                stmt.setNull(6, java.sql.Types.BLOB);
            }

            // File path
            String filePath = extractFilePath(message);
            stmt.setString(7, filePath);

            stmt.setInt(8, message.isOutgoing() ? 1 : 0);
            stmt.setString(9, message.getSenderId());
            stmt.setString(10, message.getSenderAvatarChar());

            int rowsAffected = stmt.executeUpdate();

            System.out.printf("[MessageDao] ✅ INSERT successful! Rows affected: %d%n", rowsAffected);
            LOGGER.fine("Inserted message: " + message.getId() + " to conversation: " + conversationId);
        } catch (SQLException e) {
            System.err.printf("[MessageDao] ❌ INSERT failed: %s%n", e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Load all messages for a conversation
     */
    public List<Message> loadMessages(String conversationId) throws SQLException {
        String sql = """
                SELECT * FROM messages
                WHERE conversation_id = ?
                ORDER BY timestamp ASC
                """;

        List<Message> messages = new ArrayList<>();

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Message message = deserializeMessage(rs);
                    messages.add(message);
                }
            }
        }

        LOGGER.fine("Loaded " + messages.size() + " messages for conversation: " + conversationId);
        return messages;
    }

    /**
     * Load messages with pagination
     */
    public List<Message> loadMessagesPaginated(String conversationId, int limit, int offset) throws SQLException {
        String sql = """
                SELECT * FROM messages
                WHERE conversation_id = ?
                ORDER BY timestamp DESC
                LIMIT ? OFFSET ?
                """;

        List<Message> messages = new ArrayList<>();

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Message message = deserializeMessage(rs);
                    messages.add(message);
                }
            }
        }

        return messages;
    }

    /**
     * Load media messages (images, videos, documents) for a conversation
     */
    public List<Message> loadMediaMessages(String conversationId) throws SQLException {
        String sql = """
                SELECT * FROM messages
                WHERE conversation_id = ?
                AND type IN ('IMAGE', 'VIDEO', 'DOCUMENT', 'FILE')
                ORDER BY timestamp DESC
                """;

        List<Message> messages = new ArrayList<>();

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Message message = deserializeMessage(rs);
                    messages.add(message);
                }
            }
        }

        return messages;
    }

    /**
     * Delete a message
     */
    public boolean deleteMessage(String messageId) throws SQLException {
        String sql = "DELETE FROM messages WHERE id = ?";

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, messageId);
            int affected = stmt.executeUpdate();
            return affected > 0;
        }
    }

    /**
     * Delete all messages for a conversation
     */
    public int deleteConversationMessages(String conversationId) throws SQLException {
        String sql = "DELETE FROM messages WHERE conversation_id = ?";

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            return stmt.executeUpdate();
        }
    }

    /**
     * Get message count for a conversation
     */
    public int getMessageCount(String conversationId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM messages WHERE conversation_id = ?";

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        return 0;
    }

    /**
     * Serialize message content to JSON
     */
    private String serializeContent(Message message) {
        JsonObject json = new JsonObject();

        if (message.getType() == MessageType.TEXT) {
            json.addProperty("text", message.getText());
            json.addProperty("mime", "text/plain");
        } else {
            // File message
            FileAttachment attachment = message.getAttachment();
            if (attachment != null) {
                json.addProperty("fileName", attachment.getFileName());
                json.addProperty("fileSize", attachment.getFileSizeBytes());
                json.addProperty("mime", getMimeType(attachment));
            }
        }

        return gson.toJson(json);
    }

    /**
     * Deserialize message from database row
     */
    private Message deserializeMessage(ResultSet rs) throws SQLException {
        // Note: id is loaded from DB but Message uses its own UUID - we trust the DB
        // stored ID
        String senderId = rs.getString("sender_id");
        String senderAvatarChar = rs.getString("sender_avatar_char");
        MessageType type = MessageType.valueOf(rs.getString("type"));

        // DECRYPT CONTENT
        String encryptedContent = rs.getString("content");
        String contentJson = database.decrypt(encryptedContent);

        boolean isOutgoing = rs.getInt("is_outgoing") == 1;

        Message message;

        if (type == MessageType.TEXT) {
            // Text message
            JsonObject json = gson.fromJson(contentJson, JsonObject.class);
            String text = json != null && json.has("text") ? json.get("text").getAsString()
                    : "Error: Decryption Failed";
            message = new Message(text, senderId, senderAvatarChar);
        } else {
            // File message
            JsonObject json = gson.fromJson(contentJson, JsonObject.class);
            // Handle null json (decryption error)
            String fileName = "Unknown";
            long fileSize = 0;
            if (json != null) {
                fileName = json.has("fileName") ? json.get("fileName").getAsString() : "File";
                fileSize = json.has("fileSize") ? json.get("fileSize").getAsLong() : 0;
            }

            String filePath = rs.getString("file_path");

            // Load thumbnail
            Image thumbnail = null;
            byte[] thumbnailBytes = rs.getBytes("thumbnail");
            if (thumbnailBytes != null) {
                try {
                    BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(thumbnailBytes));
                    thumbnail = javafx.embed.swing.SwingFXUtils.toFXImage(buffered, null);
                } catch (Exception e) {
                    LOGGER.warning("Failed to load thumbnail: " + e.getMessage());
                }
            }

            Path path = filePath != null ? Paths.get(filePath) : null;
            FileAttachment attachment = new FileAttachment(type, fileName, fileSize, path, thumbnail);

            message = new Message("", senderId, senderAvatarChar);
            message.setAttachment(attachment);
        }

        message.setType(type);
        message.setOutgoing(isOutgoing);

        return message;
    }

    /**
     * Extract thumbnail bytes from message
     */
    private byte[] extractThumbnailBytes(Message message) {
        if (message.getAttachment() == null || message.getAttachment().getThumbnail() == null) {
            return null;
        }

        try {
            Image fxImage = message.getAttachment().getThumbnail();
            BufferedImage buffered = javafx.embed.swing.SwingFXUtils.fromFXImage(fxImage, null);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(buffered, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            LOGGER.warning("Failed to extract thumbnail bytes: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract file path from message
     */
    private String extractFilePath(Message message) {
        if (message.getAttachment() == null || message.getAttachment().getLocalPath() == null) {
            return null;
        }
        return message.getAttachment().getLocalPath().toString();
    }

    /**
     * Get MIME type from attachment
     */
    private String getMimeType(FileAttachment attachment) {
        String fileName = attachment.getFileName().toLowerCase();

        if (fileName.endsWith(".png"))
            return "image/png";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"))
            return "image/jpeg";
        if (fileName.endsWith(".gif"))
            return "image/gif";
        if (fileName.endsWith(".mp4"))
            return "video/mp4";
        if (fileName.endsWith(".mov"))
            return "video/quicktime";
        if (fileName.endsWith(".pdf"))
            return "application/pdf";
        if (fileName.endsWith(".doc") || fileName.endsWith(".docx"))
            return "application/msword";
        if (fileName.endsWith(".txt"))
            return "text/plain";

        return "application/octet-stream";
    }

    /**
     * Get all file messages (non-text messages with attachments)
     * Used by Secure Files to show DM/Meeting files
     */
    public List<DmFileRecord> getAllFileMessages() throws SQLException {
        List<DmFileRecord> files = new ArrayList<>();

        // Correct column names from messages table schema
        String sql = """
                SELECT id, conversation_id, sender_id, type, content, file_path,
                       timestamp, is_outgoing, thumbnail
                FROM messages
                WHERE type != 'TEXT' AND file_path IS NOT NULL AND file_path != ''
                ORDER BY timestamp DESC
                """;

        try (PreparedStatement stmt = database.getConnection().prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                DmFileRecord record = new DmFileRecord();
                record.id = rs.getString("id"); // id is TEXT not LONG
                record.conversationId = rs.getString("conversation_id");
                record.senderId = rs.getString("sender_id");
                record.messageType = rs.getString("type"); // column is 'type' not 'message_type'
                record.filePath = rs.getString("file_path");
                record.createdAt = rs.getLong("timestamp"); // column is 'timestamp' not 'created_at'
                record.isOutgoing = rs.getInt("is_outgoing") == 1;

                // DECRYPT CONTENT for file records too
                String encryptedContent = rs.getString("content");
                String contentJson = database.decrypt(encryptedContent);

                if (contentJson != null && !contentJson.isEmpty()) {
                    try {
                        JsonObject json = gson.fromJson(contentJson, JsonObject.class);
                        record.fileName = json.has("fileName") ? json.get("fileName").getAsString() : "File";
                        record.fileSize = json.has("fileSize") ? json.get("fileSize").getAsLong() : 0;
                        record.mimeType = json.has("mime") ? json.get("mime").getAsString()
                                : getMimeFromFileName(record.fileName);
                    } catch (Exception e) {
                        record.fileName = extractFileNameFromPath(record.filePath);
                        record.fileSize = 0;
                        record.mimeType = getMimeFromFileName(record.fileName);
                    }
                } else {
                    record.fileName = extractFileNameFromPath(record.filePath);
                    record.mimeType = getMimeFromFileName(record.fileName);
                }

                // Load thumbnail
                byte[] thumbnailBytes = rs.getBytes("thumbnail");
                if (thumbnailBytes != null) {
                    try {
                        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(thumbnailBytes));
                        record.thumbnail = javafx.embed.swing.SwingFXUtils.toFXImage(buffered, null);
                    } catch (Exception e) {
                        // Ignore thumbnail errors
                    }
                }

                files.add(record);
            }
        }

        System.out.printf("[MessageDao] ✅ Loaded %d file messages from DM%n", files.size());
        return files;
    }

    /**
     * Extract file name from path
     */
    private String extractFileNameFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty())
            return "Unknown File";
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    /**
     * Get MIME type from file name
     */
    private String getMimeFromFileName(String fileName) {
        if (fileName == null)
            return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png"))
            return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
            return "image/jpeg";
        if (lower.endsWith(".gif"))
            return "image/gif";
        if (lower.endsWith(".webp"))
            return "image/webp";
        if (lower.endsWith(".bmp"))
            return "image/bmp";
        if (lower.endsWith(".mp4"))
            return "video/mp4";
        if (lower.endsWith(".mov"))
            return "video/quicktime";
        if (lower.endsWith(".mkv"))
            return "video/x-matroska";
        if (lower.endsWith(".avi"))
            return "video/x-msvideo";
        if (lower.endsWith(".pdf"))
            return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx"))
            return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx"))
            return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx"))
            return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".txt"))
            return "text/plain";
        if (lower.endsWith(".md"))
            return "text/markdown";
        if (lower.endsWith(".zip"))
            return "application/zip";
        if (lower.endsWith(".rar"))
            return "application/x-rar-compressed";
        if (lower.endsWith(".7z"))
            return "application/x-7z-compressed";
        if (lower.endsWith(".tar"))
            return "application/x-tar";
        if (lower.endsWith(".gz"))
            return "application/gzip";
        return "application/octet-stream";
    }

    /**
     * DM File Record - represents a file shared in DM
     */
    public static class DmFileRecord {
        public String id; // TEXT type
        public String conversationId;
        public String senderId;
        public String messageType;
        public String fileName;
        public long fileSize;
        public String filePath;
        public String mimeType;
        public long createdAt;
        public boolean isOutgoing;
        public Image thumbnail;

        /**
         * Get file category based on MIME type
         */
        public String getCategory() {
            if (mimeType == null)
                return "Other";
            if (mimeType.startsWith("image/"))
                return "Images";
            if (mimeType.startsWith("video/"))
                return "Videos";
            if (mimeType.contains("pdf") || mimeType.contains("word") ||
                    mimeType.contains("excel") || mimeType.contains("powerpoint") ||
                    mimeType.contains("text/") || mimeType.contains("document"))
                return "Documents";
            if (mimeType.contains("zip") || mimeType.contains("rar") ||
                    mimeType.contains("7z") || mimeType.contains("tar") ||
                    mimeType.contains("gzip"))
                return "Archives";
            return "Other";
        }

        public String getFormattedSize() {
            if (fileSize < 1024)
                return fileSize + " B";
            if (fileSize < 1024 * 1024)
                return String.format("%.1f KB", fileSize / 1024.0);
            if (fileSize < 1024 * 1024 * 1024)
                return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
            return String.format("%.1f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(createdAt);
            return instant.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"));
        }
    }
}
