package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.client.ClientMenu;
import com.saferoom.gui.utils.AlertUtils;
import javafx.application.Platform; // Kapatma işlevi için import edildi
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.*;
import java.net.URL;
import java.util.Objects;
import java.util.Properties;

import com.saferoom.oauth.OAuthManager;
import com.saferoom.oauth.UserInfo;
import com.saferoom.gui.utils.UserSession;

public class LoginController {

    // --- FXML Değişkenleri ---
    @FXML
    private VBox rootPane;
    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private CheckBox rememberMe;
    @FXML
    private Hyperlink forgotPasswordLink;
    @FXML
    private Button signInButton;
    @FXML
    private JFXButton googleLoginButton;
    @FXML
    private JFXButton githubLoginButton;
    @FXML
    private Hyperlink signUpLink;
    @FXML
    private Button closeButton; // YENİ: Kapatma butonu eklendi

    private double xOffset = 0;
    private double yOffset = 0;

    // Remember Me Constants
    private static final String USER_PREFS_FILE = "user_prefs.properties";
    private static final String USERNAME_KEY = "saved_username";
    private static final String PASSWORD_KEY = "saved_password";
    private static final String REMEMBER_KEY = "remember_me";

    private boolean containsSqlInjection(String input) {
        // SQL injection pattern - daha hızlı regex kullanımı
        String sqlInjectionPattern = "(?i).*(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|OR\\s+\\d|AND\\s+\\d|--|/\\*|\\*/|xp_|sp_|'|\"|;|<|>|SCRIPT|IFRAME|ONLOAD).*";
        return input.matches(sqlInjectionPattern);
    }

    private boolean isValidUsernameOrEmail(String input) {
        // Username veya email için kontrol (@ karakteri dahil)
        return input.matches("^[a-zA-Z0-9._@-]+$");
    }

    private boolean isValidPassword(String password) {
        // Password için daha geniş karakter seti (özel karakterler dahil)
        return password.matches("^[a-zA-Z0-9._@#$%^&*()+=\\[\\]{}|\\\\:;\"'<>,.?/~`!-]+$");
    }

    private void logSecurityIncident(String attemptedUsername) {
        System.err.println("SECURITY ALERT: SQL Injection attempt detected!");
        System.err.println("Username attempt: " + attemptedUsername);
        System.err.println("Timestamp: " + java.time.LocalDateTime.now());
        System.err.println("This incident has been logged and will be reported.");

        // Buraya IP loglama, email gönderme vs ekleyebilirsin
    }

    @FXML
    public void initialize() {
        signInButton.setOnAction(event -> handleSignIn());
        signUpLink.setOnAction(event -> handleSignUp());
        forgotPasswordLink.setOnAction(event -> handleForgotPassword());
        googleLoginButton.setOnAction(event -> handleGoogleLogin());
        githubLoginButton.setOnAction(event -> handleGitHubLogin());
        passwordField.setOnAction(event -> handleSignIn());
        closeButton.setOnAction(event -> handleClose());

        // Load saved credentials if remember me was checked
        loadSavedCredentials();
    }

