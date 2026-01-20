package com.saferoom.gui.dialog;

import com.saferoom.gui.components.VideoPanel;
import com.saferoom.gui.dialog.ScreenSourcePickerDialog;
import com.saferoom.webrtc.CallManager;
import com.saferoom.webrtc.pipeline.FrameRenderResult;
import com.saferoom.webrtc.screenshare.ScreenShareController;
import com.saferoom.webrtc.screenshare.ScreenSourceOption;
import dev.onvoid.webrtc.media.video.VideoTrack;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.time.Duration;
import java.time.Instant;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import java.util.Optional;
import com.saferoom.log.Logger;

/**
 * Dialog for active WebRTC call
 * Shows video preview, controls (mute, camera toggle, end call), and call
 * duration
 */
public class ActiveCallDialog {

    private static final Logger logger = Logger.getLogger(ActiveCallDialog.class);

    private final Stage stage;
    private final String remoteUsername;
    private final String callId;
    private final boolean videoEnabled;
    private final CallManager callManager;
    private ScreenShareController screenShareController;

    // UI Components
    private Label durationLabel;
    private Button muteButton;
    private Button cameraButton;
    private Button shareScreenButton; // Start/stop screen sharing
    private Button endCallButton;
    private Button screenToggleButton; // Toggle between camera and screen share
    private VideoPanel localVideoPanel; // Local camera preview
    private VideoPanel remoteVideoPanel; // Remote user's video/camera
    private VideoPanel remoteScreenPanel; // Remote user's screen share
    private StackPane videoArea; // Container for video panels

    // State
    private boolean isMuted = false;
    private boolean isCameraOn = true;
    private boolean isSharingScreen = false; // Currently sharing my screen
    private boolean isShowingScreen = false; // Currently showing screen vs camera
    private boolean hasRemoteScreen = false; // Remote peer is sharing screen
    private Instant callStartTime;
    private Timeline durationTimer;

    /**
     * Create active call dialog
     * 
     * @param remoteUsername Remote user's username
     * @param callId         Unique call identifier
     * @param videoEnabled   Whether video is enabled
     * @param callManager    CallManager instance
     */
    public ActiveCallDialog(String remoteUsername, String callId, boolean videoEnabled, CallManager callManager) {
        this.remoteUsername = remoteUsername;
        this.callId = callId;
        this.videoEnabled = videoEnabled;
        this.callManager = callManager;
        this.screenShareController = callManager.getScreenShareController();
        this.callStartTime = Instant.now();
        this.stage = createDialog();

        // Register remote track callback to automatically attach remote video
        registerRemoteTrackCallback();

        // 🔧 SELF-VIEW FIX: Attach local video immediately if available
        if (videoEnabled) {
            VideoTrack localTrack = callManager.getLocalVideoTrack();
            if (localTrack != null) {
                System.out.println("[ActiveCallDialog] 📸 Attaching local video (immediate)");
                attachLocalVideo(localTrack);
            } else {
                System.out.println("[ActiveCallDialog] ⏳ Local video not ready yet, waiting for callback...");
            }

            // Also listen for async track creation (e.g. for incoming calls)
            callManager.setOnLocalTracksReadyCallback(() -> {
                javafx.application.Platform.runLater(() -> {
                    VideoTrack track = callManager.getLocalVideoTrack();
                    if (track != null) {
                        System.out.println("[ActiveCallDialog] 📸 Attaching local video (callback)");
                        attachLocalVideo(track);
                    }
                });
            });
        }
    }

