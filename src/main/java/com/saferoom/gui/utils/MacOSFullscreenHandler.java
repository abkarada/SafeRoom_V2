package com.saferoom.gui.utils;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.input.KeyCombination;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class MacOSFullscreenHandler {

    public static boolean isMacOS() {
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("mac");
    }

    public static void handleMacOSFullscreen(Stage stage, boolean enterFullscreen) {
        if (stage == null) {
            return;
        }

        try {
            if (enterFullscreen) {
                // 1. Çıkış tuşunu iptal et
                stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

                // 2. Tam ekrana geçişi başlat
                stage.setFullScreen(true);

                // 3. Ekran boyutlarını al
                Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();

                Platform.runLater(() -> {
                    // --- ADIM A: Boyutları Ekrana Eşitle ---
                    stage.setX(screenBounds.getMinX());
                    stage.setY(screenBounds.getMinY());
                    stage.setWidth(screenBounds.getWidth());
                    stage.setHeight(screenBounds.getHeight());

                    // --- ADIM B (KELEPÇE): Min ve Max boyutları sabitle ---
                    // Pencerenin alabileceği en küçük ve en büyük boyutu 
                    // ekran boyutuna kilitle. Resizing fiziksel olarak imkansız olur.
                    stage.setMinWidth(screenBounds.getWidth());
                    stage.setMinHeight(screenBounds.getHeight());
                    stage.setMaxWidth(screenBounds.getWidth());
                    stage.setMaxHeight(screenBounds.getHeight());

                    // --- ADIM C: İşletim Sistemine de bildir ---
                    stage.setResizable(false);
                });

            } else {
                // Tam ekrandan çık
                stage.setFullScreen(false);

                // --- KİLİDİ AÇ ---
                stage.setResizable(true);

                // DÜZELTME BURADA:
                // 800 ve 600 yerine, uygulamanızın gerçek minimum değerlerini yazıyoruz.
                stage.setMinWidth(1024);
                stage.setMinHeight(768);

                stage.setMaxWidth(Double.MAX_VALUE);
                stage.setMaxHeight(Double.MAX_VALUE);

                stage.setFullScreenExitKeyCombination(KeyCombination.keyCombination("Esc"));

                // Eski boyutuna döndür
                stage.setWidth(1280);
                stage.setHeight(800);
                stage.centerOnScreen();
            }
        } catch (Exception e) {
            System.err.println("macOS tam ekran hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