    private void handleSignIn() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Kullanıcı adı ve şifre alanlarını doldurun.");
            return;
        }
        if (containsSqlInjection(username) || containsSqlInjection(password)) {
            showError("Security Alert: Suspicious input detected. This will report on your IP!");
            logSecurityIncident(username);
            return;
        }

        if (username.length() > 50 || password.length() > 100) {
            showError("Username or password too long!");
            return;
        }

        if (!isValidUsernameOrEmail(username) || !isValidPassword(password)) {
            showError("Invalid characters detected. This will report on your IP!");
            return;
        }

        try {
            String loginResult = ClientMenu.Login(username, password);

            // CRITICAL: Check for null or error states FIRST
            if (loginResult == null) {
                showError("Connection error: No response from server");
                return;
            }

            // Check for all error states
            if (loginResult.equals("N_REGISTER")) {
                showError("User not registered!");
                return;
            } else if (loginResult.equals("WRONG_PASSWORD")) {
                showError("Wrong password!");
                return;
            } else if (loginResult.equals("BLOCKED_USER")) {
                showError("Account blocked! Please contact support.");
                return;
            } else if (loginResult.startsWith("ERROR")) {
                // Handle all ERROR_* variants
                if (loginResult.equals("ERROR_SERVER_UNAVAILABLE")) {
                    showError("Server is unavailable. Please check your connection.");
                } else if (loginResult.equals("ERROR_TIMEOUT")) {
                    showError("Connection timeout. Please try again.");
                } else if (loginResult.equals("ERROR_CONNECTION_FAILED")) {
                    showError("Connection failed. Please check if server is running.");
                } else {
                    showError("Connection error occurred!");
                }
                return;
            } else if (loginResult.contains("Exception") || loginResult.contains("Error")) {
                // Catch any exception strings that slipped through
                System.err.println("Unexpected error response: " + loginResult);
                showError("An error occurred during login. Please try again.");
                return;
            } else {
                // Login başarılı, loginResult eksik bilgiyi içeriyor
                // Save credentials if Remember Me is checked
                if (rememberMe.isSelected()) {
                    saveCredentials(username, password);
                } else {
                    clearSavedCredentials();
                }

                // Create UserInfo for traditional login and save to session
                UserInfo traditionalUser = new UserInfo();

                // Kullanıcının hangi şekilde giriş yaptığını ve eksik bilgiyi belirle
                String actualUsername;
                if (username.contains("@")) {
                    // Email ile giriş yapmış, loginResult username içeriyor
                    traditionalUser.setName(loginResult); // Server'dan gelen username
                    traditionalUser.setEmail(username); // Kullanıcının girdiği email
                    actualUsername = loginResult; // Actual username
                } else {
                    // Username ile giriş yapmış, loginResult email içeriyor
                    traditionalUser.setName(username); // Kullanıcının girdiği username
                    traditionalUser.setEmail(loginResult); // Server'dan gelen email
                    actualUsername = username; // Already username
                }

                traditionalUser.setProvider("Traditional");
                UserSession.getInstance().setCurrentUser(traditionalUser, "traditional");

                // NEW: Initialize Persistent Storage with password
                try {
                    System.out.println("[Login] Initializing persistent storage...");
                    ClientMenu.initializePersistentStorage(actualUsername, password);
                    System.out.println("[Login] ✅ Persistent storage ready!");
                } catch (Exception e) {
                    System.err.println("[Login] ⚠️ Persistent storage initialization failed: " + e.getMessage());
                    System.err.println("[Login] Messages will be stored in RAM only");
                    e.printStackTrace();
                }

                // Stop any existing heartbeat service before starting a new one
                com.saferoom.gui.utils.HeartbeatService heartbeatService = com.saferoom.gui.utils.HeartbeatService
                        .getInstance();
                if (heartbeatService.isRunning()) {
                    System.out.println("🛑 Stopping existing heartbeat service before starting new one");
                    heartbeatService.stopHeartbeat();
                    // Kısa bir bekle ki cleanup tamamlansın
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Start heartbeat service
                heartbeatService.startHeartbeat(traditionalUser.getName());

                // Register user for P2P communication
                Platform.runLater(() -> {
                    new Thread(() -> {
                        try {
                            boolean registered = com.saferoom.client.ClientMenu
                                    .registerP2PUser(traditionalUser.getName());
                            if (registered) {
                                System.out.println(
                                        "✅ P2P registration successful for user: " + traditionalUser.getName());

                                // ✅ P2P registered - WebRTC will handle NAT traversal automatically
                                System.out.println("✅ P2P ready for user: " + traditionalUser.getName());

                                // Initialize Rooms v1 Connection
                                com.saferoom.client.ClientMenu.initRoomsV1();
                            } else {
                                System.err.println("⚠️ P2P registration failed for user: " + traditionalUser.getName());
                            }
                        } catch (Exception e) {
                            System.err.println("❌ P2P registration error: " + e.getMessage());
                        }
                    }).start();
                });

                try {
                    Stage loginStage = (Stage) rootPane.getScene().getWindow();
                    loginStage.close();
                    Stage mainStage = new Stage();
                    mainStage.initStyle(StageStyle.TRANSPARENT);
                    mainStage.setTitle("SafeRoom");
                    Parent mainRoot = FXMLLoader
                            .load(Objects.requireNonNull(getClass().getResource("/view/MainView.fxml")));
                    Scene mainScene = new Scene(mainRoot, 1280, 800);
                    mainScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                    String cssPath = "/styles/styles.css";
                    URL cssUrl = getClass().getResource(cssPath);
                    if (cssUrl != null) {
                        mainScene.getStylesheets().add(cssUrl.toExternalForm());
                    }
                    mainStage.setScene(mainScene);
                    mainStage.setResizable(true);
                    mainStage.setMinWidth(1024);
                    mainStage.setMinHeight(768);
                    mainStage.show();

                    // Refresh user info in MainController after successful traditional login
                    Platform.runLater(() -> {
                        MainController mainController = MainController.getInstance();
                        if (mainController != null) {
                            mainController.refreshUserInfo();
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                    showError("Ana sayfa yüklenemedi.");
                }
            }
        } catch (io.grpc.StatusRuntimeException e) {
            System.err.println("gRPC Connection Error: " + e.getMessage());
            if (e.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
                showError("Server connection failed. Please check if the server is running.");
            } else if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
                showError("Server is unavailable. Please try again later.");
            } else {
                showError("Connection error: " + e.getStatus().getDescription());
            }
        } catch (Exception e) {
            System.err.println("Unexpected error during login: " + e.getMessage());
            e.printStackTrace();
            showError("An unexpected error occurred. Please try again.");
        }

    }

    private void handleSignUp() {
        System.out.println("Kayıt ekranına geçiş yapılıyor...");
        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            Stage registerStage = new Stage();
            registerStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/RegisterView.fxml")));

            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                registerStage.setX(event.getScreenX() - xOffset);
                registerStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }
            registerStage.setScene(scene);
            registerStage.setResizable(false);
            registerStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * YENİ: Uygulamayı tamamen kapatır.
     */
    private void handleClose() {
        System.out.println("Uygulama kapatılıyor...");
        Platform.exit();
    }

    // ... (Diğer metodlar değişmedi) ...
    private void handleForgotPassword() {
        System.out.println("Navigating to forgot password screen...");

        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            Stage forgotPasswordStage = new Stage();
            forgotPasswordStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader
                    .load(Objects.requireNonNull(getClass().getResource("/view/ForgotPasswordView.fxml")));

            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                forgotPasswordStage.setX(event.getScreenX() - xOffset);
                forgotPasswordStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }
            forgotPasswordStage.setScene(scene);
            forgotPasswordStage.setResizable(false);
            forgotPasswordStage.show();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Failed to load forgot password screen.");
        }
    }

    private void handleGoogleLogin() {
        System.out.println("Starting Google OAuth...");

        // Show loading state
        googleLoginButton.setDisable(true);
        googleLoginButton.setText("Connecting...");

        OAuthManager.authenticateWithGoogle(userInfo -> {
            // Reset button state
            googleLoginButton.setDisable(false);
            googleLoginButton.setText("Google");

            if (userInfo != null) {
                System.out.println("Google OAuth successful: " + userInfo);

                // Check if user exists in database, if not create account
                handleOAuthSuccess(userInfo);
            } else {
                showError("Google authentication failed. Please try again.");
            }
        });
    }

    private void handleGitHubLogin() {
        System.out.println("Starting GitHub OAuth...");

        // Show loading state
        githubLoginButton.setDisable(true);
        githubLoginButton.setText("Connecting...");

        OAuthManager.authenticateWithGitHub(userInfo -> {
            // Reset button state
            githubLoginButton.setDisable(false);
            githubLoginButton.setText("GitHub");

            if (userInfo != null) {
                System.out.println("GitHub OAuth successful: " + userInfo);

                // Check if user exists in database, if not create account
                handleOAuthSuccess(userInfo);
            } else {
                showError("GitHub authentication failed. Please try again.");
            }
        });
    }

    /**
     * Handle successful OAuth authentication
     */
    private void handleOAuthSuccess(UserInfo userInfo) {
        try {
            // Save user session
            UserSession.getInstance().setCurrentUser(userInfo, "oauth");

            // Start heartbeat service
            com.saferoom.gui.utils.HeartbeatService.getInstance().startHeartbeat(userInfo.getName());

            // Register user for P2P communication
            Platform.runLater(() -> {
                new Thread(() -> {
                    try {
                        boolean registered = com.saferoom.client.ClientMenu.registerP2PUser(userInfo.getName());
                        if (registered) {
                            System.out.println("✅ P2P registration successful for OAuth user: " + userInfo.getName());

                            // ✅ P2P registered - WebRTC will handle NAT traversal automatically
                            System.out.println("✅ P2P ready for OAuth user: " + userInfo.getName());

                            // Initialize Rooms v1 Connection
                            com.saferoom.client.ClientMenu.initRoomsV1();
                        } else {
                            System.err.println("⚠️ P2P registration failed for OAuth user: " + userInfo.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("❌ P2P registration error: " + e.getMessage());
                    }
                }).start();
            });

            // TODO: Check if user exists in database
            // If not, create user account with OAuth info

            // For now, directly proceed to main view
            proceedToMainView(userInfo);

        } catch (Exception e) {
            System.err.println("OAuth success handling error: " + e.getMessage());
            showError("Authentication completed but login failed. Please try again.");
        }
    }

    /**
     * Proceed to main application view
     */
    private void proceedToMainView(UserInfo userInfo) {
        try {
            Stage loginStage = (Stage) rootPane.getScene().getWindow();
            loginStage.close();

            Stage mainStage = new Stage();
            mainStage.initStyle(StageStyle.TRANSPARENT);
            mainStage.setTitle("SafeRoom - " + userInfo.getName());

            Parent mainRoot = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/MainView.fxml")));
            Scene mainScene = new Scene(mainRoot, 1280, 800);
            mainScene.setFill(javafx.scene.paint.Color.TRANSPARENT);

            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                mainScene.getStylesheets().add(cssUrl.toExternalForm());
            }

            mainStage.setScene(mainScene);
            mainStage.setResizable(true);
            mainStage.setMinWidth(1024);
            mainStage.setMinHeight(768);
            mainStage.show();

            // Refresh user info in MainController after OAuth login
            Platform.runLater(() -> {
                if (MainController.getInstance() != null) {
                    MainController.getInstance().refreshUserInfo();
                }
            });

            System.out.println("Successfully logged in with " + userInfo.getProvider() + ": " + userInfo.getEmail());

        } catch (IOException e) {
            e.printStackTrace();
            showError("Ana sayfa yüklenemedi.");
        }
    }

    private void showAlert(String title, String content) {
        AlertUtils.showInfo(title, content);
    }

    private void showError(String message) {
        AlertUtils.showError("Hata", message);
    }

    // Remember Me Helper Methods
    private void loadSavedCredentials() {
        try {
            File prefsFile = new File(USER_PREFS_FILE);
            if (prefsFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(prefsFile)) {
                    props.load(fis);

                    String savedUsername = props.getProperty(USERNAME_KEY);
                    String savedPassword = props.getProperty(PASSWORD_KEY);
                    String rememberMeValue = props.getProperty(REMEMBER_KEY);

                    if (savedUsername != null && "true".equals(rememberMeValue)) {
                        usernameField.setText(savedUsername);

                        if (savedPassword != null) {
                            // Decrypt password
                            String decryptedPassword = simpleDecrypt(savedPassword);
                            passwordField.setText(decryptedPassword);
                        }

                        rememberMe.setSelected(true);
                        signInButton.requestFocus(); // Focus signin button if both fields are filled
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load saved credentials: " + e.getMessage());
        }
    }

    private void saveCredentials(String username, String password) {
        try {
            Properties props = new Properties();
            props.setProperty(USERNAME_KEY, username);

            // Encrypt password before saving
            String encryptedPassword = simpleEncrypt(password);
            props.setProperty(PASSWORD_KEY, encryptedPassword);
            props.setProperty(REMEMBER_KEY, "true");

            try (FileOutputStream fos = new FileOutputStream(USER_PREFS_FILE)) {
                props.store(fos, "SafeRoom User Preferences");
            }
            System.out.println("Credentials saved successfully.");
        } catch (Exception e) {
            System.err.println("Failed to save credentials: " + e.getMessage());
        }
    }

    private void clearSavedCredentials() {
        try {
            File prefsFile = new File(USER_PREFS_FILE);
            if (prefsFile.exists()) {
                prefsFile.delete();
            }
            System.out.println("Saved credentials cleared.");
        } catch (Exception e) {
            System.err.println("Failed to clear saved credentials: " + e.getMessage());
        }
    }

    // Simple XOR encryption for local password storage
    // NOT cryptographically secure, but better than plain text
    private String simpleEncrypt(String text) {
        StringBuilder result = new StringBuilder();
        String key = "SafeRoomKey2025"; // Simple key

        for (int i = 0; i < text.length(); i++) {
            char textChar = text.charAt(i);
            char keyChar = key.charAt(i % key.length());
            char encryptedChar = (char) (textChar ^ keyChar);
            result.append(String.format("%02X", (int) encryptedChar));
        }

        return result.toString();
    }

    private String simpleDecrypt(String encryptedText) {
        StringBuilder result = new StringBuilder();
        String key = "SafeRoomKey2025"; // Same key

        try {
            for (int i = 0; i < encryptedText.length(); i += 2) {
                String hexByte = encryptedText.substring(i, i + 2);
                int encryptedChar = Integer.parseInt(hexByte, 16);
                char keyChar = key.charAt((i / 2) % key.length());
                char decryptedChar = (char) (encryptedChar ^ keyChar);
                result.append(decryptedChar);
            }
        } catch (Exception e) {
            System.err.println("Failed to decrypt password: " + e.getMessage());
            return "";
        }

        return result.toString();
    }
}
