package com.saferoom.gui.controller;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.kordamp.ikonli.javafx.FontIcon;

import com.saferoom.gui.dialog.ActiveCallDialog;
import com.saferoom.gui.dialog.IncomingCallDialog;
import com.saferoom.gui.dialog.OutgoingCallDialog;
import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.search.MessageSearchPanel;
import com.saferoom.gui.search.SearchHit;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.storage.FTS5SearchService;
import com.saferoom.storage.LocalDatabase;
import com.saferoom.storage.SqlCipherHelper;
import com.saferoom.webrtc.CallManager;

import dev.onvoid.webrtc.media.video.VideoTrack;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import com.saferoom.gui.model.FileAttachment;
import com.saferoom.gui.model.MessageType;
import com.saferoom.storage.LocalMessageRepository;

public class ChatViewController {

    @FXML
    private BorderPane chatPane;

    // Header Components
    @FXML
    private Label chatPartnerAvatar;
    @FXML
    private Label chatPartnerName;
    @FXML
    private Label chatPartnerStatus;

    // Main Content
    @FXML
    private ListView<Message> messageListView;

    // Input Area
    @FXML
    private TextField messageInputField;
    @FXML
    private Button sendButton;
    @FXML
    private Button attachmentButton; // Hedef butonumuz

    // Header Buttons
    @FXML
    private Button phoneButton;
    @FXML
    private Button videoButton;

    // Sağ Panel (User Info) Bileşenleri
    @FXML
    private VBox userInfoSidebar;
    @FXML
    private Label infoAvatar;
    @FXML
    private Label infoName;
    @FXML
    private Label infoStatus;
    @FXML
    private Button infoAudioButton;
    @FXML
    private Button infoVideoButton;
    @FXML
    private Button infoSearchButton;

    // Shared Media UI
    @FXML
    private HBox sharedMediaContainer;
    @FXML
    private Label sharedMediaCount;
    @FXML
    private StackPane mediaPlaceholder1;
    @FXML
    private StackPane mediaPlaceholder2;
    @FXML
    private StackPane mediaPlaceholder3;
    @FXML
    private Button viewAllMediaBtn;

    // UI Areas
    @FXML
    private HBox chatHeader;
    @FXML
    private HBox chatInputArea;

    // Placeholders
    @FXML
    private VBox emptyChatPlaceholder; // Welcome Screen
    @FXML
    private VBox noMessagesPlaceholder; // In-Chat Empty Screen

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;
    private boolean autoScrollAtBottom = true;

    // WebRTC variables
    private OutgoingCallDialog currentOutgoingDialog;
    private IncomingCallDialog currentIncomingDialog;
    private ActiveCallDialog currentActiveCallDialog;
    private boolean callbacksSetup = false;
    private boolean currentCallVideoEnabled = false;

    // Search variables (legacy - keeping for compatibility)
    private FTS5SearchService searchService;
    private List<String> searchResultMessageIds;
    private int currentSearchIndex = -1;

    // NEW: WhatsApp-style search panel
    private MessageSearchPanel searchPanel;
    private String highlightedMessageId = null;

