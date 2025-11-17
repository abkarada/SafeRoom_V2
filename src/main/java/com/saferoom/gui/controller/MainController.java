package com.saferoom.gui.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.kordamp.ikonli.javafx.FontIcon;

import com.jfoenix.controls.JFXButton;
import com.saferoom.gui.MainApp;
import com.saferoom.gui.dialog.ActiveCallDialog;
import com.saferoom.gui.dialog.IncomingCallDialog;
import com.saferoom.gui.utils.AlertUtils;
import com.saferoom.gui.utils.MacOSFullscreenHandler;
import com.saferoom.gui.utils.UserSession;
import com.saferoom.gui.utils.WindowStateManager;
import com.saferoom.webrtc.CallManager;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class MainController {

    private static MainController instance;

    // FXML Değişkenleri
    @FXML
    private BorderPane mainPane;
    @FXML
    public StackPane contentArea;
    @FXML
    private VBox navBox;
    @FXML
    private JFXButton dashboardButton;
    @FXML
    private JFXButton roomsButton;
    @FXML
    private JFXButton messagesButton;
    @FXML
    private JFXButton friendsButton;
    @FXML
    private JFXButton fileVaultButton;
    // @FXML private JFXButton settingsButton; <-- KALDIRILDI
    @FXML
    private JFXButton notificationsButton;
    @FXML
    private StackPane profileBox;
    @FXML
    private Label userAvatar;
    @FXML
    private Region statusDot;

    // Window control buttons
    @FXML
    private JFXButton minimizeButton;
    @FXML
    private JFXButton maximizeButton;
    @FXML
    private JFXButton closeButton;

    // Active call dialog reference (for incoming calls handled by MainController)
    private ActiveCallDialog currentActiveCallDialog;

    // User status types
    public enum UserStatus {
        ONLINE("status-dot-online"),
        IDLE("status-dot-idle"),
        DND("status-dot-dnd"),
        OFFLINE("status-dot-offline");

        private final String styleClass;

        UserStatus(String styleClass) {
            this.styleClass = styleClass;
        }

        public String getStyleClass() {
            return styleClass;
        }
    }

    private UserStatus currentStatus = UserStatus.ONLINE;
    private ContextMenu userMenu;
    private List<MenuItem> mainMenuItems = new ArrayList<>();
    private List<MenuItem> statusMenuItems = new ArrayList<>();
    private boolean showingStatusSheet = false;

    // User Info
    private String getCurrentUserName() {
        return UserSession.getInstance().getDisplayName();
    }

    private String getCurrentUserInitials() {
        return UserSession.getInstance().getUserInitials();
    }

    // Window state manager instance
    private WindowStateManager windowStateManager = new WindowStateManager();

    @FXML
    public void initialize() {
        instance = this;

        // Buton olay atamaları
        dashboardButton.setOnAction(event -> handleDashboard());
        roomsButton.setOnAction(event -> handleRooms());
        messagesButton.setOnAction(event -> handleMessages());
        friendsButton.setOnAction(event -> handleFriends());
        fileVaultButton.setOnAction(event -> handleFileVault());
        notificationsButton.setOnAction(event -> System.out.println("Notifications clicked!"));

        // Window control button events
        minimizeButton.setOnAction(event -> handleMinimize());
        maximizeButton.setOnAction(event -> handleMaximize());
        closeButton.setOnAction(event -> handleClose());

        // Window drag functionality for undecorated window
        windowStateManager.setupWindowDrag(mainPane);

        // Pencere konumunu geri yükle (varsa)
        mainPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && newScene.getWindow() instanceof Stage) {
                Stage stage = (Stage) newScene.getWindow();
                // Pencere tamamen yüklendikten sonra konum geri yükle ve transparent yap
                javafx.application.Platform.runLater(() -> {
                    WindowStateManager.restoreWindowState(stage);
                    // Tüm scene'leri transparent yap (beyaz köşeleri önlemek için)
                    newScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                });
            }
        });

        // Initialize user status
        setUserStatus(UserStatus.ONLINE);

        // Initialize user avatar with first letter of username
        if (userAvatar != null) {
            userAvatar.setText("U"); // You can replace this with actual username first letter
        }

        // 🔧 Initialize WebRTC CallManager on startup
        String currentUsername = UserSession.getInstance().getDisplayName();
        if (currentUsername != null && !currentUsername.equals("Username")) {
            System.out.printf("[MainController] 🎬 Initializing CallManager for user: %s%n", currentUsername);
            try {
                CallManager callManager = CallManager.getInstance();
                callManager.initialize(currentUsername);

                // 🔧 Setup global incoming call handler
                setupGlobalCallCallbacks(callManager);

                System.out.println("[MainController] ✅ CallManager initialized - ready to receive calls");
            } catch (Exception e) {
                System.err.printf("[MainController] ❌ Failed to initialize CallManager: %s%n", e.getMessage());
                e.printStackTrace();
            }
        }

        // 🎯 P2P Registration: Register self to signaling server
        if (currentUsername != null && !currentUsername.equals("Username")) {
            // com.saferoom.p2p.P2PConnectionManager.getInstance().registerSelf(currentUsername); // P2P system removed
            System.out.println("📝 P2P registration disabled - using server relay only");
        }

        // Build user menu and bind to profile box
        buildUserContextMenu();
        if (profileBox != null) {
            profileBox.setOnMouseClicked(e -> {
                if (userMenu != null) {
                    if (userMenu.isShowing()) {
                        userMenu.hide();
                    } else {
                        showUserMenuWithDynamicPosition();
                    }
                }
            });
        }

        // Başlangıçta Dashboard'u yükle
        handleDashboard();

        // Ana scene'i transparent yap
        javafx.application.Platform.runLater(() -> {
            if (mainPane.getScene() != null) {
                mainPane.getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
            }
        });
    }

    public void setUserStatus(UserStatus status) {
        if (statusDot != null) {
            // Remove all status classes
            statusDot.getStyleClass().removeAll(
                    UserStatus.ONLINE.getStyleClass(),
                    UserStatus.IDLE.getStyleClass(),
                    UserStatus.DND.getStyleClass(),
                    UserStatus.OFFLINE.getStyleClass()
            );
            // Add the new status class
            statusDot.getStyleClass().add(status.getStyleClass());
            currentStatus = status;
            // Refresh user menu selection visuals if open
            boolean reopenUser = userMenu != null && userMenu.isShowing();
            if (userMenu != null) {
                userMenu.hide();
            }
            buildUserContextMenu();
            if (reopenUser && profileBox != null) {
                showUserMenuWithDynamicPosition();
                // restore sheet view
                userMenu.getItems().setAll(showingStatusSheet ? statusMenuItems : mainMenuItems);
            }
        }
    }

    public UserStatus getUserStatus() {
        return currentStatus;
    }

    private void showUserMenuWithDynamicPosition() {
        if (userMenu == null || profileBox == null) {
            return;
        }

        // Get the scene and window bounds
        javafx.scene.Scene scene = profileBox.getScene();
        if (scene == null) {
            return;
        }

        javafx.stage.Window window = scene.getWindow();
        if (window == null) {
            return;
        }

        // Get the profile box bounds in scene coordinates
        javafx.geometry.Bounds profileBounds = profileBox.localToScene(profileBox.getBoundsInLocal());

        // Calculate the estimated menu height based on current menu items
        int currentMenuItemCount = userMenu.getItems().size();
        if (currentMenuItemCount == 0) {
            // If menu hasn't been populated yet, estimate based on main menu
            currentMenuItemCount = 7; // header + separator + status + separator + settings + help + separator + logout
        }

        double estimatedMenuHeight = currentMenuItemCount * 32 + 20; // ~32px per item + padding

        // Calculate position
        double offsetX = 8; // Small gap from profile
        double windowHeight = scene.getHeight();
        double profileBottomY = profileBounds.getMaxY();
        double profileTopY = profileBounds.getMinY();

        // Calculate optimal position
        double offsetY;
        double spaceBelow = windowHeight - profileBottomY;
        double spaceAbove = profileTopY;

        if (estimatedMenuHeight <= spaceBelow - 10) {
            // Enough space below the profile - position menu below
            offsetY = profileBounds.getHeight() + 5;
        } else if (estimatedMenuHeight <= spaceAbove - 10) {
            // Not enough space below, but enough above - position menu above
            offsetY = -estimatedMenuHeight - 5;
        } else {
            // Not enough space in either direction - position to fit within window
            // Try to center the menu vertically around the profile, but keep it in bounds
            double idealCenterY = profileBounds.getMinY() + (profileBounds.getHeight() / 2);
            double menuTop = idealCenterY - (estimatedMenuHeight / 2);

            // Adjust if menu would go outside window bounds
            if (menuTop < 10) {
                menuTop = 10; // Keep 10px margin from top
            } else if (menuTop + estimatedMenuHeight > windowHeight - 10) {
                menuTop = windowHeight - estimatedMenuHeight - 10; // Keep 10px margin from bottom
            }

            offsetY = menuTop - profileBounds.getMinY();
        }

        userMenu.show(profileBox, Side.RIGHT, offsetX, offsetY);
    }

    private void buildUserContextMenu() {
        userMenu = new ContextMenu();
        userMenu.getStyleClass().add("user-menu");
        mainMenuItems.clear();
        statusMenuItems.clear();
        showingStatusSheet = false;

        // Header with avatar and name
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label avatar = new Label(getCurrentUserInitials());
        avatar.getStyleClass().add("user-avatar-header");
        // Name and status
        Label nameLabel = new Label(getCurrentUserName());
        nameLabel.getStyleClass().add("user-name");
        header.getChildren().addAll(avatar, nameLabel);
        CustomMenuItem headerItem = new CustomMenuItem(header, false);

        // Status main row that opens a popup selection
        HBox statusMainRow = new HBox(10);
        statusMainRow.setAlignment(Pos.CENTER_LEFT);
        statusMainRow.getStyleClass().add("user-menu-item");
        Pane currentDot = new Pane();
        currentDot.getStyleClass().addAll("status-dot-menu", currentStatus.getStyleClass());
        Label statusLabel = new Label("Status");
        statusLabel.getStyleClass().add("user-menu-text");
        Pane grow1 = new Pane();
        HBox.setHgrow(grow1, javafx.scene.layout.Priority.ALWAYS);
        FontIcon arrow = new FontIcon("fas-chevron-right");
        arrow.getStyleClass().add("user-menu-arrow");
        statusMainRow.getChildren().addAll(currentDot, statusLabel, grow1, arrow);
        CustomMenuItem statusOpenItem = new CustomMenuItem(statusMainRow, false);
        statusMainRow.setOnMouseClicked(e -> {
            userMenu.getItems().setAll(statusMenuItems);
            showingStatusSheet = true;
        });

        // Other actions
        CustomMenuItem settingsItem = actionRow("Settings", "fas-cog", this::handleSettings);
        CustomMenuItem helpItem = actionRow("Help", "far-question-circle", () -> AlertUtils.showInfo("Help", "Help is coming soon."));
        CustomMenuItem logoutItem = actionRow("Log out", "fas-sign-out-alt", this::handleLogout);

        mainMenuItems.add(headerItem);
        mainMenuItems.add(new SeparatorMenuItem());
        mainMenuItems.add(statusOpenItem);
        mainMenuItems.add(new SeparatorMenuItem());
        mainMenuItems.add(settingsItem);
        mainMenuItems.add(helpItem);
        mainMenuItems.add(new SeparatorMenuItem());
        mainMenuItems.add(logoutItem);
        userMenu.getItems().setAll(mainMenuItems);

        // Build status sheet (with Back)
        CustomMenuItem backItem = actionRow("Back", "fas-chevron-left", () -> {
            userMenu.getItems().setAll(mainMenuItems);
            showingStatusSheet = false;
        });
        statusMenuItems.add(backItem);
        statusMenuItems.add(new SeparatorMenuItem());
        statusMenuItems.add(statusRow("Online", UserStatus.ONLINE));
        statusMenuItems.add(statusRow("Idle", UserStatus.IDLE));
        statusMenuItems.add(statusRow("Do Not Disturb", UserStatus.DND));
        statusMenuItems.add(statusRow("Offline", UserStatus.OFFLINE));
    }

    private CustomMenuItem actionRow(String text, String iconLiteral, Runnable action) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("user-menu-item");
        FontIcon icon = new FontIcon(iconLiteral);
        icon.getStyleClass().add("user-menu-icon");
        Label label = new Label(text);
        label.getStyleClass().add("user-menu-text");
        row.getChildren().addAll(icon, label);
        row.setOnMouseClicked(e -> {
            // Auto-close menu when clicking menu items
            if (userMenu != null && userMenu.isShowing()) {
                userMenu.hide();
            }
            action.run();
        });
        return new CustomMenuItem(row, false);
    }

    private CustomMenuItem statusRow(String text, UserStatus status) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("user-menu-item");
        Pane dot = new Pane();
        dot.getStyleClass().addAll("status-dot-menu", status.getStyleClass());
        Label label = new Label(text);
        label.getStyleClass().add("user-menu-text");
        FontIcon check = new FontIcon("fas-check");
        check.getStyleClass().add("check-icon");
        check.setVisible(currentStatus == status);
        row.getChildren().addAll(dot, label, new Pane());
        // keep check mark aligned right
        Pane grow = new Pane();
        HBox.setHgrow(grow, javafx.scene.layout.Priority.ALWAYS);
        row.getChildren().set(2, grow);
        row.getChildren().add(check);
        row.setOnMouseClicked(e -> setUserStatus(status));
        return new CustomMenuItem(row, false);
    }

    public void handleSettings() {
        clearActiveButton();
        loadView("SettingsView.fxml");
    }

    public void handleLogout() {
        try {
            // Get current username before clearing session
            String currentUsername = UserSession.getInstance().getDisplayName();

            // Stop heartbeat service and end session
            com.saferoom.gui.utils.HeartbeatService.getInstance().stopHeartbeat(currentUsername);

            // Clear user session
            UserSession.getInstance().clearSession();

            // Close current main window
            Stage currentStage = (Stage) mainPane.getScene().getWindow();
            currentStage.close();

            // Open login window
            Stage loginStage = new Stage();
            loginStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            loginStage.setTitle("SafeRoom - Login");

            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader();
            loader.setLocation(getClass().getResource("/view/LoginView.fxml"));
            javafx.scene.Parent root = loader.load();

            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT); // Transparency ekle

            // Add CSS styling
            String cssPath = "/styles/styles.css";
            java.net.URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            // Enable window dragging
            final double[] xOffset = {0};
            final double[] yOffset = {0};
            root.setOnMousePressed(event -> {
                xOffset[0] = event.getSceneX();
                yOffset[0] = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                loginStage.setX(event.getScreenX() - xOffset[0]);
                loginStage.setY(event.getScreenY() - yOffset[0]);
            });

            loginStage.setResizable(false);
            loginStage.setScene(scene);
            loginStage.show();

        } catch (java.io.IOException e) {
            e.printStackTrace();
            AlertUtils.showError("Error", "Failed to logout. Please try again.");
        }
    }

    /**
     * Ana pencereye geri dönüş için kullanılır (meeting view'lardan)
     */
    public void returnToMainView() {
        // Sidebar'ı tekrar göster
        showSidebarFromFullScreen();
        handleDashboard(); // Dashboard'a geri dön
    }

    /**
     * Sidebar'ı gizleyerek full screen mod aktif eder
     */
    public void hideSidebarForFullScreen() {
        // Tüm sol sidebar'ı gizle (navigation + profil + bildirimler dahil)
        if (mainPane != null && mainPane.getLeft() != null) {
            mainPane.getLeft().setVisible(false);
            mainPane.getLeft().setManaged(false);
        }

        // Content area'yı sol kenardan başlat (sidebar'ın bulunduğu alan dahil)
        if (contentArea != null) {
            // BorderPane'daki content area'nın sol margin'ını 0 yap
            javafx.scene.layout.BorderPane.setMargin(contentArea, new javafx.geometry.Insets(0));

            // Content area'ı parent BorderPane'in center kısmına tam yerleştir
            if (contentArea.getParent() instanceof BorderPane) {
                BorderPane parentPane = (BorderPane) contentArea.getParent().getParent();
                if (parentPane != null) {
                    javafx.scene.layout.BorderPane.setMargin(parentPane, new javafx.geometry.Insets(0));
                }
            }
        }
    }

    /**
     * Sidebar'ı tekrar göstererek normal moda döner
     */
    public void showSidebarFromFullScreen() {
        // Tüm sol sidebar'ı tekrar göster
        if (mainPane != null && mainPane.getLeft() != null) {
            mainPane.getLeft().setVisible(true);
            mainPane.getLeft().setManaged(true);
        }

        // Content area'yı normal konumuna döndür
        if (contentArea != null) {
            // Normal margin değerlerini geri yükle
            javafx.scene.layout.BorderPane.setMargin(contentArea, new javafx.geometry.Insets(0));

            if (contentArea.getParent() instanceof BorderPane) {
                BorderPane parentPane = (BorderPane) contentArea.getParent().getParent();
                if (parentPane != null) {
                    javafx.scene.layout.BorderPane.setMargin(parentPane, new javafx.geometry.Insets(0));
                }
            }
        }
    }

    // Window control methods
    private void handleMinimize() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        stage.setIconified(true);
    }

    private void handleMaximize() {
        Stage stage = (Stage) mainPane.getScene().getWindow();

        // İşletim sistemini kontrol et
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isMacOS = osName.contains("mac");
        // Linux/Unix kontrolü ekle
        boolean isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix");

        // DÜZELTME BURADA:
        // Eğer işletim sistemi Mac VEYA Linux ise, bizim yazdığımız özel Handler'ı kullan.
        if (isMacOS || isLinux) {

            if (stage.isFullScreen()) {
                // Çıkış (Linux için de çalışır)
                MacOSFullscreenHandler.handleMacOSFullscreen(stage, false);

                if (maximizeButton != null && maximizeButton.getGraphic() instanceof FontIcon) {
                    ((FontIcon) maximizeButton.getGraphic()).setIconLiteral("far-square");
                }
            } else {
                // Giriş (Linux için de çalışır)
                MacOSFullscreenHandler.handleMacOSFullscreen(stage, true);

                if (maximizeButton != null && maximizeButton.getGraphic() instanceof FontIcon) {
                    ((FontIcon) maximizeButton.getGraphic()).setIconLiteral("far-clone");
                }
            }
        } else {
            // Windows: Standart JavaFX davranışı (Windows şeffaf pencereleri daha iyi yönetir)
            if (stage.isMaximized()) {
                stage.setMaximized(false);
                if (maximizeButton != null && maximizeButton.getGraphic() instanceof FontIcon) {
                    ((FontIcon) maximizeButton.getGraphic()).setIconLiteral("far-square");
                }
            } else {
                stage.setMaximized(true);
                if (maximizeButton != null && maximizeButton.getGraphic() instanceof FontIcon) {
                    ((FontIcon) maximizeButton.getGraphic()).setIconLiteral("far-clone");
                }
            }
        }
    }

    private void handleClose() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        stage.close();
    }

    public static MainController getInstance() {
        return instance;
    }

    private void handleDashboard() {
        setActiveButton(dashboardButton);
        loadView("DashBoardView.fxml");
    }

    public void handleRooms() {
        setActiveButton(roomsButton);
        loadView("RoomsView.fxml");
    }

    public void handleMessages() {
        setActiveButton(messagesButton);
        loadView("MessagesView.fxml");
    }

    public void handleFriends() {
        setActiveButton(friendsButton);
        loadView("FriendsView.fxml");
    }

    public void handleFileVault() {
        setActiveButton(fileVaultButton);
        loadView("FileVaultView.fxml");
    }

    /**
     * Programmatically switch to Messages tab (for P2P notifications)
     */
    public void switchToMessages() {
        System.out.println("[GUI] 📱 Programmatically switching to Messages tab");
        handleMessages();
    }

    public void handleProfile(String username) {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(MainApp.class.getResource("/view/ProfileView.fxml")));
            Parent root = loader.load();

            // Get the controller and set profile data
            ProfileController profileController = loader.getController();
            profileController.setProfileData(username);

            contentArea.getChildren().setAll(root);
            clearActiveButton(); // No specific button for profile view
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            showErrorInContentArea("Profil görünümü yüklenemedi");
        }
    }
    // private void handleSettings() { ... } <-- METOT KALDIRILDI

    public void loadSecureRoomView() {
        clearActiveButton(); // Aktif butonu temizle
        loadFullScreenView("SecureRoomView.fxml", true);
    }

    public void loadJoinMeetView() {
        clearActiveButton(); // Aktif butonu temizle
        loadFullScreenView("JoinMeetView.fxml", false);
    }

    public void loadServerView(String serverName, String serverIcon) {
        setActiveButton(roomsButton);
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/view/ServerView.fxml"));
            Parent root = loader.load();

            // Get the controller and set server info
            ServerController controller = loader.getController();
            controller.enterServer(serverName, serverIcon);
            controller.setMainController(this); // Pass reference to maintain window controls

            contentArea.getChildren().setAll(root);
        } catch (IOException e) {
            e.printStackTrace();
            showErrorInContentArea("ServerView.fxml yüklenemedi.");
        }
    }

    private void loadFullScreenView(String fxmlFile, boolean isSecureRoom) {
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/view/" + fxmlFile));
            Parent root = loader.load();

            // İçerik değiştirme yaklaşımını kullan (scene değiştirme yerine)
            if (isSecureRoom) {
                SecureRoomController controller = loader.getController();
                controller.setMainController(this); // Ana controller referansını ver
            } else {
                JoinMeetController controller = loader.getController();
                controller.setMainController(this); // Ana controller referansını ver
            }

            // Sidebar'ı gizle ve content area'yı tam genişliğe çıkar
            hideSidebarForFullScreen();

            // Content area'ya yeni görünümü yükle
            contentArea.getChildren().setAll(root);

        } catch (IOException e) {
            e.printStackTrace();
            showErrorInContentArea(fxmlFile + " yüklenemedi.");
        }
    }

    private void loadView(String fxmlFile) {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(MainApp.class.getResource("/view/" + fxmlFile)));
            Parent root = loader.load();
            contentArea.getChildren().setAll(root);
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            showErrorInContentArea("Görünüm yüklenemedi: " + fxmlFile);
        }
    }

    private void showErrorInContentArea(String message) {
        Label errorLabel = new Label(message + "\nLütfen dosya yolunu ve içeriğini kontrol edin.");
        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 16px; -fx-alignment: center;");
        errorLabel.setWrapText(true);
        VBox errorBox = new VBox(errorLabel);
        errorBox.setAlignment(Pos.CENTER);
        contentArea.getChildren().setAll(errorBox);
    }

    private void setActiveButton(JFXButton activeButton) {
        clearActiveButton();
        activeButton.getStyleClass().add("active");
    }

    private void clearActiveButton() {
        navBox.getChildren().forEach(node -> node.getStyleClass().remove("active"));
    }

    /**
     * Refresh user information in all views after login
     */
    public void refreshUserInfo() {
        // Update user menu header with new user info
        buildUserContextMenu();

        // If currently showing dashboard or settings, refresh those views
        if (contentArea.getChildren().size() > 0) {
            try {
                Parent currentView = (Parent) contentArea.getChildren().get(0);

                // Check if it's dashboard view and refresh
                if (currentView.getId() != null && currentView.getId().equals("dashboardView")) {
                    handleDashboard(); // Reload dashboard
                }

                // Check if it's settings view and refresh  
                if (currentView.getId() != null && currentView.getId().equals("settingsView")) {
                    handleSettings(); // Reload settings
                }

            } catch (Exception e) {
                System.err.println("Error refreshing user info: " + e.getMessage());
            }
        }
    }

    /**
     * 🎯 Setup global WebRTC incoming call callbacks This must be called once
     * during initialization to handle incoming calls globally
     */
    private void setupGlobalCallCallbacks(CallManager callManager) {
        System.out.println("[MainController] 📞 Setting up global incoming call handler");

        callManager.setOnIncomingCallCallback(callInfo -> {
            Platform.runLater(() -> {
                System.out.printf("[MainController] 🔔 Incoming %s call from: %s (callId: %s)%n",
                        callInfo.videoEnabled ? "video" : "audio",
                        callInfo.callerUsername,
                        callInfo.callId);

                System.out.println("[MainController] 🎬 About to create IncomingCallDialog...");

                try {
                    // Show incoming call dialog
                    IncomingCallDialog dialog = new IncomingCallDialog(
                            callInfo.callerUsername,
                            callInfo.callId,
                            callInfo.videoEnabled
                    );

                    System.out.println("[MainController] 📺 Dialog created, calling show()...");

                    // Handle user response
                    CompletableFuture<Boolean> dialogResult = dialog.show();

                    System.out.printf("[MainController] 📺 Dialog shown, future isDone=%b%n", dialogResult.isDone());

                    dialogResult.thenAccept(accepted -> {
                        System.out.printf("[MainController] 🎯 Dialog response received: %b%n", accepted);
                        if (accepted) {
                            System.out.printf("[MainController] ✅ Call accepted from: %s (callId: %s)%n",
                                    callInfo.callerUsername, callInfo.callId);

                            // Close incoming dialog
                            dialog.close();

                            // Accept the call
                            callManager.acceptCall(callInfo.callId);

                            // Open ActiveCallDialog
                            Platform.runLater(() -> {
                                currentActiveCallDialog = new ActiveCallDialog(
                                        callInfo.callerUsername,
                                        callInfo.callId,
                                        callInfo.videoEnabled,
                                        callManager
                                );
                                currentActiveCallDialog.show();
                                System.out.println("[MainController] 📺 ActiveCallDialog opened after accepting call");

                                // Attach local video track if video enabled
                                if (callInfo.videoEnabled) {
                                    dev.onvoid.webrtc.media.video.VideoTrack localVideo = callManager.getLocalVideoTrack();
                                    System.out.printf("[MainController] 🔍 Local video track: %s%n",
                                            localVideo != null ? "EXISTS" : "NULL");

                                    if (localVideo != null) {
                                        currentActiveCallDialog.attachLocalVideo(localVideo);
                                        System.out.println("[MainController] 📹 Local video attached to dialog");
                                    } else {
                                        System.err.println("[MainController] ❌ ERROR: Local video track is NULL! Cannot attach.");
                                    }
                                } else {
                                    System.out.println("[MainController] ℹ️ Video not enabled for this call");
                                }
                            });
                        } else {
                            System.out.printf("[MainController] ❌ Call rejected from: %s (callId: %s)%n",
                                    callInfo.callerUsername, callInfo.callId);
                            callManager.rejectCall(callInfo.callId);
                        }
                    });

                } catch (Exception e) {
                    System.err.printf("[MainController] ❌ Failed to show incoming call dialog: %s%n", e.getMessage());
                    e.printStackTrace();
                }
            });
        });

        // Handle call ended (close dialog)
        callManager.setOnCallEndedCallback(callId -> {
            Platform.runLater(() -> {
                System.out.printf("[MainController] 📴 Call ended: %s%n", callId);

                // Close active call dialog if it exists
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.close();
                    currentActiveCallDialog = null;
                    System.out.println("[MainController] ✅ ActiveCallDialog closed");
                }
            });
        });

        System.out.println("[MainController] ✅ Global incoming call handler registered");
    }
}
