package com.saferoom.filevault.service;

import com.saferoom.filevault.model.FileMetadata;
import com.saferoom.filevault.model.FileMetadata.FileSource;
import com.saferoom.filevault.storage.FileVaultRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * File Indexer
 * 
 * Indexes files from different sources into FileVault
 * - DM received files
 * - Meeting shared files
 * - Local encrypted files
 */
public class FileIndexer {

    private final FileVaultRepository repository;

    public FileIndexer(FileVaultRepository repository) {
        this.repository = repository;
    }

    /**
     * Index a file received from DM
     */
    public FileMetadata indexDMFile(String senderUsername, Path filePath) {
        try {
            FileMetadata metadata = createBaseMetadata(filePath, FileSource.DM);
            metadata.setSourceUser(senderUsername);

            // Auto-categorize
            autoCategorizefile(metadata);

            // Save to database
            repository.insertFile(metadata);

            System.out.printf("[FileIndexer] ✅ Indexed DM file: %s from %s%n",
                    metadata.getOriginalName(), senderUsername);

            return metadata;

        } catch (Exception e) {
            System.err.println("[FileIndexer] ❌ Failed to index DM file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Index a file received from Meeting
     */
    public FileMetadata indexMeetingFile(String senderUsername, Path filePath) {
        try {
            FileMetadata metadata = createBaseMetadata(filePath, FileSource.MEETING);
            metadata.setSourceUser(senderUsername);

            autoCategorizefile(metadata);

            repository.insertFile(metadata);

            System.out.printf("[FileIndexer] ✅ Indexed Meeting file: %s from %s%n",
                    metadata.getOriginalName(), senderUsername);

            return metadata;

        } catch (Exception e) {
            System.err.println("[FileIndexer] ❌ Failed to index Meeting file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Index a locally encrypted file
     */
    public FileMetadata indexLocalEncryptedFile(Path originalFile, Path encryptedFile,
            String keyBase64, String ivBase64,
            boolean compressed, String compressionType) {
        try {
            FileMetadata metadata = createBaseMetadata(encryptedFile, FileSource.LOCAL);

            // Store original name
            metadata.getMetadata().put("original_name", originalFile.getFileName().toString());

            // Encryption info
            metadata.setEncrypted(true);
            metadata.setEncryptionKey(keyBase64);
            metadata.setIvBase64(ivBase64);

            // Compression info
            metadata.setCompressed(compressed);
            metadata.setCompressionType(compressionType);

            autoCategorizefile(metadata);

            repository.insertFile(metadata);

            System.out.printf("[FileIndexer] ✅ Indexed local encrypted file: %s%n",
                    metadata.getOriginalName());

            return metadata;

        } catch (Exception e) {
            System.err.println("[FileIndexer] ❌ Failed to index local file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create base metadata from file
     */
    private FileMetadata createBaseMetadata(Path filePath, FileSource source) throws Exception {
        FileMetadata metadata = new FileMetadata();

        metadata.setFileId(UUID.randomUUID().toString());
        metadata.setSource(source);
        metadata.setOriginalName(filePath.getFileName().toString());
        metadata.setStoredPath(filePath.toAbsolutePath().toString());
        metadata.setFileSize(Files.size(filePath));
        metadata.setMimeType(Files.probeContentType(filePath));
        metadata.setCreatedAt(System.currentTimeMillis());
        metadata.setModifiedAt(System.currentTimeMillis());

        // Calculate SHA-256 hash
        metadata.setHashSha256(calculateSHA256(filePath));

        return metadata;
    }

    /**
     * Auto-categorize file based on MIME type
     */
    private void autoCategorizefile(FileMetadata metadata) {
        FileMetadata.FileCategory category = metadata.getAutoCategory();
        if (category != FileMetadata.FileCategory.ALL) {
            metadata.addCategoryTag(category.getDisplayName());
        }
    }

    /**
     * Calculate SHA-256 hash
     */
    /**
     * Calculate SHA-256 hash (Streaming)
     */
    private String calculateSHA256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (java.io.InputStream fis = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hash = digest.digest();
        return Base64.getEncoder().encodeToString(hash);
    }
}
