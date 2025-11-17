package com.saferoom.gui.utils;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.input.KeyCombination;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class MacOSFullscreenHandler {

    // İşletim sisteminin macOS olup olmadığını kontrol eder
    public static boolean isMacOS() {
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("mac");
    }

    // Tam ekran geçişini yöneten ana metot
    public static void handleMacOSFullscreen(Stage stage, boolean enterFullscreen) {
        if (stage == null) {
            return;
        }

        try {
            if (enterFullscreen) {
                // 1. macOS üst menü çubuğunu (Apple logosu vs.) gizlemek için 
                // varsayılan ESC çıkışını iptal et (Kiosk modu gibi davranır)
                stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

                // 2. Önce Tam Ekrana geç
                stage.setFullScreen(true);

                // 3. (Agent Tavsiyesi) Sahnenin tüm ekranı kapladığından emin olmak için
                // işletim sistemine "Ben ekranın köşesindeyim ve tam boyuttayım" sinyali ver.
                Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();

                // Animasyonların bitmesi için çok kısa bir gecikme ile boyutları zorla
                Platform.runLater(() -> {
                    stage.setX(screenBounds.getMinX());
                    stage.setY(screenBounds.getMinY());
                    stage.setWidth(screenBounds.getWidth());
                    stage.setHeight(screenBounds.getHeight());
                });

            } else {
                // Tam ekrandan çık
                stage.setFullScreen(false);

                // Çıkış tuşunu normale döndür
                stage.setFullScreenExitKeyCombination(KeyCombination.keyCombination("Esc"));

                // Makul bir boyuta geri dön ve ortala
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
