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
// CallManager import REMOVED - use reflection to prevent WebRTC loading at startup

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
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class MainController {

    private static MainController instance;

    // --- HEADER VE SIDEBAR KONTROLLERİ İÇİN FXML DEĞİŞKENLERİ ---
    @FXML
    private BorderPane headerBar;
    @FXML
    private HBox windowControlsLeft;    // macOS için sol taraf
    @FXML
    private HBox windowControlsRight;   // Windows için sağ taraf
    @FXML
    private HBox sidebarHeader;
    // ------------------------------------------------------------

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
    @FXML
    private JFXButton notificationsButton;
    @FXML
    private StackPane profileBox;
    @FXML
    private Label userAvatar;
    @FXML
    private Region statusDot;

    // Window control buttons - Windows
    @FXML
    private JFXButton minimizeButton;
    @FXML
    private JFXButton maximizeButton;
    @FXML
    private JFXButton closeButton;

    // Window control buttons - macOS
    @FXML
    private JFXButton closeButtonMac;
    @FXML
    private JFXButton minimizeButtonMac;
    @FXML
    private JFXButton maximizeButtonMac;

    private ActiveCallDialog currentActiveCallDialog;

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

    private String getCurrentUserName() {
        return UserSession.getInstance().getDisplayName();
    }

    private String getCurrentUserInitials() {
        return UserSession.getInstance().getUserInitials();
    }

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

        // Pencere Kontrollerini Özelleştir (Mac vs Windows)
        customizeWindowControls();

        // Window drag functionality
        windowStateManager.setupWindowDrag(mainPane);

        // Pencere konumunu geri yükle
        mainPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && newScene.getWindow() instanceof Stage) {
                Stage stage = (Stage) newScene.getWindow();
                javafx.application.Platform.runLater(() -> {
                    WindowStateManager.restoreWindowState(stage);
                    newScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                });
            }
        });

        // Initialize user status
        setUserStatus(UserStatus.ONLINE);

        if (userAvatar != null) {
            userAvatar.setText(getCurrentUserInitials());
        }

        // ⚡ LAZY LOADING: WebRTC/CallManager artık burada başlatılmıyor!
        // RAM optimizasyonu: WebRTC sadece arama yapılınca/alınınca yüklenecek
        // ⚡ LAZY LOADING: CallManager NOT initialized at startup
        // This prevents loading WebRTC native library (~200-300 MB on Windows)
        // CallManager will auto-initialize when first call is made
        String currentUsername = UserSession.getInstance().getDisplayName();
        if (currentUsername != null && !currentUsername.equals("Username")) {
            System.out.println("[MainController] ⚡ CallManager DISABLED at startup (will load on first call)");
            System.out.println("📝 P2P registration disabled - using server relay only");
        }

        // User menu
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

        javafx.application.Platform.runLater(() -> {
            if (mainPane.getScene() != null) {
                mainPane.getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
            }
        });
    }

    public static MainController getInstance() {
        return instance;
    }

    private void customizeWindowControls() {
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isMacOS = osName.contains("mac");

        if (isMacOS) {
            // macOS: Sol taraftaki butonları göster, sağ taraftakileri gizle
            if (windowControlsLeft != null) {
                windowControlsLeft.setVisible(true);
                windowControlsLeft.setManaged(true);
            }
            if (windowControlsRight != null) {
                windowControlsRight.setVisible(false);
                windowControlsRight.setManaged(false);
            }

            // macOS butonlarına event handlers ekle
            if (closeButtonMac != null) {
                closeButtonMac.setOnAction(event -> handleClose());
            }
            if (minimizeButtonMac != null) {
                minimizeButtonMac.setOnAction(event -> handleMinimize());
            }
            if (maximizeButtonMac != null) {
                maximizeButtonMac.setOnAction(event -> handleMaximize());
            }

            // Sidebar header'ı temizle (artık buraya hiçbir şey eklenmeyecek)
            if (sidebarHeader != null) {
                sidebarHeader.getChildren().clear();
            }

        } else {
            // Windows/Linux: Sağ taraftaki butonları göster, sol taraftakileri gizle
            if (windowControlsRight != null) {
                windowControlsRight.setVisible(true);
                windowControlsRight.setManaged(true);
            }
            if (windowControlsLeft != null) {
                windowControlsLeft.setVisible(false);
                windowControlsLeft.setManaged(false);
            }

            // Windows butonlarına event handlers ekle
            if (minimizeButton != null) {
                minimizeButton.setOnAction(event -> handleMinimize());
            }
            if (maximizeButton != null) {
                maximizeButton.setOnAction(event -> handleMaximize());
            }
            if (closeButton != null) {
                closeButton.setOnAction(event -> handleClose());
            }
        }
    }

    public void setUserStatus(UserStatus status) {
        if (statusDot != null) {
            statusDot.getStyleClass().removeAll(
                    UserStatus.ONLINE.getStyleClass(),
                    UserStatus.IDLE.getStyleClass(),
                    UserStatus.DND.getStyleClass(),
                    UserStatus.OFFLINE.getStyleClass()
            );
            statusDot.getStyleClass().add(status.getStyleClass());
            currentStatus = status;
            boolean reopenUser = userMenu != null && userMenu.isShowing();
            if (userMenu != null) {
                userMenu.hide();
            }
            buildUserContextMenu();
            if (reopenUser && profileBox != null) {
                showUserMenuWithDynamicPosition();
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
        javafx.scene.Scene scene = profileBox.getScene();
        if (scene == null) {
            return;
        }
        javafx.stage.Window window = scene.getWindow();
        if (window == null) {
            return;
        }

        javafx.geometry.Bounds profileBounds = profileBox.localToScene(profileBox.getBoundsInLocal());
        int currentMenuItemCount = userMenu.getItems().size();
        if (currentMenuItemCount == 0) {
            currentMenuItemCount = 7;
        }

        double estimatedMenuHeight = currentMenuItemCount * 32 + 20;
        double offsetX = 8;
        double windowHeight = scene.getHeight();
        double profileBottomY = profileBounds.getMaxY();
        double profileTopY = profileBounds.getMinY();
        double offsetY;
        double spaceBelow = windowHeight - profileBottomY;
        double spaceAbove = profileTopY;

        if (estimatedMenuHeight <= spaceBelow - 10) {
            offsetY = profileBounds.getHeight() + 5;
        } else if (estimatedMenuHeight <= spaceAbove - 10) {
            offsetY = -estimatedMenuHeight - 5;
        } else {
            double idealCenterY = profileBounds.getMinY() + (profileBounds.getHeight() / 2);
            double menuTop = idealCenterY - (estimatedMenuHeight / 2);
            if (menuTop < 10) {
                menuTop = 10;
            }else if (menuTop + estimatedMenuHeight > windowHeight - 10) {
                menuTop = windowHeight - estimatedMenuHeight - 10;
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

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label avatar = new Label(getCurrentUserInitials());
        avatar.getStyleClass().add("user-avatar-header");
        Label nameLabel = new Label(getCurrentUserName());
        nameLabel.getStyleClass().add("user-name");
        header.getChildren().addAll(avatar, nameLabel);
        CustomMenuItem headerItem = new CustomMenuItem(header, false);

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
            String currentUsername = UserSession.getInstance().getDisplayName();
            com.saferoom.gui.utils.HeartbeatService.getInstance().stopHeartbeat(currentUsername);
            UserSession.getInstance().clearSession();
            Stage currentStage = (Stage) mainPane.getScene().getWindow();
            currentStage.close();

            Stage loginStage = new Stage();
            loginStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            loginStage.setTitle("SafeRoom - Login");

            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/view/LoginView.fxml"));
            Parent root = loader.load();

            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            java.net.URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

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
        } catch (IOException e) {
            e.printStackTrace();
            AlertUtils.showError("Error", "Failed to logout. Please try again.");
        }
    }

    public void returnToMainView() {
        showSidebarFromFullScreen();
        handleDashboard();
    }

    public void hideSidebarForFullScreen() {
        if (mainPane != null && mainPane.getLeft() != null) {
            mainPane.getLeft().setVisible(false);
            mainPane.getLeft().setManaged(false);
        }
        if (contentArea != null) {
            BorderPane.setMargin(contentArea, new javafx.geometry.Insets(0));
        }
    }

    public void showSidebarFromFullScreen() {
        if (mainPane != null && mainPane.getLeft() != null) {
            mainPane.getLeft().setVisible(true);
            mainPane.getLeft().setManaged(true);
        }
        if (contentArea != null) {
            BorderPane.setMargin(contentArea, new javafx.geometry.Insets(0));
        }
    }

    private void handleMinimize() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        stage.setIconified(true);
    }

    private void handleMaximize() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isMacOS = osName.contains("mac");
        boolean isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix");

        if (isMacOS || isLinux) {
            if (stage.isFullScreen()) {
                MacOSFullscreenHandler.handleMacOSFullscreen(stage, false);
                // macOS butonunun ikonunu güncelle
                if (maximizeButtonMac != null && maximizeButtonMac.getGraphic() instanceof Region) {
                    maximizeButtonMac.getGraphic().getStyleClass().removeAll("mac-button-maximize-fullscreen");
                }
            } else {
                MacOSFullscreenHandler.handleMacOSFullscreen(stage, true);
                // macOS butonunun ikonunu güncelle
                if (maximizeButtonMac != null && maximizeButtonMac.getGraphic() instanceof Region) {
                    maximizeButtonMac.getGraphic().getStyleClass().add("mac-button-maximize-fullscreen");
                }
            }
        } else {
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
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(MainApp.class.getResource("/view/MessagesView.fxml")));
            Parent root = loader.load();
            MessagesController messagesController = loader.getController();
            messagesController.setOnNavigateToFriendsRequest(this::handleFriends);
            contentArea.getChildren().setAll(root);
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            showErrorInContentArea("MessagesView.fxml yüklenemedi: " + e.getMessage());
        }
    }

    public void handleFriends() {
        setActiveButton(friendsButton);
        loadView("FriendsView.fxml");
    }

    public void handleFileVault() {
        setActiveButton(fileVaultButton);
        loadView("SecureFilesView.fxml");
    }

    public void switchToMessages() {
        System.out.println("[GUI] 📱 Programmatically switching to Messages tab");
        handleMessages();
    }

    public void handleProfile(String username) {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(MainApp.class.getResource("/view/ProfileView.fxml")));
            Parent root = loader.load();
            ProfileController profileController = loader.getController();
            profileController.setProfileData(username);
            contentArea.getChildren().setAll(root);
            clearActiveButton();
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            showErrorInContentArea("Profil görünümü yüklenemedi");
        }
    }

    public void loadSecureRoomView() {
        clearActiveButton();
        loadFullScreenView("SecureRoomView.fxml", true);
    }

    public void loadJoinMeetView() {
        clearActiveButton();
        loadFullScreenView("JoinMeetView.fxml", false);
    }

    public void loadServerView(String serverName, String serverIcon) {
        setActiveButton(roomsButton);
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/view/ServerView.fxml"));
            Parent root = loader.load();
            ServerController controller = loader.getController();
            controller.enterServer(serverName, serverIcon);
            controller.setMainController(this);
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
            if (isSecureRoom) {
                SecureRoomController controller = loader.getController();
                controller.setMainController(this);
            } else {
                JoinMeetController controller = loader.getController();
                controller.setMainController(this);
            }
            hideSidebarForFullScreen();
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

    public void refreshUserInfo() {
        buildUserContextMenu();
        if (contentArea.getChildren().size() > 0) {
            try {
                Parent currentView = (Parent) contentArea.getChildren().get(0);
                String id = currentView.getId();
                if (id != null) {
                    if (id.equals("dashboardView")) {
                        handleDashboard();
                    }else if (id.equals("settingsView")) {
                        handleSettings();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error refreshing user info: " + e.getMessage());
            }
        }
    }

    // ⚡ CallManager methods REMOVED to prevent WebRTC loading at startup
    // Call functionality will be handled by ChatViewController when user initiates a call
}