    private Stage createDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.DECORATED);
        dialog.setTitle("Active Call - " + remoteUsername);
        dialog.setResizable(true);
        dialog.setMinWidth(640);
        dialog.setMinHeight(480);

        // Main container
        BorderPane mainContainer = new BorderPane();
        mainContainer.getStyleClass().add("dialog-container");

        // ========== TOP BAR ==========
        HBox topBar = new HBox(10);
        topBar.setPadding(new Insets(10, 15, 10, 15));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");

        Label userLabel = new Label(remoteUsername);
        userLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        durationLabel = new Label("00:00");
        durationLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        topBar.getChildren().addAll(userLabel, spacer, durationLabel);
        mainContainer.setTop(topBar);

        // ========== CENTER (VIDEO AREA) ==========
        videoArea = new StackPane();
        videoArea.setStyle("-fx-background-color: #1a1a1a;");

        // Remote video panel (full screen) - Canvas for actual video rendering
        remoteVideoPanel = new VideoPanel(640, 480);
        remoteVideoPanel.setStyle("-fx-background-color: #2c3e50;");
        // Bind size to video area
        remoteVideoPanel.widthProperty().bind(videoArea.widthProperty());
        remoteVideoPanel.heightProperty().bind(videoArea.heightProperty());

        // Remote screen share panel (initially hidden)
        remoteScreenPanel = new VideoPanel(640, 480);
        remoteScreenPanel.setStyle("-fx-background-color: #1a1a1a;");
        remoteScreenPanel.widthProperty().bind(videoArea.widthProperty());
        remoteScreenPanel.heightProperty().bind(videoArea.heightProperty());
        remoteScreenPanel.setVisible(false);
        remoteScreenPanel.setManaged(false);
        remoteScreenPanel.pauseRendering();
        remoteScreenPanel.pauseRendering();

        // Local video preview (small, bottom-right corner) - Canvas for local camera
        if (videoEnabled) {
            localVideoPanel = new VideoPanel(160, 120);
            localVideoPanel.setStyle(
                    "-fx-background-color: #34495e; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, 0, 0);");

            // Position in bottom-right corner
            StackPane.setAlignment(localVideoPanel, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(localVideoPanel, new Insets(15));

            videoArea.getChildren().addAll(remoteVideoPanel, remoteScreenPanel, localVideoPanel);
        } else {
            videoArea.getChildren().addAll(remoteVideoPanel, remoteScreenPanel);
        }

        mainContainer.setCenter(videoArea);

        // ========== BOTTOM BAR (CONTROLS) ==========
        HBox controlBar = new HBox(20);
        controlBar.setPadding(new Insets(15));
        controlBar.setAlignment(Pos.CENTER);
        controlBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        // Mute/Unmute button
        muteButton = createControlButton(
                FontAwesomeSolid.MICROPHONE,
                "#3498db",
                "Mute");
        muteButton.setOnAction(e -> toggleMute());

        // Camera toggle button (only for video calls)
        cameraButton = createControlButton(
                FontAwesomeSolid.VIDEO,
                "#9b59b6",
                "Turn Off Camera");
        cameraButton.setOnAction(e -> toggleCamera());
        cameraButton.setVisible(videoEnabled);
        cameraButton.setManaged(videoEnabled);

        // Share screen button
        shareScreenButton = createControlButton(
                FontAwesomeSolid.DESKTOP,
                "#27ae60",
                "Share Screen");
        shareScreenButton.setOnAction(e -> handleShareScreen());

        // Ensure screen share control is enabled across all platforms
        checkScreenCaptureCapability();

        // Screen share toggle button (initially hidden)
        screenToggleButton = createControlButton(
                FontAwesomeSolid.DESKTOP,
                "#2ecc71",
                "Switch to Screen");
        screenToggleButton.setOnAction(e -> toggleScreenView());
        screenToggleButton.setVisible(false);
        screenToggleButton.setManaged(false);

        // End call button
        endCallButton = createControlButton(
                FontAwesomeSolid.PHONE_SLASH,
                "#e74c3c",
                "End Call");
        endCallButton.setOnAction(e -> endCall());

        controlBar.getChildren().addAll(muteButton, cameraButton, shareScreenButton, screenToggleButton, endCallButton);
        mainContainer.setBottom(controlBar);

        // Create scene
        Scene scene = new Scene(mainContainer, 800, 600);
        try {
            scene.getStylesheets().add(
                    getClass().getResource("/styles/styles.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("[ActiveCallDialog] Warning: Could not load styles.css");
        }

        dialog.setScene(scene);

        // Handle window close
        dialog.setOnCloseRequest(e -> {
            e.consume(); // Prevent default close
            endCall();
        });

        return dialog;
    }

    /**
     * Create a control button with icon
     */
    private Button createControlButton(FontAwesomeSolid icon, String color, String tooltip) {
        Button button = new Button();
        FontIcon buttonIcon = new FontIcon(icon);
        buttonIcon.setIconSize(24);
        button.setGraphic(buttonIcon);
        button.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                color));

        // Hover effect
        String hoverColor = adjustBrightness(color, 0.8);
        button.setOnMouseEntered(e -> button.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                hoverColor)));
        button.setOnMouseExited(e -> button.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                color)));

        return button;
    }

    /**
     * Adjust color brightness (simple darkening)
     */
    private String adjustBrightness(String hexColor, double factor) {
        // Simple darkening by reducing RGB values
        try {
            Color color = Color.web(hexColor);
            return String.format("#%02x%02x%02x",
                    (int) (color.getRed() * 255 * factor),
                    (int) (color.getGreen() * 255 * factor),
                    (int) (color.getBlue() * 255 * factor));
        } catch (Exception e) {
            return hexColor;
        }
    }

    /**
     * Toggle microphone mute
     */
    private void toggleMute() {
        isMuted = !isMuted;

        if (callManager != null) {
            // FIX: Offload logic to virtual thread to avoid blocking UI
            callManager.runAsync(() -> callManager.toggleAudio(!isMuted));
        }

        FontIcon icon = new FontIcon(isMuted ? FontAwesomeSolid.MICROPHONE_SLASH : FontAwesomeSolid.MICROPHONE);
        icon.setIconSize(24);
        muteButton.setGraphic(icon);

        String color = isMuted ? "#e74c3c" : "#3498db";
        muteButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                color));

        System.out.printf("[ActiveCallDialog] Microphone %s%n", isMuted ? "muted" : "unmuted");
    }

    /**
     * Toggle camera on/off
     */
    private void toggleCamera() {
        isCameraOn = !isCameraOn;

        if (callManager != null) {
            // FIX: Offload logic to virtual thread to avoid blocking UI
            callManager.runAsync(() -> callManager.toggleVideo(isCameraOn));
        }

        FontIcon icon = new FontIcon(isCameraOn ? FontAwesomeSolid.VIDEO : FontAwesomeSolid.VIDEO_SLASH);
        icon.setIconSize(24);
        cameraButton.setGraphic(icon);

        String color = isCameraOn ? "#9b59b6" : "#e74c3c";
        cameraButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                color));

        // Update local preview visibility
        if (localVideoPanel != null) {
            localVideoPanel.setVisible(isCameraOn);
            if (isCameraOn) {
                localVideoPanel.resumeRendering();
            } else {
                localVideoPanel.pauseRendering();
            }
        }

        System.out.printf("[ActiveCallDialog] Camera %s%n", isCameraOn ? "enabled" : "disabled");
    }

    /**
     * Check if screen capture is available on this system
     * Runs in background thread to avoid blocking UI
     * CRITICAL: Prevents native crashes by testing capability first
     * 
     * NOTE: On Linux, screen capture requires:
     * - PipeWire (modern audio/video server)
     * - XDG Desktop Portal (screen capture API)
     * - Portal implementation (xdg-desktop-portal-gtk/kde/wlr)
     * 
     * Check: pipewire --version && xdg-desktop-portal --version
     */
    private void checkScreenCaptureCapability() {
        javafx.application.Platform.runLater(() -> {
            if (shareScreenButton != null) {
                shareScreenButton.setDisable(false);
                shareScreenButton.setOpacity(1.0);
                shareScreenButton.setTooltip(new javafx.scene.control.Tooltip("Share Screen"));
            }
        });
    }

    /**
     * Handle share screen button click
     * Opens screen picker dialog and starts/stops screen sharing
     */
    private void handleShareScreen() {
        ScreenShareController controller = requireScreenShareController();
        if (controller == null) {
            return;
        }
        if (controller.isSharing()) {
            stopScreenSharing();
        } else {
            startScreenSharing();
        }
    }

    /**
     * Start screen sharing
     */
    private void startScreenSharing() {
        ScreenShareController controller = requireScreenShareController();
        if (controller == null) {
            return;
        }
        try (ScreenSourcePickerDialog dialog = new ScreenSourcePickerDialog(controller)) {
            Optional<ScreenSourceOption> selection = dialog.showAndWait(stage);
            if (selection.isEmpty()) {
                updateShareScreenButton();
                return;
            }
            setShareButtonBusy(true);
            controller.startScreenShare(selection.get())
                    .whenComplete((ignored, error) -> Platform.runLater(() -> {
                        setShareButtonBusy(false);
                        if (error != null) {
                            showScreenShareError("Failed to start screen sharing", error);
                        }
                        isSharingScreen = controller.isSharing();
                        updateShareScreenButton();
                    }));
        } catch (Exception e) {
            showScreenShareError("Failed to open screen picker", e);
        }
    }

    /**
     * Stop screen sharing
     */
    private void stopScreenSharing() {
        ScreenShareController controller = requireScreenShareController();
        if (controller == null) {
            return;
        }
        setShareButtonBusy(true);
        controller.stopScreenShare()
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    setShareButtonBusy(false);
                    if (error != null) {
                        showScreenShareError("Failed to stop screen sharing", error);
                    }
                    isSharingScreen = controller.isSharing();
                    updateShareScreenButton();
                }));
    }

    /**
     * Update share screen button appearance
     */
    private void updateShareScreenButton() {
        if (shareScreenButton == null)
            return;
        isSharingScreen = screenShareController != null && screenShareController.isSharing();

        FontIcon icon = new FontIcon(isSharingScreen ? FontAwesomeSolid.STOP : FontAwesomeSolid.DESKTOP);
        icon.setIconSize(24);
        shareScreenButton.setGraphic(icon);

        String color = isSharingScreen ? "#e74c3c" : "#27ae60";
        String tooltip = isSharingScreen ? "Stop Sharing" : "Share Screen";

        shareScreenButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                        "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                        "-fx-background-radius: 30px;",
                color));

        shareScreenButton.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        System.out.printf("[ActiveCallDialog] Share screen button updated: isSharing=%b%n", isSharingScreen);
    }

    private ScreenShareController requireScreenShareController() {
        if (screenShareController == null && callManager != null) {
            screenShareController = callManager.getScreenShareController();
        }
        if (screenShareController == null) {
            showScreenShareError("Screen sharing is not available right now.", null);
        }
        return screenShareController;
    }

    private void setShareButtonBusy(boolean busy) {
        if (shareScreenButton != null) {
            shareScreenButton.setDisable(busy);
        }
    }

    private void showScreenShareError(String header, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Screen Share");
        alert.setHeaderText(header);
        if (error != null && error.getMessage() != null) {
            alert.setContentText(error.getMessage());
        } else {
            alert.setContentText(null);
        }
        alert.show();
    }

    /**
     * End the call
     */
    private void endCall() {
        System.out.printf("[ActiveCallDialog] Ending call: %s%n", callId);

        stopDurationTimer();

        if (callManager != null) {
            // FIX: Offload heavy cleanup to virtual thread
            callManager.runAsync(() -> callManager.endCall());
        }

        close();
    }

    /**
     * Start duration timer
     */
    private void startDurationTimer() {
        durationTimer = new Timeline(
                new KeyFrame(javafx.util.Duration.seconds(1), e -> updateDuration()));
        durationTimer.setCycleCount(Timeline.INDEFINITE);
        durationTimer.play();
    }

    /**
     * Stop duration timer
     */
    private void stopDurationTimer() {
        if (durationTimer != null) {
            durationTimer.stop();
        }
    }

    /**
     * Update call duration label
     */
    private void updateDuration() {
        Duration duration = Duration.between(callStartTime, Instant.now());
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        long hours = minutes / 60;

        String durationText;
        if (hours > 0) {
            durationText = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
        } else {
            durationText = String.format("%02d:%02d", minutes, seconds % 60);
        }

        durationLabel.setText(durationText);
    }

    /**
     * Attach local video track for preview
     */
    public void attachLocalVideo(VideoTrack track) {
        javafx.application.Platform.runLater(() -> {
            if (localVideoPanel != null && track != null) {
                System.out.println("[ActiveCallDialog] Attaching local video track");
                localVideoPanel.attachVideoTrack(track);
            }
        });
    }

    /**
     * Attach remote video track for display
     * With replaceTrack(), same track ID (video0) is used for camera and screen
     * share
     * We simply display the current video track content (camera or screen)
     */
    public void attachRemoteVideo(VideoTrack track) {
        javafx.application.Platform.runLater(() -> {
            if (track == null) {
                logger.warn("[ActiveCallDialog] Cannot attach null video track");
                return;
            }

            String trackId = track.getId();
            logger.info(String.format("📹 Attaching remote video track: %s (enabled=%b)", trackId, track.isEnabled()));

            // With replaceTrack(), the track content changes but ID stays same (video0)
            // Simply update the main video panel with the current track
            logger.info("📹 Updating video panel with current track");

            if (remoteVideoPanel != null) {
                logger.info("📹 remoteVideoPanel exists, attaching track...");
                // Detach old track first to ensure clean update
                remoteVideoPanel.detachVideoTrack();

                // Attach new track (will show camera or screen share depending on sender)
                remoteVideoPanel.attachVideoTrack(track);
                logger.info(String.format("📹 isShowingScreen=%b, will %s rendering",
                        isShowingScreen, isShowingScreen ? "PAUSE" : "RESUME"));
                if (isShowingScreen) {
                    remoteVideoPanel.pauseRendering();
                } else {
                    remoteVideoPanel.resumeRendering();
                }

                logger.info(String.format("✅ Remote video track attached: %s", trackId));
            } else {
                logger.error("❌ remoteVideoPanel is NULL! Cannot attach video track", null);
            }
        });
    }

    /**
     * Toggle between screen share and camera view
     */
    private void toggleScreenView() {
        if (isShowingScreen) {
            switchToCameraView();
        } else {
            switchToScreenView();
        }
    }

    /**
     * Switch to screen share view
     */
    private void switchToScreenView() {
        if (!hasRemoteScreen) {
            System.out.println("[ActiveCallDialog] ⚠️ No screen share available");
            return;
        }

        System.out.println("[ActiveCallDialog] 🖥️ Switching to screen view");

        remoteVideoPanel.setVisible(false);
        remoteVideoPanel.setManaged(false);
        remoteVideoPanel.pauseRendering();

        remoteScreenPanel.setVisible(true);
        remoteScreenPanel.setManaged(true);
        remoteScreenPanel.resumeRendering();

        isShowingScreen = true;

        // Update button
        if (screenToggleButton != null) {
            FontIcon icon = new FontIcon(FontAwesomeSolid.VIDEO);
            icon.setIconSize(24);
            screenToggleButton.setGraphic(icon);
        }
    }

    /**
     * Switch to camera view
     */
    private void switchToCameraView() {
        System.out.println("[ActiveCallDialog] 📹 Switching to camera view");

        remoteScreenPanel.setVisible(false);
        remoteScreenPanel.setManaged(false);
        remoteScreenPanel.pauseRendering();

        remoteVideoPanel.setVisible(true);
        remoteVideoPanel.setManaged(true);
        remoteVideoPanel.resumeRendering();

        isShowingScreen = false;

        // Update button
        if (screenToggleButton != null) {
            FontIcon icon = new FontIcon(FontAwesomeSolid.DESKTOP);
            icon.setIconSize(24);
            screenToggleButton.setGraphic(icon);
        }
    }

    /**
     * Detach all video tracks and dispose panels to prevent memory leaks
     */
    private void detachVideoTracks() {
        if (localVideoPanel != null) {
            localVideoPanel.dispose();
        }
        if (remoteVideoPanel != null) {
            remoteVideoPanel.dispose();
        }
        if (remoteScreenPanel != null) {
            remoteScreenPanel.dispose();
        }
    }

    /**
     * Handle remote screen share stop
     * Called when remote peer stops sharing screen
     */
    public void onRemoteScreenShareStopped() {
        System.out.println("[ActiveCallDialog] 🛑 Remote screen share stopped");

        javafx.application.Platform.runLater(() -> {
            hasRemoteScreen = false;

            // Hide screen toggle button
            if (screenToggleButton != null) {
                screenToggleButton.setVisible(false);
                screenToggleButton.setManaged(false);
            }

            // If currently showing screen, switch back to camera
            if (isShowingScreen) {
                switchToCameraView();
            }

            // Detach screen track
            if (remoteScreenPanel != null) {
                remoteScreenPanel.detachVideoTrack();
                remoteScreenPanel.pauseRendering();
            }

            if (remoteVideoPanel != null) {
                remoteVideoPanel.resumeRendering();
            }
        });
    }

    /**
     * Register callback to receive remote video tracks
     */
    private void registerRemoteTrackCallback() {
        logger.info("🔧 Registering remote track callback...");
        callManager.setOnRemoteTrackCallback(track -> {
            logger.info(String.format("📺 Remote track callback fired: kind=%s, id=%s, class=%s",
                    track.getKind(), track.getId(), track.getClass().getSimpleName()));

            javafx.application.Platform.runLater(() -> {
                logger.info(String.format("📺 Processing remote track on FX thread: kind=%s, id=%s",
                        track.getKind(), track.getId()));

                // Only attach video tracks
                if (track instanceof dev.onvoid.webrtc.media.video.VideoTrack) {
                    dev.onvoid.webrtc.media.video.VideoTrack videoTrack = (dev.onvoid.webrtc.media.video.VideoTrack) track;
                    logger.info("📹 Track is VideoTrack, calling attachRemoteVideo...");
                    attachRemoteVideo(videoTrack);
                    logger.info("📹 Remote video attachment complete");
                } else {
                    logger.info("🎤 Remote audio track (auto-handled by AudioDeviceModule)");
                }
            });
        });
        logger.info("✅ Remote track callback registered");
    }

    /**
     * Show the dialog
     */
    public void show() {
        stage.show();
        startDurationTimer();
    }

    /**
     * Close the dialog
     */
    public void close() {
        stopDurationTimer();
        detachVideoTracks(); // Clean up video resources

        // Clear all callbacks to prevent memory leaks from lambda references
        if (callManager != null) {
            callManager.setOnRemoteTrackCallback(null);
            callManager.setOnLocalTracksReadyCallback(null);
        }

        // Null out panel references to help GC
        localVideoPanel = null;
        remoteVideoPanel = null;
        remoteScreenPanel = null;

        // Clear the static buffer pool to free memory after call ends
        FrameRenderResult.clearBufferPool();

        if (stage.isShowing()) {
            stage.close();
        }
    }

    /**
     * Get the call ID
     */
    public String getCallId() {
        return callId;
    }

    /**
     * Get the remote username
     */
    public String getRemoteUsername() {
        return remoteUsername;
    }

    /**
     * Check if microphone is muted
     */
    public boolean isMuted() {
        return isMuted;
    }

    /**
     * Check if camera is on
     */
    public boolean isCameraOn() {
        return isCameraOn;
    }
}
