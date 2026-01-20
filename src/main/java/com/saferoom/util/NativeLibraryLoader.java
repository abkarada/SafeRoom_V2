package com.saferoom.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Helper to load native libraries (DLL/SO/DYLIB) from JAR resources.
 * This enables the application to be distributed as a single JAR/installer
 * without requiring the user to manually install native dependencies.
 */
public class NativeLibraryLoader {

    private static final int BUFFER_SIZE = 8192;

    public static void loadLibrary(String libraryName) {
        try {
            // 1. Try loading from system path (for developers who have it installed)
            System.loadLibrary(libraryName);
            System.out.println("[NativeLibraryLoader] Loaded " + libraryName + " from system path.");
        } catch (UnsatisfiedLinkError e) {
            // 2. Fallback: Load from JAR resources
            loadFromJar(libraryName);
        }
    }

    private static void loadFromJar(String libraryName) {
        String osName = System.getProperty("os.name").toLowerCase(Locale.US);
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.US);

        String platformPaths = getPlatformPath(osName, osArch);
        String fileExtension = getFileExtension(osName);

        // Full expected filename (e.g. "libnative_encoder.so" or "native_encoder.dll")
        String fullFilename = mapLibraryName(libraryName, osName, fileExtension);

        // Resource path: /natives/{platform}/filename
        // e.g., /natives/linux-x86_64/libnative_encoder.so
        String resourcePath = "/natives/" + platformPaths + "/" + fullFilename;

        try {
            InputStream is = NativeLibraryLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                throw new FileNotFoundException("Native library not found in resources: " + resourcePath);
            }

            // Create a temp file to extract to
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "saferoom_natives_" + System.nanoTime());
            if (!tempDir.mkdir()) {
                // Ignore if exists, or fail later
            }
            tempDir.deleteOnExit();

            File tempFile = new File(tempDir, fullFilename);
            tempFile.deleteOnExit();

            // Copy file
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            is.close();

            // Load the extracted file
            System.load(tempFile.getAbsolutePath());
            System.out.println("[NativeLibraryLoader] Loaded " + libraryName + " from JAR resources ("
                    + tempFile.getAbsolutePath() + ")");

        } catch (IOException ex) {
            throw new RuntimeException("Failed to load native library from JAR: " + resourcePath, ex);
        }
    }

    private static String getPlatformPath(String osName, String osArch) {
        if (osName.contains("win")) {
            return "windows-x86_64"; // simplify for now, check arch if needed
        } else if (osName.contains("mac")) {
            return osArch.contains("aarch64") || osArch.contains("arm") ? "macos-aarch64" : "macos-x86_64";
        } else {
            return "linux-x86_64";
        }
    }

    private static String getFileExtension(String osName) {
        if (osName.contains("win")) {
            return "dll";
        } else if (osName.contains("mac")) {
            return "dylib";
        } else {
            return "so";
        }
    }

    private static String mapLibraryName(String libName, String osName, String ext) {
        if (osName.contains("win")) {
            return libName + "." + ext;
        } else {
            return "lib" + libName + "." + ext;
        }
    }
}