    @FXML
    public void initialize() {
        this.chatService = ChatService.getInstance();

        String username = chatService.getCurrentUsername();
        if (username != null) {
            this.currentUser = new User(username, username);
        } else {
            this.currentUser = new User("temp-id", "You");
        }

        // Global mesaj listener
        chatService.newMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && messages != null && messages.contains(newMsg) && autoScrollAtBottom) {
                messageListView.scrollTo(messages.size() - 1);
            }
        });

        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
        }

        // Buton Tanımlamaları (Derleme hatası çözüldü)
        if (phoneButton != null) {
            phoneButton.setOnAction(e -> handlePhoneCall());
        }
        if (videoButton != null) {
            videoButton.setOnAction(e -> handleVideoCall());
        }

        // YENİ BAĞLANTI: Attachment butonu menüyü açar
        if (attachmentButton != null) {
            attachmentButton.setOnAction(e -> handleAttachmentMenu());
        }

        // Avatara ve isme tıklayınca paneli aç
        if (chatPartnerAvatar != null) {
            chatPartnerAvatar.setOnMouseClicked(e -> handleUserInfoToggle());
        }
        if (chatPartnerName != null) {
            chatPartnerName.setOnMouseClicked(e -> handleUserInfoToggle());
        }

        // Başlangıçta Welcome Screen göster
        showWelcomeScreen();
        setupAutoScrollBehavior();
    }

    // --- YARDIMCI METOT: Özel stilli menü elemanları oluşturur ---
    private MenuItem createAttachmentMenuItem(String text, String iconCode, String filterDesc, String... filterExts) {
        MenuItem item = new MenuItem(text);

        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(18);
        item.setGraphic(icon);

        // Aksiyon: Dosya Seçiciyi aç ve filtreleri uygula
        item.setOnAction(e -> openFileExplorer(text, filterDesc, filterExts));

        // Özel stil (CSS)
        item.getStyleClass().add("chat-context-menu-item-custom");

        return item;
    }

    // --- YENİ ANA METOT: + Butonuna Tıklanınca Menüyü Açar ---
    @FXML
    private void handleAttachmentMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("chat-context-menu");

        // Menüye elemanları ekle
        menu.getItems().addAll(
                createAttachmentMenuItem("Photo & Video", "fas-camera", "Media Files", "*.png", "*.jpg", "*.jpeg",
                        "*.gif", "*.mp4", "*.mov"),
                createAttachmentMenuItem("Document", "fas-file-alt", "Document Files", "*.pdf", "*.doc", "*.docx",
                        "*.txt"),
                new SeparatorMenuItem(),
                createAttachmentMenuItem("Other File", "fas-paperclip", "All Files", "*.*"));

        // Menüyü + butonunun üstüne yaslayarak göster
        menu.show(attachmentButton, Side.TOP, 0, -5);
    }

    // --- FİLTRELİ DOSYA SEÇİCİ MANTIĞI (Menüden çağrılır) ---
    private void openFileExplorer(String title, String filterDesc, String... filterExts) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);

        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterDesc, filterExts));

        Stage stage = (Stage) attachmentButton.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            showFileSendConfirmation(selectedFile);
        }
    }

    // --- User Info Toggle Metodu ---
    @FXML
    private void handleUserInfoToggle() {
        if (userInfoSidebar == null) {
            return;
        }

        boolean isVisible = userInfoSidebar.isVisible();

        if (!isVisible) {
            // --- AÇILIŞ ---
            updateInfoPanel();
            userInfoSidebar.setVisible(true);
            userInfoSidebar.setManaged(true);

            userInfoSidebar.setTranslateX(320);
            TranslateTransition openSlide = new TranslateTransition(Duration.millis(250), userInfoSidebar);
            openSlide.setToX(0);
            openSlide.play();

        } else {
            // --- KAPANIŞ ---
            TranslateTransition closeSlide = new TranslateTransition(Duration.millis(250), userInfoSidebar);
            closeSlide.setToX(320);
            closeSlide.setOnFinished(e -> {
                userInfoSidebar.setVisible(false);
                userInfoSidebar.setManaged(false);
            });
            closeSlide.play();
        }
    }

    // Sağ paneldeki bilgileri güncelle
    private void updateInfoPanel() {
        if (infoName != null && chatPartnerName != null) {
            infoName.setText(chatPartnerName.getText());
        }

        if (infoStatus != null && chatPartnerStatus != null) {
            infoStatus.setText(chatPartnerStatus.getText());
        }

        if (infoAvatar != null && chatPartnerAvatar != null) {
            infoAvatar.setText(chatPartnerAvatar.getText());
        }
    }

    // Sağ paneldeki "X" butonu da bu metodu kullanır
    @FXML
    private void toggleUserInfo() {
        handleUserInfoToggle();
    }

    // --- Standart Chat Metotları ---
    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        // DEBUG: Check if messages are already loaded (from preload)
        System.out.println("[ChatView] initChannel: " + channelId + " - messages already in RAM: " + messages.size());

        messageListView.setItems(messages);
        messageListView.setCellFactory(param -> new MessageCell(currentUser.getId()));

        if (chatHeader != null) {
            chatHeader.setVisible(true);
            chatHeader.setManaged(true);
        }
        if (chatInputArea != null) {
            chatInputArea.setVisible(true);
            chatInputArea.setManaged(true);
        }

        updateViewVisibility();

        messages.addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                updateViewVisibility();
                if (c.wasAdded() && autoScrollAtBottom) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });

        // Load from disk ONLY if not already loaded (preload may have done this)
        if (messages.isEmpty()) {
            System.out.println("[ChatView] Messages empty, loading from disk...");
            loadConversationHistoryAsync(channelId);
        } else {
            System.out.println("[ChatView] Messages already loaded, skipping disk load");
            // Just scroll to bottom - no refresh needed since items are already bound
            messageListView.scrollTo(messages.size() - 1);
        }

        // Load shared media thumbnails for Contact Info sidebar
        loadSharedMedia(channelId);
    }

    /**
     * Load shared media (images, videos, documents) for the Contact Info sidebar
     * Shows up to 3 thumbnails with a count indicator
     */
    private void loadSharedMedia(String channelId) {
        if (!LocalDatabase.isInitialized()) {
            System.out.println("[ChatView] Database not initialized, skipping shared media load");
            return;
        }

        try {
            LocalMessageRepository repository = LocalMessageRepository.getInstance();
            String currentUser = chatService.getCurrentUsername();
            String conversationId = SqlCipherHelper.generateConversationId(currentUser, channelId);

            System.out.printf("[SharedMedia] 📂 Loading media for conversation: %s <-> %s%n", currentUser, channelId);
            System.out.printf("[SharedMedia] 📂 Conversation ID (hash): %s%n", conversationId.substring(0, 16) + "...");

            repository.loadMediaMessagesAsync(conversationId)
                    .thenAccept(mediaMessages -> {
                        System.out.printf("[SharedMedia] ✅ Found %d media files for %s%n", mediaMessages.size(),
                                channelId);
                        for (Message msg : mediaMessages) {
                            if (msg.getAttachment() != null) {
                                System.out.printf("[SharedMedia]    - %s (%s)%n",
                                        msg.getAttachment().getFileName(), msg.getType());
                            }
                        }
                        Platform.runLater(() -> {
                            updateSharedMediaUI(mediaMessages);
                        });
                    })
                    .exceptionally(error -> {
                        System.err.println("[SharedMedia] ❌ Failed to load: " + error.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[SharedMedia] ❌ Error: " + e.getMessage());
        }
    }

    /**
     * Update Shared Media UI with loaded media messages
     */
    private void updateSharedMediaUI(List<Message> mediaMessages) {
        // Clear existing thumbnails
        StackPane[] placeholders = { mediaPlaceholder1, mediaPlaceholder2, mediaPlaceholder3 };

        for (StackPane placeholder : placeholders) {
            if (placeholder != null) {
                placeholder.getChildren().clear();
                placeholder.setStyle("-fx-background-color: #2a2d31; -fx-background-radius: 8;");
            }
        }

        // Update count label
        int totalMedia = mediaMessages.size();
        if (sharedMediaCount != null) {
            if (totalMedia > 0) {
                sharedMediaCount.setText(totalMedia + " items");
                sharedMediaCount.setVisible(true);
            } else {
                sharedMediaCount.setText("");
                sharedMediaCount.setVisible(false);
            }
        }

        // Show "View All" button if more than 3 items
        if (viewAllMediaBtn != null) {
            viewAllMediaBtn.setVisible(totalMedia > 3);
            viewAllMediaBtn.setManaged(totalMedia > 3);

            // Set click handler for View All button
            final List<Message> allMedia = mediaMessages;
            viewAllMediaBtn.setOnAction(e -> openAllMediaModal(allMedia));
        }

        // Fill placeholders with thumbnails (up to 3)
        for (int i = 0; i < Math.min(3, mediaMessages.size()); i++) {
            Message mediaMsg = mediaMessages.get(i);
            FileAttachment attachment = mediaMsg.getAttachment();

            if (attachment != null && placeholders[i] != null) {
                StackPane placeholder = placeholders[i];

                // Try to load thumbnail
                Image thumbnail = attachment.getThumbnail();

                if (thumbnail == null && attachment.getLocalPath() != null) {
                    // Try to load from file path for images
                    try {
                        java.nio.file.Path filePath = attachment.getLocalPath();
                        if (java.nio.file.Files.exists(filePath)) {
                            String fileName = filePath.getFileName().toString().toLowerCase();
                            if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                                    fileName.endsWith(".jpeg") || fileName.endsWith(".gif")) {
                                thumbnail = new Image(filePath.toUri().toString(), 80, 80, true, true, true);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[ChatView] Failed to load thumbnail from path: " + e.getMessage());
                    }
                }

                // CRITICAL: Prevent focus stealing on first click
                placeholder.setFocusTraversable(false);
                placeholder.setPickOnBounds(true);

                if (thumbnail != null) {
                    ImageView imageView = new ImageView(thumbnail);
                    imageView.setFitWidth(80);
                    imageView.setFitHeight(80);
                    imageView.setPreserveRatio(true);
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 5, 0, 0, 2);");
                    imageView.setMouseTransparent(true); // Let clicks pass through to parent

                    placeholder.getChildren().add(imageView);
                    placeholder
                            .setStyle("-fx-background-color: transparent; -fx-background-radius: 8; -fx-cursor: hand;");
                } else {
                    // Show file type icon instead (with filename for accurate icon)
                    FontIcon icon = getFileTypeIcon(attachment.getTargetType(), attachment.getFileName());
                    icon.setMouseTransparent(true); // Let clicks pass through to parent
                    placeholder.getChildren().add(icon);
                    placeholder.setStyle("-fx-background-color: #2a2d31; -fx-background-radius: 8; -fx-cursor: hand;");
                }

                // Use MOUSE_PRESSED for immediate response (same fix as grid items)
                final FileAttachment finalAttachment = attachment;
                placeholder.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                    if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                        e.consume();
                        openSharedMediaFile(finalAttachment);
                    }
                });
            }
        }

        // If no media, show empty state
        if (mediaMessages.isEmpty()) {
            if (mediaPlaceholder1 != null) {
                Label emptyLabel = new Label("No media");
                emptyLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
                mediaPlaceholder1.getChildren().add(emptyLabel);
            }
        }
    }

    /**
     * Get appropriate icon for file type
     */
    private FontIcon getFileTypeIcon(MessageType type) {
        return getFileTypeIcon(type, null);
    }

    /**
     * Get appropriate icon for file type with filename for better detection
     */
    private FontIcon getFileTypeIcon(MessageType type, String fileName) {
        FontIcon icon = new FontIcon();
        icon.setIconSize(24);
        icon.setIconColor(javafx.scene.paint.Color.web("#94a1b2"));

        // First check by filename extension for more accurate icons
        if (fileName != null) {
            String lower = fileName.toLowerCase();

            // Text files
            if (lower.endsWith(".txt") || lower.endsWith(".log")) {
                icon.setIconLiteral("fas-file-alt");
                return icon;
            }
            // Code files
            if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js") ||
                    lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".sh")) {
                icon.setIconLiteral("fas-file-code");
                return icon;
            }
            // JSON/XML/Config files
            if (lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".yml") ||
                    lower.endsWith(".yaml") || lower.endsWith(".ini") || lower.endsWith(".conf")) {
                icon.setIconLiteral("fas-file-code");
                return icon;
            }
            // Markdown
            if (lower.endsWith(".md")) {
                icon.setIconLiteral("fas-file-alt");
                return icon;
            }
            // CSV/Excel
            if (lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                icon.setIconLiteral("fas-file-excel");
                return icon;
            }
            // Word documents
            if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
                icon.setIconLiteral("fas-file-word");
                return icon;
            }
            // PDF
            if (lower.endsWith(".pdf")) {
                icon.setIconLiteral("fas-file-pdf");
                return icon;
            }
            // Archive files
            if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                    lower.endsWith(".tar") || lower.endsWith(".gz")) {
                icon.setIconLiteral("fas-file-archive");
                return icon;
            }
            // Audio files
            if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") ||
                    lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
                icon.setIconLiteral("fas-file-audio");
                return icon;
            }
        }

        // Fallback to type-based icons
        if (type == null) {
            icon.setIconLiteral("fas-file");
            return icon;
        }

        switch (type) {
            case IMAGE:
                icon.setIconLiteral("fas-image");
                break;
            case VIDEO:
                icon.setIconLiteral("fas-video");
                break;
            case DOCUMENT:
                icon.setIconLiteral("fas-file-alt"); // Generic document icon
                break;
            default:
                icon.setIconLiteral("fas-file");
        }
        return icon;
    }

    /**
     * Open shared media file - same logic as MessageCell
     * Opens images in preview modal, PDFs in PDF viewer, others with system app
     */
    private void openSharedMediaFile(FileAttachment attachment) {
        if (attachment == null || attachment.getLocalPath() == null) {
            return;
        }

        java.nio.file.Path filePath = attachment.getLocalPath();
        if (!java.nio.file.Files.exists(filePath)) {
            showAlert("File Not Found", "The file no longer exists at: " + filePath, Alert.AlertType.WARNING);
            return;
        }

        MessageType type = attachment.getTargetType();
        String fileName = attachment.getFileName();

        // Debug log
        System.out.printf("[SharedMedia] Opening file: %s (type: %s)%n", fileName, type);

        // Null-safe filename check
        if (fileName == null) {
            fileName = filePath.getFileName().toString();
            System.out.printf("[SharedMedia] Using path filename: %s%n", fileName);
        }

        String fileNameLower = fileName.toLowerCase();

        // Image - open in preview modal
        if (type == MessageType.IMAGE || isImageFile(fileNameLower)) {
            System.out.println("[SharedMedia] → Opening as IMAGE");
            openImagePreviewModal(attachment);
        }
        // PDF - open in PDF viewer modal
        else if (fileNameLower.endsWith(".pdf")) {
            System.out.println("[SharedMedia] → Opening as PDF");
            openPdfViewerModal(attachment);
        }
        // Text files - open in text viewer modal
        else if (isTextFile(fileNameLower)) {
            System.out.println("[SharedMedia] → Opening as TEXT");
            openTextViewerModal(attachment);
        }
        // Video - open with system player (in background thread)
        else if (type == MessageType.VIDEO || isVideoFile(fileNameLower)) {
            System.out.println("[SharedMedia] → Opening as VIDEO (system app)");
            openWithSystemApp(filePath);
        }
        // Other files - open with xdg-open on Linux
        else {
            System.out.println("[SharedMedia] → Opening with xdg-open");
            openWithXdgOpen(filePath);
        }
    }

    private boolean isImageFile(String fileName) {
        return fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                fileName.endsWith(".jpeg") || fileName.endsWith(".gif") ||
                fileName.endsWith(".bmp") || fileName.endsWith(".webp");
    }

    private boolean isTextFile(String fileName) {
        return fileName.endsWith(".txt") || fileName.endsWith(".log") ||
                fileName.endsWith(".md") || fileName.endsWith(".json") ||
                fileName.endsWith(".xml") || fileName.endsWith(".csv") ||
                fileName.endsWith(".yml") || fileName.endsWith(".yaml") ||
                fileName.endsWith(".ini") || fileName.endsWith(".conf") ||
                fileName.endsWith(".properties") || fileName.endsWith(".sh") ||
                fileName.endsWith(".java") || fileName.endsWith(".py") ||
                fileName.endsWith(".js") || fileName.endsWith(".html") ||
                fileName.endsWith(".css");
    }

    private boolean isVideoFile(String fileName) {
        return fileName.endsWith(".mp4") || fileName.endsWith(".mov") ||
                fileName.endsWith(".mkv") || fileName.endsWith(".avi") ||
                fileName.endsWith(".webm");
    }

    /**
     * Open "View All Media" modal - shows all shared media in a grid
     */
    private void openAllMediaModal(List<Message> mediaMessages) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.setTitle("Shared Media - " + currentChannelId + " (" + mediaMessages.size() + " items)");

        // Create grid for media thumbnails
        javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(15));
        grid.setStyle("-fx-background-color: #0f111a;");

        // Add each media item to grid
        for (Message mediaMsg : mediaMessages) {
            FileAttachment attachment = mediaMsg.getAttachment();
            if (attachment == null)
                continue;

            StackPane mediaItem = createMediaGridItem(attachment);
            grid.getChildren().add(mediaItem);
        }

        // Wrap in scroll pane
        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #0f111a; -fx-background-color: #0f111a;");
        scrollPane.setPannable(false); // Disable panning to prevent click interference
        scrollPane.setFocusTraversable(false); // Don't steal focus

        // Header (no close button - use window's X button)
        HBox header = new HBox();
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.setSpacing(15);
        header.setPadding(new javafx.geometry.Insets(15));
        header.setStyle("-fx-background-color: #1a1d21;");

        Label titleLabel = new Label("Shared Media");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        Label countLabel = new Label(mediaMessages.size() + " items");
        countLabel.setStyle("-fx-text-fill: #94a1b2; -fx-font-size: 14px;");

        header.getChildren().addAll(titleLabel, countLabel);

        // Main layout
        VBox root = new VBox(header, scrollPane);
        javafx.scene.layout.VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);
        root.setStyle("-fx-background-color: #0f111a;");

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 700, 500);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.show();
    }

    /**
     * Create a single media item for the grid
     */
    private StackPane createMediaGridItem(FileAttachment attachment) {
        StackPane item = new StackPane();
        item.setPrefSize(120, 120);
        item.setMinSize(120, 120);
        item.setMaxSize(120, 120);
        // Base style set via CSS class for better performance
        item.getStyleClass().add("media-grid-item-base");

        // CRITICAL: Prevent focus stealing on first click
        item.setFocusTraversable(false);
        item.setPickOnBounds(true); // Ensure clicks are captured even on transparent areas

        // Try to load thumbnail
        Image thumbnail = attachment.getThumbnail();

        if (thumbnail == null && attachment.getLocalPath() != null) {
            try {
                java.nio.file.Path filePath = attachment.getLocalPath();
                if (java.nio.file.Files.exists(filePath)) {
                    String fileName = filePath.getFileName().toString().toLowerCase();
                    if (isImageFile(fileName)) {
                        thumbnail = new Image(filePath.toUri().toString(), 120, 120, true, true, true);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        if (thumbnail != null) {
            ImageView imageView = new ImageView(thumbnail);
            imageView.setFitWidth(120);
            imageView.setFitHeight(120);
            imageView.setPreserveRatio(true);
            imageView.setMouseTransparent(true); // Let clicks pass through
            item.getChildren().add(imageView);
        } else {
            // Show file type icon (with filename for accurate icon)
            FontIcon icon = getFileTypeIcon(attachment.getTargetType(), attachment.getFileName());
            icon.setIconSize(36);
            icon.setMouseTransparent(true); // Let clicks pass through

            Label nameLabel = new Label(truncateFileName(attachment.getFileName(), 15));
            nameLabel.setStyle("-fx-text-fill: #94a1b2; -fx-font-size: 10px;");
            nameLabel.setMouseTransparent(true); // Let clicks pass through

            VBox content = new VBox(5, icon, nameLabel);
            content.setAlignment(javafx.geometry.Pos.CENTER);
            content.setMouseTransparent(true); // Let clicks pass through
            item.getChildren().add(content);
        }

        // Hover effect - use pseudoclass instead of inline style for better performance
        // CSS handles :hover state automatically, no need for Java handlers
        item.getStyleClass().add("media-grid-item");

        // Use MOUSE_PRESSED instead of MOUSE_CLICKED for immediate response
        // MOUSE_CLICKED fires after MOUSE_RELEASED, which can feel delayed
        item.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                e.consume();
                openSharedMediaFile(attachment);
            }
        });

        return item;
    }

    /**
     * Truncate filename for display
     */
    private String truncateFileName(String fileName, int maxLength) {
        if (fileName == null)
            return "File";
        if (fileName.length() <= maxLength)
            return fileName;

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String name = fileName.substring(0, dotIndex);
            String ext = fileName.substring(dotIndex);
            int availableLength = maxLength - ext.length() - 3; // 3 for "..."
            if (availableLength > 0) {
                return name.substring(0, Math.min(name.length(), availableLength)) + "..." + ext;
            }
        }
        return fileName.substring(0, maxLength - 3) + "...";
    }

    /**
     * Open text file in a viewer modal
     */
    private void openTextViewerModal(FileAttachment attachment) {
        try {
            java.nio.file.Path path = attachment.getLocalPath();
            String content = java.nio.file.Files.readString(path);

            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            stage.setTitle(attachment.getFileName());

            javafx.scene.control.TextArea textArea = new javafx.scene.control.TextArea(content);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setStyle(
                    "-fx-control-inner-background: #1a1d21; " +
                            "-fx-text-fill: #e5e5e5; " +
                            "-fx-font-family: 'JetBrains Mono', 'Consolas', monospace; " +
                            "-fx-font-size: 13px;");

            VBox root = new VBox(textArea);
            root.setStyle("-fx-background-color: #0f111a; -fx-padding: 10;");
            javafx.scene.layout.VBox.setVgrow(textArea, javafx.scene.layout.Priority.ALWAYS);

            javafx.scene.Scene scene = new javafx.scene.Scene(root, 700, 500);
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    stage.close();
                }
            });

            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            System.err.println("[ChatView] Failed to open text viewer: " + e.getMessage());
            // Fallback to system app
            openWithSystemApp(attachment.getLocalPath());
        }
    }

    /**
     * Open image in a preview modal (like MessageCell does)
     */
    private void openImagePreviewModal(FileAttachment attachment) {
        try {
            Image fullImage = new Image(attachment.getLocalPath().toUri().toString());

            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            stage.setTitle(attachment.getFileName());

            ImageView imageView = new ImageView(fullImage);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(800);
            imageView.setFitHeight(600);

            StackPane root = new StackPane(imageView);
            root.setStyle("-fx-background-color: #0f111a; -fx-padding: 20;");
            root.setOnMouseClicked(e -> stage.close());

            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    stage.close();
                }
            });

            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            System.err.println("[ChatView] Failed to open image preview: " + e.getMessage());
            openWithSystemApp(attachment.getLocalPath());
        }
    }

    /**
     * Opens PDF with the system default application.
     * In-app PDF rendering via PDFBox has been removed to reduce dependencies.
     */
    private void openPdfViewerModal(FileAttachment attachment) {
        if (attachment == null || attachment.getLocalPath() == null) {
            return;
        }
        openWithSystemApp(attachment.getLocalPath());
    }

    /**
     * Open file with system default application (in background thread to prevent UI
     * freeze)
     */
    private void openWithSystemApp(java.nio.file.Path filePath) {
        // On Linux, use xdg-open to avoid GDK warnings
        openWithXdgOpen(filePath);
    }

    /**
     * Open file using xdg-open (Linux) or system default
     * This avoids the "XSetErrorHandler() called with a GDK error trap pushed"
     * warning
     */
    private void openWithXdgOpen(java.nio.file.Path filePath) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;

                if (os.contains("linux")) {
                    // Use xdg-open on Linux
                    pb = new ProcessBuilder("xdg-open", filePath.toAbsolutePath().toString());
                } else if (os.contains("mac")) {
                    // Use open on macOS
                    pb = new ProcessBuilder("open", filePath.toAbsolutePath().toString());
                } else {
                    // Windows - use cmd /c start
                    pb = new ProcessBuilder("cmd", "/c", "start", "", filePath.toAbsolutePath().toString());
                }

                pb.inheritIO();
                Process process = pb.start();

                // Don't wait for process to complete
                System.out.printf("[SharedMedia] Opened with system app: %s%n", filePath.getFileName());

            } catch (Exception e) {
                Platform.runLater(() -> {
                    System.err.println("[ChatView] Failed to open file: " + e.getMessage());
                    showAlert("Error", "Could not open file: " + e.getMessage(), Alert.AlertType.ERROR);
                });
            }
        });
    }

    /**
     * Load conversation history from persistent storage (NEW)
     * Loads messages from disk and populates the ObservableList
     * 
     * @param channelId Remote username / channel ID
     */
    private void loadConversationHistoryAsync(String channelId) {
        if (!LocalDatabase.isInitialized())
            return;

        System.out.println("[ChatView] ⏳ Lazy loading history for: " + channelId);

        LocalMessageRepository repository = LocalMessageRepository.getInstance();
        String currentUser = chatService.getCurrentUsername();
        String conversationId = SqlCipherHelper.generateConversationId(currentUser, channelId);

        // Initial load: 15 messages (Page 0)
        repository.loadMessagesPaginatedAsync(conversationId, 15, 0)
                .thenAccept(loadedMessages -> {
                    Platform.runLater(() -> {
                        if (loadedMessages.isEmpty()) {
                            System.out.println("[ChatView] No history found.");
                            return;
                        }

                        // SQL returns DESC (newest first), so we must reverse for UI (oldest at top)
                        java.util.Collections.reverse(loadedMessages);

                        messages.setAll(loadedMessages);
                        messageListView.scrollTo(messages.size() - 1);

                        System.out.println("[ChatView] ✅ Loaded " + loadedMessages.size() + " messages (Lazy Load)");

                        // Setup scroll listener for infinite scrolling (Scroll Up to load more)
                        setupScrollListenerForPagination(conversationId);
                    });
                })
                .exceptionally(e -> {
                    System.err.println("[ChatView] ❌ History load failed: " + e.getMessage());
                    return null;
                });
    }

    private void updateViewVisibility() {
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(false);
            emptyChatPlaceholder.setManaged(false);
        }

        boolean isChatEmpty = (messages == null || messages.isEmpty());

        if (noMessagesPlaceholder != null) {
            noMessagesPlaceholder.setVisible(isChatEmpty);
            noMessagesPlaceholder.setManaged(isChatEmpty);
        }

        messageListView.setVisible(!isChatEmpty);
        messageListView.setManaged(!isChatEmpty);
    }

    public void showWelcomeScreen() {
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(true);
            emptyChatPlaceholder.setManaged(true);
        }

        if (noMessagesPlaceholder != null) {
            noMessagesPlaceholder.setVisible(false);
            noMessagesPlaceholder.setManaged(false);
        }

        messageListView.setVisible(false);
        messageListView.setManaged(false);

        if (chatHeader != null) {
            chatHeader.setVisible(false);
            chatHeader.setManaged(false);
        }

        if (chatInputArea != null) {
            chatInputArea.setVisible(false);
            chatInputArea.setManaged(false);
        }

        // Paneli gizle
        if (userInfoSidebar != null) {
            userInfoSidebar.setVisible(false);
            userInfoSidebar.setManaged(false);
        }
    }

    public void setWidthConstraint(double width) {
        if (chatPane != null) {
            chatPane.setMaxWidth(width);
            chatPane.setPrefWidth(width);
        }
    }

    public void setHeaderVisible(boolean visible) {
        if (chatHeader != null) {
            chatHeader.setVisible(visible);
            chatHeader.setManaged(visible);
        }
    }

    public void setHeader(String name, String status, String avatarChar, boolean isGroupChat) {
        chatPartnerName.setText(name);
        chatPartnerStatus.setText(status);
        chatPartnerAvatar.setText(avatarChar);

        chatPartnerStatus.getStyleClass().removeAll("status-online", "status-offline", "status-idle", "status-dnd",
                "status-busy");

        String statusLower = status.toLowerCase();
        if (statusLower.contains("online")) {
            chatPartnerStatus.getStyleClass().add("status-online");
        } else if (statusLower.contains("idle") || statusLower.contains("away")) {
            chatPartnerStatus.getStyleClass().add("status-idle");
        } else if (statusLower.contains("busy") || statusLower.contains("dnd")) {
            chatPartnerStatus.getStyleClass().add("status-dnd");
        } else {
            chatPartnerStatus.getStyleClass().add("status-offline");
        }

        phoneButton.setVisible(!isGroupChat);
        videoButton.setVisible(!isGroupChat);

        if (userInfoSidebar != null && userInfoSidebar.isVisible()) {
            updateInfoPanel();
        }
    }

    @FXML
    private void handleSendMessage() {
        String text = messageInputField.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        chatService.sendMessage(currentChannelId, text, currentUser);
        messageInputField.clear();
    }

    private void showFileSendConfirmation(File file) {
        long fileSizeBytes = file.length();
        String fileSizeStr = formatFileSize(fileSizeBytes);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Send File");
        alert.setHeaderText("Send file to " + chatPartnerName.getText() + "?");
        alert.setContentText(
                String.format("File: %s\nSize: %s\n\nDo you want to send this file?", file.getName(), fileSizeStr));
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                sendFile(file.toPath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void sendFile(Path filePath) {
        chatService.sendFileMessage(currentChannelId, filePath, currentUser);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    private void setupAutoScrollBehavior() {
        messageListView.skinProperty().addListener((obs, oldSkin, newSkin) -> attachScrollListener());
        Platform.runLater(this::attachScrollListener);
    }

    private void attachScrollListener() {
        ScrollBar bar = (ScrollBar) messageListView.lookup(".scroll-bar:vertical");
        if (bar == null) {
            return;
        }
        bar.valueProperty().addListener((obs, oldVal, newVal) -> {
            double max = bar.getMax();
            if (Double.compare(max, 0) == 0) {
                autoScrollAtBottom = true;
                return;
            }
            autoScrollAtBottom = (max - newVal.doubleValue()) < 0.05;
        });
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            if (!event.isShiftDown()) {
                handleSendMessage();
                event.consume();
            }
        }
    }

    // ===============================
    // WebRTC Call Handlers (Kısaltıldı)
    // ===============================
    @FXML
    private void handlePhoneCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            return;
        }
        showCallConfirmation("Audio Call", "Start audio call?", false);
    }

    @FXML
    private void handleVideoCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            return;
        }
        showCallConfirmation("Video Call", "Start video call?", true);
    }

    // Contact Info panel'deki butonlar için handler'lar
    @FXML
    private void handleInfoAudioCall() {
        handlePhoneCall(); // Aynı mantığı kullan
    }

    @FXML
    private void handleInfoVideoCall() {
        handleVideoCall(); // Aynı mantığı kullan
    }

    /**
     * Contact Info Search Button Handler
     * Opens WhatsApp-style search panel
     */
    @FXML
    private void handleInfoSearch() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            showAlert("Search", "No conversation selected.", Alert.AlertType.INFORMATION);
            return;
        }

        // Check if persistence is enabled
        try {
            LocalDatabase db = LocalDatabase.getInstance();
            if (db == null || db.getConnection() == null) {
                showAlert("Search Unavailable",
                        "Search feature requires persistent storage to be enabled.\n" +
                                "Messages are currently stored in RAM only.",
                        Alert.AlertType.INFORMATION);
                return;
            }

            // Show WhatsApp-style search panel
            showSearchPanel();

        } catch (IllegalStateException e) {
            showAlert("Search Unavailable",
                    "Persistent storage is not initialized.\n" +
                            "Search will be available after enabling message history.",
                    Alert.AlertType.INFORMATION);
        }
    }

    /**
     * Show WhatsApp-style search panel with slide-in animation
     */
    private void showSearchPanel() {
        // Close Contact Info sidebar first (search panel replaces it)
        if (userInfoSidebar != null && userInfoSidebar.isVisible()) {
            hideUserInfoSidebar();
        }

        // Initialize search panel if needed
        if (searchPanel == null) {
            initializeSearchPanel();
        }

        // Generate conversation ID
        String conversationId = SqlCipherHelper.generateConversationId(
                chatService.getCurrentUsername(),
                currentChannelId);

        // Show the panel
        searchPanel.show(conversationId, chatService.getCurrentUsername());
    }

    /**
     * Hide user info sidebar with animation
     */
    private void hideUserInfoSidebar() {
        if (userInfoSidebar != null) {
            TranslateTransition slideOut = new TranslateTransition(Duration.millis(200), userInfoSidebar);
            slideOut.setToX(userInfoSidebar.getWidth());
            slideOut.setOnFinished(e -> userInfoSidebar.setVisible(false));
            slideOut.play();
        }
    }

    /**
     * Hide search panel with slide-out animation
     */
    private void hideSearchPanel() {
        if (searchPanel != null && searchPanel.isShowing()) {
            searchPanel.hide();
        }
    }

    /**
     * Initialize the WhatsApp-style search panel
     */
    private void initializeSearchPanel() {
        searchPanel = new MessageSearchPanel();

        // Load CSS
        try {
            String cssPath = getClass().getResource("/css/search-panel.css").toExternalForm();
            searchPanel.getStylesheets().add(cssPath);
        } catch (Exception e) {
            System.err.println("[ChatView] Could not load search-panel.css: " + e.getMessage());
        }

        // Set callback for when a result is clicked
        searchPanel.setOnResultSelected(this::handleSearchResultSelected);

        // Set callback for when panel is closed
        searchPanel.setOnClose(() -> {
            // Clear any highlights
            if (highlightedMessageId != null) {
                clearMessageHighlight(highlightedMessageId);
                highlightedMessageId = null;
            }
        });

        // Add panel to the chat pane (overlay on right side)
        if (chatPane != null) {
            // Wrap existing content in StackPane if needed
            if (!(chatPane.getCenter() instanceof StackPane)) {
                javafx.scene.Node existingCenter = chatPane.getCenter();
                StackPane stackPane = new StackPane();
                stackPane.getChildren().add(existingCenter);
                stackPane.getChildren().add(searchPanel);

                // Align search panel to right
                StackPane.setAlignment(searchPanel, javafx.geometry.Pos.CENTER_RIGHT);

                chatPane.setCenter(stackPane);
            } else {
                StackPane stackPane = (StackPane) chatPane.getCenter();
                if (!stackPane.getChildren().contains(searchPanel)) {
                    stackPane.getChildren().add(searchPanel);
                    StackPane.setAlignment(searchPanel, javafx.geometry.Pos.CENTER_RIGHT);
                }
            }
        }
    }

    /**
     * Handle when a search result is clicked
     */
    private void handleSearchResultSelected(SearchHit hit) {
        if (hit == null || messages == null)
            return;

        // Find message index by ID
        int index = findMessageIndexById(hit.getMessageId());

        if (index >= 0) {
            // Scroll to message
            messageListView.scrollTo(index);

            // Highlight the message
            highlightMessage(hit.getMessageId());
        } else {
            System.err.println("[ChatView] Message not found in list: " + hit.getMessageId());
        }
    }

    /**
     * Find message index by ID
     */
    private int findMessageIndexById(String messageId) {
        if (messages == null || messageId == null)
            return -1;

        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Highlight a message with yellow flash animation (2 seconds)
     */
    private void highlightMessage(String messageId) {
        // Clear previous highlight
        if (highlightedMessageId != null) {
            clearMessageHighlight(highlightedMessageId);
        }

        highlightedMessageId = messageId;

        // Find the message and trigger highlight
        int index = findMessageIndexById(messageId);
        if (index >= 0) {
            // Scroll to the message first
            messageListView.scrollTo(index);

            // Select briefly for visual feedback (no refresh needed)
            messageListView.getSelectionModel().select(index);

            // Schedule highlight removal after 2 seconds
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.seconds(2), e -> {
                        clearMessageHighlight(messageId);
                        messageListView.getSelectionModel().clearSelection();
                    }));
            timeline.play();

            // NOTE: Removed messageListView.refresh() - it was causing
            // all visible cells to re-render, which is expensive and unnecessary.
            // The selection change alone provides visual feedback.
        }
    }

    /**
     * Clear message highlight
     */
    private void clearMessageHighlight(String messageId) {
        highlightedMessageId = null;
        messageListView.refresh();
    }

    /**
     * Check if a message is currently highlighted
     */
    public boolean isMessageHighlighted(String messageId) {
        return messageId != null && messageId.equals(highlightedMessageId);
    }

    // ========== LEGACY SEARCH METHODS (kept for compatibility) ==========

    /**
     * @deprecated Use showSearchPanel() instead
     */
    @Deprecated
    private void showSearchDialog() {
        showSearchPanel();
    }

    /**
     * @deprecated Use handleSearchResultSelected() instead
     */
    @Deprecated
    private void performSearch(String query) {
        // Get conversation ID
        String conversationId = SqlCipherHelper.generateConversationId(
                chatService.getCurrentUsername(),
                currentChannelId);

        // Search in background
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (searchService == null) {
                searchService = new FTS5SearchService(LocalDatabase.getInstance());
            }
            searchResultMessageIds = searchService.getMatchingMessageIds(query, conversationId);

            Platform.runLater(() -> {
                if (searchResultMessageIds.isEmpty()) {
                    showAlert("No Results",
                            "No messages found containing \"" + query + "\"",
                            Alert.AlertType.INFORMATION);
                } else {
                    currentSearchIndex = 0;
                    scrollToSearchResult(0);
                }
            });
        });
    }

    /**
     * Show search navigation dialog with Up/Down buttons
     */
    private void showSearchNavigationDialog(String query, int totalResults) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Search Results");
        alert.setHeaderText("Found " + totalResults + " results for \"" + query + "\"");
        alert.setContentText("Result " + (currentSearchIndex + 1) + " of " + totalResults);

        // Custom buttons
        ButtonType previousBtn = new ButtonType("⬆ Previous");
        ButtonType nextBtn = new ButtonType("⬇ Next");
        ButtonType closeBtn = new ButtonType("Close", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(previousBtn, nextBtn, closeBtn);

        try {
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/styles.css").toExternalForm());
        } catch (Exception e) {
            // Ignore
        }

        // Handle button clicks
        alert.showAndWait().ifPresent(response -> {
            if (response == previousBtn) {
                navigateToPreviousResult(query, totalResults);
            } else if (response == nextBtn) {
                navigateToNextResult(query, totalResults);
            }
        });
    }

    /**
     * Navigate to next search result
     */
    private void navigateToNextResult(String query, int totalResults) {
        if (currentSearchIndex < totalResults - 1) {
            currentSearchIndex++;
            scrollToSearchResult(currentSearchIndex);
            showSearchNavigationDialog(query, totalResults);
        } else {
            showAlert("End of Results", "No more results", Alert.AlertType.INFORMATION);
        }
    }

    /**
     * Navigate to previous search result
     */
    private void navigateToPreviousResult(String query, int totalResults) {
        if (currentSearchIndex > 0) {
            currentSearchIndex--;
            scrollToSearchResult(currentSearchIndex);
            showSearchNavigationDialog(query, totalResults);
        } else {
            showAlert("Start of Results", "Already at first result", Alert.AlertType.INFORMATION);
        }
    }

    /**
     * Scroll to a specific search result
     */
    private void scrollToSearchResult(int index) {
        String targetMessageId = searchResultMessageIds.get(index);

        // Find message in ObservableList
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId().equals(targetMessageId)) {
                final int messageIndex = i;
                Platform.runLater(() -> {
                    messageListView.scrollTo(messageIndex);
                    messageListView.getSelectionModel().select(messageIndex);

                    // Highlight with animation
                    highlightMessage(messageIndex);
                });
                break;
            }
        }
    }

    /**
     * Highlight a message cell with animation
     */
    private void highlightMessage(int index) {
        Platform.runLater(() -> {
            // Flash selection
            messageListView.getSelectionModel().select(index);

            // Clear selection after delay
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(2));
            pause.setOnFinished(e -> messageListView.getSelectionModel().clearSelection());
            pause.play();
        });
    }

    private void showCallConfirmation(String title, String header, boolean isVideo) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText("Do you want to call " + chatPartnerName.getText() + "?");
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            startCall(isVideo);
        }
    }

    private void startCall(boolean videoEnabled) {
        try {
            String targetUsername = currentChannelId;
            String myUsername = currentUser.getId();
            CallManager callManager = CallManager.getInstance();

            if (!callManager.isInitialized()) {
                callManager.initialize(myUsername);
            }
            setupCallManagerCallbacks(callManager);

            if (callManager.isInCall()) {
                showAlert("Call In Progress", "You are already in a call.", Alert.AlertType.WARNING);
                return;
            }

            callManager.startCall(targetUsername, true, videoEnabled)
                    .thenAccept(callId -> {
                        Platform.runLater(() -> {
                            currentCallVideoEnabled = videoEnabled;
                            String callMsg = videoEnabled ? "📹 Starting video call..." : "📞 Starting audio call...";
                            chatService.sendMessage(currentChannelId, callMsg, currentUser);

                            currentOutgoingDialog = new OutgoingCallDialog(targetUsername, callId, videoEnabled);
                            currentOutgoingDialog.show();
                        });
                    })
                    .exceptionally(e -> {
                        Platform.runLater(() -> showAlert("Call Failed", "Failed to start call: " + e.getMessage(),
                                Alert.AlertType.ERROR));
                        return null;
                    });
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Error starting call: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private void setupCallManagerCallbacks(CallManager callManager) {
        if (callbacksSetup) {
            return;
        }
        callbacksSetup = true;

        callManager.setOnIncomingCallCallback(info -> {
            Platform.runLater(() -> {
                currentCallVideoEnabled = info.videoEnabled;
                IncomingCallDialog incomingDialog = new IncomingCallDialog(info.callerUsername, info.callId,
                        info.videoEnabled);

                // DÜZELTME: incomingDialog'u sınıf değişkenine atadık.
                // Böylece arama bittiğinde kapatabiliriz.
                this.currentIncomingDialog = incomingDialog;

                incomingDialog.show().thenAccept(accepted -> {
                    if (accepted) {
                        callManager.acceptCall(info.callId);
                    } else {
                        callManager.rejectCall(info.callId);
                    }

                    // İşlem bittiğinde referansı temizle
                    this.currentIncomingDialog = null;
                });
            });
        });

        callManager.setOnCallAcceptedCallback(callId -> {
            Platform.runLater(() -> {
                chatService.sendMessage(currentChannelId, "✅ Call accepted - connecting...", currentUser);
                if (currentOutgoingDialog != null) {
                    currentOutgoingDialog.close();
                    currentOutgoingDialog = null;
                }

                if (currentActiveCallDialog == null) {
                    currentActiveCallDialog = new ActiveCallDialog(currentChannelId, callId, currentCallVideoEnabled,
                            callManager);
                    currentActiveCallDialog.show();

                    // 🎥 For CALLER: Tracks are ALREADY added in startCall()
                    // So we attach local video immediately here
                    if (currentCallVideoEnabled) {
                        VideoTrack localVideo = callManager.getLocalVideoTrack();
                        if (localVideo != null) {
                            System.out.println(
                                    "[ChatViewController] 🎥 Attaching local video (CALLER - tracks already ready)");
                            currentActiveCallDialog.attachLocalVideo(localVideo);
                        }
                    }
                }
            });
        });

        // 🎥 For CALLEE: Attach local video when tracks are actually ready
        // (tracks are added in handleOffer() AFTER dialog is created)
        callManager.setOnLocalTracksReadyCallback(() -> {
            Platform.runLater(() -> {
                if (currentActiveCallDialog != null && currentCallVideoEnabled) {
                    VideoTrack localVideo = callManager.getLocalVideoTrack();
                    if (localVideo != null) {
                        System.out.println("[ChatViewController] 🎥 Attaching local video (CALLEE - tracks now ready)");
                        currentActiveCallDialog.attachLocalVideo(localVideo);
                    }
                }
            });
        });

        callManager.setOnCallRejectedCallback(callId -> {
            Platform.runLater(() -> chatService.sendMessage(currentChannelId, "❌ Call was rejected", currentUser));
        });

        callManager.setOnCallConnectedCallback(() -> {
            Platform.runLater(() -> chatService.sendMessage(currentChannelId, "🔗 Call connected!", currentUser));
        });

        callManager.setOnCallEndedCallback(callId -> {
            Platform.runLater(() -> {
                chatService.sendMessage(currentChannelId, "📴 Call ended", currentUser);
                if (currentOutgoingDialog != null) {
                    currentOutgoingDialog.close();
                    currentOutgoingDialog = null;
                }
                if (currentIncomingDialog != null) {
                    currentIncomingDialog.close();
                    currentIncomingDialog = null;
                }
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.close();
                    currentActiveCallDialog = null;
                }
                currentCallVideoEnabled = false;
            });
        });

        callManager.setOnRemoteTrackCallback(track -> {
            Platform.runLater(() -> {
                if (track instanceof VideoTrack && currentActiveCallDialog != null) {
                    currentActiveCallDialog.attachRemoteVideo((VideoTrack) track);
                }
            });
        });

        callManager.setOnRemoteScreenShareStoppedCallback(() -> {
            Platform.runLater(() -> {
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.onRemoteScreenShareStopped();
                }
            });
        });
    }

    private void setupScrollListenerForPagination(String conversationId) {
        // Use a flag to prevent multiple concurrent loads
        final java.util.concurrent.atomic.AtomicBoolean isLoadingMore = new java.util.concurrent.atomic.AtomicBoolean(
                false);

        // Get the ScrollBar from ListView skin
        // We need to wait for skin to be attached
        messageListView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                attachScrollHandler(conversationId, isLoadingMore);
            }
        });

        // If skin already exists
        if (messageListView.getSkin() != null) {
            attachScrollHandler(conversationId, isLoadingMore);
        }
    }

    private void attachScrollHandler(String conversationId, java.util.concurrent.atomic.AtomicBoolean isLoadingMore) {
        javafx.scene.Node scrollBarNode = messageListView.lookup(".scroll-bar:vertical");
        if (scrollBarNode instanceof ScrollBar) {
            ScrollBar scrollBar = (ScrollBar) scrollBarNode;

            scrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
                // If scrolled to near top (0.0 to 0.1) and not currently loading
                if (newVal.doubleValue() < 0.1 && !isLoadingMore.get()) {
                    loadMoreMessages(conversationId, isLoadingMore);
                }
            });
        }
    }

    private void loadMoreMessages(String conversationId, java.util.concurrent.atomic.AtomicBoolean isLoadingMore) {
        isLoadingMore.set(true);
        int currentCount = messages.size();
        System.out.println("[ChatView] 🔄 Loading more messages... (Current: " + currentCount + ")");

        LocalMessageRepository.getInstance().loadMessagesPaginatedAsync(conversationId, 15, currentCount)
                .thenAccept(olderMessages -> {
                    Platform.runLater(() -> {
                        if (olderMessages.isEmpty()) {
                            // No more messages
                            return;
                        }

                        // Reverse because SQL gives DESC (newest first)
                        java.util.Collections.reverse(olderMessages);

                        // Insert at top
                        messages.addAll(0, olderMessages);

                        // Maintain scroll position roughly
                        // (Adding items at 0 pushes content down, so we normally want to scroll to
                        // index = loaded size)
                        messageListView.scrollTo(olderMessages.size());

                        System.out.println("[ChatView] ➕ Added " + olderMessages.size() + " older messages.");

                        // Allow loading again after short delay
                        new java.util.Timer().schedule(new java.util.TimerTask() {
                            @Override
                            public void run() {
                                isLoadingMore.set(false);
                            }
                        }, 500);
                    });
                })
                .exceptionally(e -> {
                    isLoadingMore.set(false);
                    System.err.println("[ChatView] ❌ Failed to load more: " + e.getMessage());
                    return null;
                });
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());
        alert.show();
    }
}
