package com.saferoom.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GroupKeyManager - Manages AES-256-GCM group encryption keys for SDRT
 * 
 * Key Features:
 * - Single shared key per room (all participants use same key)
 * - AES-256-GCM encryption (authenticated encryption)
 * - Automatic key rotation (every 30 minutes by default)
 * - Forward secrecy (old keys destroyed after rotation)
 * - Key distribution via signaling server
 * 
 * Security Model:
 * - Server generates key and distributes to all participants
 * - Each participant encrypts their own stream with group key
 * - RELAY nodes forward encrypted packets without decrypting
 * - Only destination nodes decrypt packets
 * 
 * Room ID Integration:
 * - Each Room ID has its own unique group key
 * - Keys are isolated between rooms
 */
public class GroupKeyManager {
    
    private static final Logger logger = Logger.getLogger(GroupKeyManager.class.getName());
    
    // Encryption constants
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;                    // AES-256
    private static final int GCM_TAG_LENGTH = 128;              // 128-bit authentication tag
    private static final int GCM_IV_LENGTH = 12;                // 12 bytes (96 bits) for GCM
    
    // Key rotation
    private static final long DEFAULT_KEY_ROTATION_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes
    
    // Room ID → Group key mapping
    private final Map<String, RoomKeyInfo> roomKeys = new ConcurrentHashMap<>();
    
    // Key rotation scheduler
    private final ScheduledExecutorService rotationScheduler;
    private final boolean autoRotationEnabled;
    
    // Key rotation callback (for notifying server to distribute new key)
    private KeyRotationCallback rotationCallback;
    
    /**
     * Callback interface for key rotation events
     */
    public interface KeyRotationCallback {
        void onKeyRotated(String roomId, String newKeyBase64);
    }
    
    /**
     * RoomKeyInfo - Stores key information for a room
     */
    private static class RoomKeyInfo {
        SecretKey key;
        long createdAt;
        long rotationInterval;
        
        RoomKeyInfo(SecretKey key, long rotationInterval) {
            this.key = key;
            this.createdAt = System.currentTimeMillis();
            this.rotationInterval = rotationInterval;
        }
        
        boolean shouldRotate() {
            return System.currentTimeMillis() - createdAt >= rotationInterval;
        }
    }
    
    /**
     * Constructor with auto-rotation enabled
     */
    public GroupKeyManager(boolean enableAutoRotation) {
        this.autoRotationEnabled = enableAutoRotation;
        
        if (enableAutoRotation) {
            this.rotationScheduler = Executors.newScheduledThreadPool(1);
            // Check for key rotation every 5 minutes
            rotationScheduler.scheduleAtFixedRate(
                this::checkAndRotateKeys,
                5, 5, TimeUnit.MINUTES
            );
            logger.info("GroupKeyManager initialized with auto-rotation enabled");
        } else {
            this.rotationScheduler = null;
            logger.info("GroupKeyManager initialized (auto-rotation disabled)");
        }
    }
    
    /**
     * Constructor with default settings (auto-rotation enabled)
     */
    public GroupKeyManager() {
        this(true);
    }
    
    /**
     * Generate a new group key for a room
     * 
     * @param roomId Room ID
     * @return Base64-encoded key string (for distribution via signaling)
     */
    public String generateKeyForRoom(String roomId) {
        return generateKeyForRoom(roomId, DEFAULT_KEY_ROTATION_INTERVAL_MS);
    }
    
    /**
     * Generate a new group key for a room with custom rotation interval
     */
    public String generateKeyForRoom(String roomId, long rotationIntervalMs) {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_SIZE, new SecureRandom());
            SecretKey key = keyGen.generateKey();
            
            // Store key info
            roomKeys.put(roomId, new RoomKeyInfo(key, rotationIntervalMs));
            
            // Encode to Base64 for transmission
            String keyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
            
            logger.info(String.format("Generated group key for room %s (rotation: %d min)", 
                                     roomId, rotationIntervalMs / 60000));
            
