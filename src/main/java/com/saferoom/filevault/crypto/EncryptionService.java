package com.saferoom.filevault.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM Encryption Service
 * 
 * Simple, production-grade file encryption
 */
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 128;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate 256-bit AES key
     */
    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(KEY_SIZE, secureRandom);
        return keyGen.generateKey();
    }

    /**
     * Generate 12-byte IV
     */
    public static byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE];
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * Encrypt file (Streaming)
     */
    public static EncryptionResult encrypt(Path inputFile, Path outputFile, SecretKey key) throws Exception {
        long startTime = System.currentTimeMillis();
        byte[] iv = generateIV();

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);

        long originalSize = Files.size(inputFile);
        long encryptedSize = 0;

        try (InputStream fis = Files.newInputStream(inputFile);
                FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {

            // Write 12-byte IV first
            fos.write(iv);
            encryptedSize += IV_SIZE;

            // Stream encryption
            try (javax.crypto.CipherOutputStream cos = new javax.crypto.CipherOutputStream(fos, cipher)) {
                byte[] buffer = new byte[8192]; // 8KB buffer
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    cos.write(buffer, 0, bytesRead);
                    encryptedSize += bytesRead; // Approximate (GCM adds tag at end)
                }
            }
            // Tag is added automatically by CipherOutputStream on close
        }

        long duration = System.currentTimeMillis() - startTime;

        // Final size check
        try {
            encryptedSize = Files.size(outputFile);
        } catch (IOException ignored) {
        }

        return new EncryptionResult(
                true,
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(key.getEncoded()),
                originalSize,
                encryptedSize,
                duration);
    }

    /**
     * Decrypt file (Streaming)
     */
    public static DecryptionResult decrypt(Path encryptedFile, Path outputFile, SecretKey key) throws Exception {
        long startTime = System.currentTimeMillis();
        long encryptedSize = Files.size(encryptedFile);

        if (encryptedSize < IV_SIZE) {
            return new DecryptionResult(false, "Invalid encrypted file", 0, 0);
        }

        try (InputStream fis = Files.newInputStream(encryptedFile)) {
            // Read IV
            byte[] iv = new byte[IV_SIZE];
            if (fis.read(iv) != IV_SIZE) {
                return new DecryptionResult(false, "File too short for IV", encryptedSize, 0);
            }

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

            try (javax.crypto.CipherInputStream cis = new javax.crypto.CipherInputStream(fis, cipher);
                    OutputStream fos = Files.newOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = cis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            // Clean up partial output
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
            }
            return new DecryptionResult(false, "Wrong key or corrupted file: " + e.getMessage(), encryptedSize, 0);
        }

        long duration = System.currentTimeMillis() - startTime;
        long decryptedSize = Files.size(outputFile);

        return new DecryptionResult(true, "Success", encryptedSize, decryptedSize, duration);
    }

    /**
     * Convert Base64 to SecretKey
     */
    public static SecretKey base64ToKey(String base64) {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return new SecretKeySpec(keyBytes, "AES");
    }

    // Result classes
    public static class EncryptionResult {
        public final boolean success;
        public final String ivBase64;
        public final String keyBase64;
        public final long originalSize;
        public final long encryptedSize;
        public final long durationMs;

        public EncryptionResult(boolean success, String ivBase64, String keyBase64,
                long originalSize, long encryptedSize, long durationMs) {
            this.success = success;
            this.ivBase64 = ivBase64;
            this.keyBase64 = keyBase64;
            this.originalSize = originalSize;
            this.encryptedSize = encryptedSize;
            this.durationMs = durationMs;
        }
    }

    public static class DecryptionResult {
        public final boolean success;
        public final String message;
        public final long encryptedSize;
        public final long decryptedSize;
        public final long durationMs;

        public DecryptionResult(boolean success, String message, long encryptedSize, long decryptedSize) {
            this(success, message, encryptedSize, decryptedSize, 0);
        }

        public DecryptionResult(boolean success, String message, long encryptedSize, long decryptedSize,
                long durationMs) {
            this.success = success;
            this.message = message;
            this.encryptedSize = encryptedSize;
            this.decryptedSize = decryptedSize;
            this.durationMs = durationMs;
        }
    }
}
