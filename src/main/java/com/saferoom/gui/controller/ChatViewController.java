package com.saferoom.gui.controller;

import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.gui.dialog.FileTransferDialog;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public class ChatViewController {

    @FXML private BorderPane chatPane;
    @FXML private Label chatPartnerAvatar;
    @FXML private Label chatPartnerName;
    @FXML private Label chatPartnerStatus;
    @FXML private ListView<Message> messageListView;
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button phoneButton;
    @FXML private Button videoButton;
    @FXML private Button attachmentButton;
    @FXML private HBox chatHeader;
    @FXML private VBox emptyChatPlaceholder;

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;

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

        // Remove all existing status classes
        chatPartnerStatus.getStyleClass().removeAll("status-online", "status-offline", "status-idle", "status-dnd", "status-busy");

        // Apply appropriate status class based on status text
        String statusLower = status.toLowerCase();
        if (statusLower.contains("online")) {
            chatPartnerStatus.getStyleClass().add("status-online");
        } else if (statusLower.contains("idle") || statusLower.contains("away")) {
            chatPartnerStatus.getStyleClass().add("status-idle");
        } else if (statusLower.contains("busy") || statusLower.contains("dnd") || statusLower.contains("do not disturb")) {
            chatPartnerStatus.getStyleClass().add("status-dnd");
        } else {
            chatPartnerStatus.getStyleClass().add("status-offline");
        }

        phoneButton.setVisible(!isGroupChat);
        videoButton.setVisible(!isGroupChat);
    }

    
    /**
     * Handle attachment button click - open file chooser
     */
        /**
     * Format file size in human-readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * Handle key presses in message input field
     */
        private void updatePlaceholderVisibility() {
        boolean isListEmpty = (messages == null || messages.isEmpty());
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(isListEmpty);
            emptyChatPlaceholder.setManaged(isListEmpty);
        }
        messageListView.setVisible(!isListEmpty);
    }
}