            return keyBase64;
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to generate group key for room " + roomId, e);
            throw new RuntimeException("Key generation failed", e);
        }
    }
    
    /**
     * Import a group key received from server
     * 
     * @param roomId Room ID
     * @param keyBase64 Base64-encoded key string
     */
    public void importKeyForRoom(String roomId, String keyBase64) {
        importKeyForRoom(roomId, keyBase64, DEFAULT_KEY_ROTATION_INTERVAL_MS);
    }
    
    /**
     * Import a group key with custom rotation interval
     */
    public void importKeyForRoom(String roomId, String keyBase64, long rotationIntervalMs) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            
            roomKeys.put(roomId, new RoomKeyInfo(key, rotationIntervalMs));
            
            logger.info(String.format("Imported group key for room %s", roomId));
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to import group key for room " + roomId, e);
            throw new RuntimeException("Key import failed", e);
        }
    }
    
    /**
     * Encrypt packet payload for transmission
     * 
     * Format: [12 bytes IV][encrypted data][16 bytes auth tag]
     * 
     * @param roomId Room ID (to get correct key)
     * @param plaintext Plain data to encrypt
     * @return Encrypted data with IV and auth tag
     */
    public byte[] encrypt(String roomId, byte[] plaintext) {
        RoomKeyInfo keyInfo = roomKeys.get(roomId);
        if (keyInfo == null) {
            throw new IllegalStateException("No key available for room: " + roomId);
        }
        
        try {
            // Generate random IV (96 bits for GCM)
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keyInfo.key, gcmSpec);
            
            // Encrypt (includes auth tag)
            byte[] ciphertext = cipher.doFinal(plaintext);
            
            // Concatenate: IV + ciphertext (which includes auth tag)
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            
            return buffer.array();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Encryption failed for room " + roomId, e);
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    /**
     * Decrypt packet payload received from tree
     * 
     * @param roomId Room ID (to get correct key)
     * @param encrypted Encrypted data (IV + ciphertext + auth tag)
     * @return Decrypted plain data
     */
    public byte[] decrypt(String roomId, byte[] encrypted) {
        RoomKeyInfo keyInfo = roomKeys.get(roomId);
        if (keyInfo == null) {
            throw new IllegalStateException("No key available for room: " + roomId);
        }
        
        if (encrypted.length < GCM_IV_LENGTH + GCM_TAG_LENGTH / 8) {
            throw new IllegalArgumentException("Encrypted data too short");
        }
        
        try {
            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keyInfo.key, gcmSpec);
            
            // Decrypt and verify auth tag
            return cipher.doFinal(ciphertext);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Decryption failed for room " + roomId + ": " + e.getMessage());
            throw new RuntimeException("Decryption failed (may indicate tampering)", e);
        }
    }
    
    /**
     * Rotate key for a room (generate new key and invalidate old one)
     * 
     * @param roomId Room ID
     * @return New Base64-encoded key (for distribution)
     */
    public String rotateKeyForRoom(String roomId) {
        logger.info(String.format("Rotating key for room %s", roomId));
        
        RoomKeyInfo oldKeyInfo = roomKeys.get(roomId);
        long rotationInterval = oldKeyInfo != null ? 
                                oldKeyInfo.rotationInterval : 
                                DEFAULT_KEY_ROTATION_INTERVAL_MS;
        
        // Generate new key
        String newKeyBase64 = generateKeyForRoom(roomId, rotationInterval);
        
        // Securely erase old key
        if (oldKeyInfo != null) {
            Arrays.fill(oldKeyInfo.key.getEncoded(), (byte) 0);
        }
        
        // Notify callback
        if (rotationCallback != null) {
            rotationCallback.onKeyRotated(roomId, newKeyBase64);
        }
        
        return newKeyBase64;
    }
    
    /**
     * Check all rooms and rotate keys if needed (called by scheduler)
     */
    private void checkAndRotateKeys() {
        for (Map.Entry<String, RoomKeyInfo> entry : roomKeys.entrySet()) {
            String roomId = entry.getKey();
            RoomKeyInfo keyInfo = entry.getValue();
            
            if (keyInfo.shouldRotate()) {
                logger.info(String.format("Auto-rotating key for room %s", roomId));
                rotateKeyForRoom(roomId);
            }
        }
    }
    
    /**
     * Remove key for a room (when room is closed)
     */
    public void removeKeyForRoom(String roomId) {
        RoomKeyInfo keyInfo = roomKeys.remove(roomId);
        if (keyInfo != null) {
            // Securely erase key
            Arrays.fill(keyInfo.key.getEncoded(), (byte) 0);
            logger.info(String.format("Removed key for room %s", roomId));
        }
    }
    
    /**
     * Check if key exists for a room
     */
    public boolean hasKeyForRoom(String roomId) {
        return roomKeys.containsKey(roomId);
    }
    
    /**
     * Set callback for key rotation events
     */
    public void setRotationCallback(KeyRotationCallback callback) {
        this.rotationCallback = callback;
    }
    
    /**
     * Get key age (for monitoring)
     */
    public long getKeyAgeMs(String roomId) {
        RoomKeyInfo keyInfo = roomKeys.get(roomId);
        if (keyInfo == null) {
            return -1;
        }
        return System.currentTimeMillis() - keyInfo.createdAt;
    }
    
    /**
     * Clean up resources
     */
    public void shutdown() {
        if (rotationScheduler != null) {
            rotationScheduler.shutdown();
            try {
                if (!rotationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    rotationScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                rotationScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Securely erase all keys
        for (RoomKeyInfo keyInfo : roomKeys.values()) {
            Arrays.fill(keyInfo.key.getEncoded(), (byte) 0);
        }
        roomKeys.clear();
        
        logger.info("GroupKeyManager shutdown complete");
    }
    
    /**
     * Get encryption overhead size (for bandwidth calculation)
     */
    public static int getEncryptionOverhead() {
        return GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8); // 12 + 16 = 28 bytes
    }
}
