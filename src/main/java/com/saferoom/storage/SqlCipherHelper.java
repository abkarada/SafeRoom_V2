package com.saferoom.storage;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * SQLCipher Encryption Helper
 * Generates AES-256 encryption keys from user passwords using PBKDF2
 * 
 * Security:
 * - PBKDF2 with HMAC-SHA256
 * - 250,000 iterations (OWASP recommended)
 * - 256-bit key output
 * - Salt derived from username for consistency
 */
public class SqlCipherHelper {

    private static final int ITERATION_COUNT = 250_000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    // Cache to prevent expensive PBKDF2 re-computation
    private static final java.util.Map<String, String> KEY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // Cache for conversation IDs (SHA-256)
    private static final java.util.Map<String, String> CONV_ID_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Derive encryption key from user password
     * 
     * @param username Username (used as salt base)
     * @param password User's master password
     * @return Hex-encoded encryption key for SQLCipher
     */
    public static String deriveKey(String username, String password) {
        String cacheKey = username + "|" + password;
        if (KEY_CACHE.containsKey(cacheKey)) {
            return KEY_CACHE.get(cacheKey);
        }

        try {
            // Generate salt from username (consistent for same user)
            byte[] salt = generateSalt(username);

            KeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    salt,
                    ITERATION_COUNT,
                    KEY_LENGTH);

            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();

            // Return as hex string for SQLCipher PRAGMA key
            String result = bytesToHex(hash);
            KEY_CACHE.put(cacheKey, result);
            return result;

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    /**
     * Generate consistent salt from username
     * Uses SHA-256 hash of username as salt
     */
    private static byte[] generateSalt(String username) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(username.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Convert bytes to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Generate conversation ID from two usernames
     * Always produces the same ID regardless of order (userA|userB == userB|userA)
     * 
     * @param user1 First username
     * @param user2 Second username
     * @return SHA-256 hash of sorted usernames
     */
    public static String generateConversationId(String user1, String user2) {
        // Sort to ensure consistent ID regardless of order
        String combined = user1.compareTo(user2) < 0
                ? user1 + "|" + user2
                : user2 + "|" + user1;

        if (CONV_ID_CACHE.containsKey(combined)) {
            return CONV_ID_CACHE.get(combined);
        }

        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            String result = bytesToHex(hash);
            CONV_ID_CACHE.put(combined, result);
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
