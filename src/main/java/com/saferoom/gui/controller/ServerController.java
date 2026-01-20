package com.saferoom.gui.controller;

import com.saferoom.gui.model.User;
import com.saferoom.gui.model.Channel;
import com.saferoom.gui.model.Message;
import com.saferoom.gui.utils.UserSession;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import javafx.stage.Popup;
import java.util.ArrayList;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ClipboardContent;
import javafx.scene.SnapshotParameters;
import com.saferoom.gui.controller.MainController; // Ensure this is imported if not already
import com.saferoom.rooms.client.ActiveRoomSession;
import com.saferoom.rooms.client.logic.DataFSM;
import com.saferoom.rooms.grpc.RoomPeer;
import javafx.application.Platform;

public class ServerController implements Initializable {

    @FXML
    private BorderPane serverPane;
    @FXML
    private StackPane popupOverlay;
    @FXML
    private StackPane popupContainer;
    @FXML
    private VBox channelsContainer;
    @FXML
    private VBox voiceChannelsList;
    @FXML
    private VBox textChannelsList;
    @FXML
    private VBox fileChannelsList;
    @FXML
    private VBox voiceChannelsSection;
    @FXML
    private VBox textChannelsSection;
    @FXML
    private VBox fileChannelsSection;

    // User Info
    @FXML
    private Label currentUserName;
    @FXML
    private Label serverNameLabel;
    @FXML
    private Label serverMembersLabel;
    @FXML
    private Label serverPrivacyBadge;
    @FXML
    private FontIcon serverIcon;
    @FXML
    private Label totalUsersCount;
    @FXML
    private Label onlineUsersCount;
    @FXML
    private Label offlineUsersCount;
    @FXML
    private VBox onlineUsersList;
    @FXML
    private VBox offlineUsersList;

    // Chat
    @FXML
    private ListView<Message> messagesListView;
    @FXML
    private TextField messageTextField;
    @FXML
    private Button sendMessageBtn;
    @FXML
    private VBox defaultChannelView;
    @FXML
    private BorderPane textChatView;
    @FXML
    private VBox voiceChatView;
    @FXML
    private Label currentChannelName;
    @FXML
    private FontIcon currentChannelIcon;
    @FXML
    private Label currentChannelTopic;
    @FXML
    private VBox textGeneralNotification;
    @FXML
    private VBox textTeamNotification;
    @FXML
    private Button toggleUsersBtn;
    @FXML
    private Button toggleUsersBtnDefault;

    // Voice
    @FXML
    private Label voiceChannelName;
    @FXML
    private FlowPane voiceUsersContainer; // CHANGED to FlowPane
    @FXML
    private Button toggleMicBtn;
    @FXML
    private Button toggleDeafenBtn;
    @FXML
    private Button muteBtn;
    @FXML
    private Button deafenBtn;
    @FXML
    private Button shareScreenBtn;

    // Channel Items (for selection clearing)
    @FXML
    private HBox voiceGeneral;
    @FXML
    private HBox voiceMeeting;
    @FXML
    private HBox voicePrivate;
    @FXML
    private HBox textGeneral;
    @FXML
    private HBox textAnnouncements;
    @FXML
    private HBox textTeam;
    @FXML
    private HBox textPrivate;
    @FXML
    private HBox fileResources;
    @FXML
    private HBox fileProject;

    // State
    private List<User> serverUsers = new java.util.ArrayList<>();
    private List<Channel> textChannels = new java.util.ArrayList<>();
    private List<Channel> voiceChannels = new java.util.ArrayList<>();
    private String currentServerName = "SafeRoom";
    private String currentChannel = "";

    // Track current user's voice card for mute/deafen status updates
    private VBox currentUserVoiceCard;
    private FontIcon currentUserMicIcon;
    private boolean isUserMuted = false;
    private boolean isUserDeafened = false;
    private Popup currentEditPopup;
    private Node currentEditTarget;

    @FXML
    private Button leaveVoiceBtn;

    // Helper method for channel creation dialog
    private void showCreateChannelDialog(VBox sectionNode, VBox listNode, boolean initialVoice) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/CreateChannelDialog.fxml"));
            Parent root = loader.load();

            CreateChannelDialogController controller = loader.getController();
            controller.setInitialCategory(false); // Default to channel creation
            controller.setInitialVoice(initialVoice);

            // Create a transparent overlay stage
            Stage ownerStage = (Stage) serverPane.getScene().getWindow();
            Stage dialogStage = new Stage();
            dialogStage.initOwner(ownerStage);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.TRANSPARENT);

            // Container for the dim effect and centering
            StackPane overlay = new StackPane();
            overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5);");

            // Limit dialog size to preferred size so it doesn't stretch
            if (root instanceof javafx.scene.layout.Region) {
                ((javafx.scene.layout.Region) root).setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                        javafx.scene.layout.Region.USE_PREF_SIZE);
            }

            overlay.getChildren().add(root);

            // Close if clicked outside (on the overlay background)
            overlay.setOnMouseClicked(event -> {
                // Check if the click target is the overlay itself, not the dialog content
                // Also ensure it's a primary button click and it's not part of a drag operation
                if (event.getTarget() == overlay &&
                        event.getButton() == javafx.scene.input.MouseButton.PRIMARY &&
                        event.isStillSincePress()) {
                    dialogStage.close();
                }
            });

            // Ensure dialog itself consumes clicks so they don't propagate to overlay
            root.setOnMouseClicked(event -> event.consume());

            // Set scene with transparent fill
            Scene scene = new Scene(overlay);
            scene.setFill(Color.TRANSPARENT);
            dialogStage.setScene(scene);

            // Bind size to owner size to cover the whole window
            dialogStage.setX(ownerStage.getX());
            dialogStage.setY(ownerStage.getY());
            dialogStage.setWidth(ownerStage.getWidth());
            dialogStage.setHeight(ownerStage.getHeight());

            // Keep it resized with owner
            ownerStage.xProperty().addListener((obs, oldVal, newVal) -> dialogStage.setX(newVal.doubleValue()));
            ownerStage.yProperty().addListener((obs, oldVal, newVal) -> dialogStage.setY(newVal.doubleValue()));
            ownerStage.widthProperty().addListener((obs, oldVal, newVal) -> dialogStage.setWidth(newVal.doubleValue()));
            ownerStage.heightProperty()
                    .addListener((obs, oldVal, newVal) -> dialogStage.setHeight(newVal.doubleValue()));

            dialogStage.showAndWait();

            if (controller.isConfirmed()) {
                if (controller.isCategory()) {
                    createCategory(controller.getResultName(), sectionNode);
                } else {
                    if (controller.isVoice()) {
                        createVoiceChannel(controller.getResultName(), listNode, controller.getUserLimit());
                    } else {
                        createTextChannel(controller.getResultName(), listNode);
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleAddVoiceSection(ActionEvent event) {
        showCreateChannelDialog(voiceChannelsSection, voiceChannelsList, true);
    }

    @FXML
    private void handleAddTextSection(ActionEvent event) {
        showCreateChannelDialog(textChannelsSection, textChannelsList, false);
    }

    @FXML
    private void handleAddFileSection(ActionEvent event) {
        showCreateChannelDialog(fileChannelsSection, fileChannelsList, false);
    }

    private void createCategory(String name, VBox siblingSection) {
        VBox newSection = new VBox(3.0);

        // Header
        HBox header = new HBox(5.0);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("channel-category-header");

        FontIcon arrow = new FontIcon("fas-chevron-down");
        arrow.getStyleClass().add("category-arrow");

        FontIcon icon = new FontIcon("fas-folder"); // Default icon
        icon.getStyleClass().add("category-icon");

        Label label = new Label(name.toUpperCase());
        label.getStyleClass().add("category-label");

        javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        Button addBtn = new Button();
        addBtn.getStyleClass().add("icon-button-tiny");
        FontIcon plusIcon = new FontIcon("fas-plus");
        plusIcon.getStyleClass().add("tiny-icon");
        addBtn.setGraphic(plusIcon);

        header.getChildren().addAll(arrow, icon, label, spacer, addBtn);

        // List container
        VBox listContainer = new VBox(2.0);

        newSection.getChildren().addAll(header, listContainer);

        // Add functionality
        // Toggle collapse
        header.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                boolean isVisible = listContainer.isVisible();
                listContainer.setVisible(!isVisible);
                listContainer.setManaged(!isVisible);
                arrow.setIconLiteral(!isVisible ? "fas-chevron-down" : "fas-chevron-right");
            }
        });

        // Add button handler
        addBtn.setOnAction(e -> showCreateChannelDialog(newSection, listContainer, false));

        // Insert into sidebar
        int index = channelsContainer.getChildren().indexOf(siblingSection);
        if (index != -1) {
            channelsContainer.getChildren().add(index + 1, newSection);
        } else {
            channelsContainer.getChildren().add(newSection);
        }

        // Make reorderable
        setupReordering(channelsContainer);

        // Add Context Menu
        setupCategoryContextMenu(header, newSection);
    }

    private void setupCategoryContextMenu(Node target, VBox section) {
        target.setOnContextMenuRequested(e -> {
            showEditPopup(target, true, false, section);
            e.consume();
        });
    }

    private void handleDeleteCategory(VBox section) {
        // Check if it's the last category
        long categoryCount = channelsContainer.getChildren().stream()
                .filter(node -> node instanceof VBox)
                .count();

        if (categoryCount <= 1) {
            Alert alert = new Alert(AlertType.WARNING);
            alert.setTitle("Cannot Delete");
            alert.setHeaderText(null);
            alert.setContentText("You cannot delete the last category.");
            alert.showAndWait();
            return;
        }

        channelsContainer.getChildren().remove(section);
    }

    private void showEditPopup(Node target, boolean isCategory, boolean isVoice, VBox categorySection) {
        try {
            // Close existing popup if open
            if (currentEditPopup != null) {
                if (currentEditTarget == target && currentEditPopup.isShowing()) {
                    return; // Do nothing if clicking same target
                }
                currentEditPopup.hide();
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/EditChannelPopup.fxml"));
            Parent root = loader.load();
            EditChannelPopupController controller = loader.getController();

            // Extract current data
            String currentName = "";
            int currentLimit = 0;
            Label nameLabel = null;
            VBox limitIndicator = null;

            if (target instanceof HBox) {
                HBox row = (HBox) target;
                for (Node child : row.getChildren()) {
                    if (child instanceof Label && (child.getStyleClass().contains("category-label")
                            || child.getStyleClass().contains("channel-name"))) {
                        nameLabel = (Label) child;
                        currentName = nameLabel.getText();
                    }
                    if (child instanceof VBox) {
                        VBox vBox = (VBox) child;
                        if (!vBox.getChildren().isEmpty() && vBox.getChildren().get(0) instanceof Label) {
                            Label label = (Label) vBox.getChildren().get(0);
                            if (label.getStyleClass().contains("voice-user-count")) {
                                limitIndicator = vBox;
                                String text = label.getText(); // "0/12"
                                if (text.contains("/")) {
                                    try {
                                        currentLimit = Integer.parseInt(text.split("/")[1]);
                                    } catch (Exception e) {
                                    }
                                }
                            }
                        }
                    }
                }
            }

            controller.setData(currentName, isCategory, isVoice, currentLimit);

            // Disable delete if it's the last category of its type
            if (isCategory && categorySection != null && categorySection.getParent() instanceof VBox) {
                VBox parent = (VBox) categorySection.getParent();
                long categoryCount = parent.getChildren().stream()
                        .filter(node -> node instanceof VBox && !((VBox) node).getChildren().isEmpty()
                                && ((VBox) node).getChildren().get(0).getStyleClass()
                                        .contains("channel-category-header"))
                        .count();

                // Also check if we are deleting the specific section passed
                if (categoryCount <= 1) {
                    controller.setDeleteDisabled(true);
                }
            }

            Popup popup = new Popup();
            popup.getContent().add(root);
            popup.setAutoHide(true);
            popup.setOnHidden(e -> {
                currentEditPopup = null;
                currentEditTarget = null;
            });
            currentEditPopup = popup;
            currentEditTarget = target;

            // Calculate position
            javafx.geometry.Bounds bounds = target.localToScreen(target.getBoundsInLocal());
            popup.show(target, bounds.getMaxX() + 5, bounds.getMinY() - 2);

            // Callbacks
            Label finalNameLabel = nameLabel;
            VBox finalLimitIndicator = limitIndicator;

            controller.setOnSave(result -> {
                if (finalNameLabel != null) {
                    finalNameLabel.setText(result.name);
                    if (isCategory) {
                        finalNameLabel.setText(result.name.toUpperCase());
                    } else if (!isVoice) {
                        finalNameLabel.setText(result.name.toLowerCase().replace(" ", "-"));
                    }
                }

                if (isVoice && finalLimitIndicator != null) {
                    if (!finalLimitIndicator.getChildren().isEmpty()
                            && finalLimitIndicator.getChildren().get(0) instanceof Label) {
                        ((Label) finalLimitIndicator.getChildren().get(0)).setText("0/" + result.userLimit);
                    }
                    finalLimitIndicator.setVisible(result.userLimit > 0);
                }
                popup.hide();
            });

            controller.setOnDelete(() -> {
                if (isCategory) {
                    handleDeleteCategory(categorySection);
                } else {
                    if (target.getParent() instanceof VBox) {
                        ((VBox) target.getParent()).getChildren().remove(target);
                    }
                }
                popup.hide();
            });

            controller.setOnCancel(popup::hide);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createTextChannel(String name, VBox container) {
        String roomId = ActiveRoomSession.getInstance().getRoomId();
        String channelId = null;

        // Sprint 13: Create channel via RPC (not direct DB)
        if (roomId != null && com.saferoom.client.ClientMenu.roomClient != null) {
            try {
                var response = com.saferoom.client.ClientMenu.roomClient.createChannel(roomId, name, "TEXT", "GENERAL");
                if (response.getSuccess()) {
                    channelId = response.getChannel().getChannelId();
                    System.out.println("[Channels] Created text channel via RPC: " + name + " (ID: " + channelId + ")");
                } else {
                    System.err.println("[Channels] RPC failed: " + response.getMessage());
                }
            } catch (Exception e) {
                System.err.println("[Channels] Failed to create channel: " + e.getMessage());
            }
        }

        if (channelId == null) {
            channelId = java.util.UUID.randomUUID().toString(); // Fallback local ID
        }

        HBox item = new HBox(8.0);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("text-channel-item");
        item.setUserData(channelId); // Store channel ID for later reference

        FontIcon icon = new FontIcon("fas-comment");
        icon.getStyleClass().add("channel-icon");

        Label label = new Label(name.toLowerCase().replace(" ", "-"));
        label.getStyleClass().add("channel-name");

        javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        item.getChildren().addAll(icon, label, spacer);

        item.setOnMouseClicked(e -> openTextChannel(label.getText(), "fas-comment", "Topic for " + label.getText()));

        container.getChildren().add(item);

        // Make reorderable
        setupReordering(container);

        // Add Context Menu
        setupChannelContextMenu(item, false);
    }

    private void setupChannelContextMenu(Node target, boolean isVoice) {
        target.setOnContextMenuRequested(e -> {
            showEditPopup(target, false, isVoice, null);
            e.consume();
        });
    }

    private void createVoiceChannel(String name, VBox container, int userLimit) {
        String roomId = ActiveRoomSession.getInstance().getRoomId();
        String channelId = null;

        // Sprint 13: Create channel via RPC
        if (roomId != null && com.saferoom.client.ClientMenu.roomClient != null) {
            try {
                var response = com.saferoom.client.ClientMenu.roomClient.createChannel(roomId, name, "VOICE",
                        "GENERAL");
                if (response.getSuccess()) {
                    channelId = response.getChannel().getChannelId();
                    System.out
                            .println("[Channels] Created voice channel via RPC: " + name + " (ID: " + channelId + ")");
                } else {
                    System.err.println("[Channels] RPC failed: " + response.getMessage());
                }
            } catch (Exception e) {
                System.err.println("[Channels] Failed to create channel: " + e.getMessage());
            }
        }

        if (channelId == null) {
            channelId = java.util.UUID.randomUUID().toString(); // Fallback
        }

        HBox item = new HBox(8.0);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("voice-channel-item");
        item.setUserData(channelId); // Store channel ID for later reference

        FontIcon icon = new FontIcon("fas-volume-up");
        icon.getStyleClass().add("channel-icon");

        Label label = new Label(name);
        label.getStyleClass().add("channel-name");

        javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        VBox limitIndicator = new VBox(1.0);
        limitIndicator.getStyleClass().add("voice-users-indicator");
        // Always visible if created with limit? Current UI hides it if 0?
        // Current UI: visible="false" initially.
        // Let's make it visible to show limit: "0/12"
        Label limitLabel = new Label("0/" + userLimit);
        limitLabel.getStyleClass().add("voice-user-count");
        limitIndicator.getChildren().add(limitLabel);
        limitIndicator.setVisible(true); // Show it

        item.getChildren().addAll(icon, label, spacer, limitIndicator);

        item.setOnMouseClicked(e -> joinVoiceChannel(name, "fas-volume-up"));

        container.getChildren().add(item);

        // Make reorderable
        setupReordering(container);

        // Add Context Menu
        setupChannelContextMenu(item, true);
    }

    @FXML
    private VBox usersSidebar;
    @FXML
    private VBox offlineUsersSection;
    @FXML
    private Button settingsBtn;
    @FXML
    private Label currentUserStatus;

    // Data - Unique fields preserved

    private boolean offlineUsersExpanded = false;
    private boolean voiceChannelsExpanded = true;
    private boolean textChannelsExpanded = true;
    private boolean fileChannelsExpanded = true;
    private MainController mainController;

    private enum SidebarState {
        OPEN, COMPACT, CLOSED
    }

    private SidebarState currentSidebarState = SidebarState.OPEN;

    // Sprint 12: Track current channel for message persistence
    private String currentChannelId = null;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupChannelHandlers();
        setupChannelCategoryToggles();
        setupUserControls();

        // Setup Drag and Drop Reordering
        setupReordering(channelsContainer);
        setupReordering(voiceChannelsList);
        setupReordering(textChannelsList);
        setupReordering(fileChannelsList);

        setupReordering(fileChannelsList);

        // Sprint 10: Initialize from ActiveRoomSession if available, otherwise use mock
        // fallback
        initializeRoomSession();

        setupContextMenusForExistingNodes();
        populateUsers();
        setupMessageListView();

        // Auto-join voice to show participants
        joinVoiceChannel("General", "fas-volume-up");

        // Set default view
        showDefaultView();

        // Setup message sending
        sendMessageBtn.setOnAction(event -> sendMessage());
        messageTextField.setOnAction(event -> sendMessage());

        // Toggle Users Sidebar
        if (toggleUsersBtn != null) {
            toggleUsersBtn.setOnAction(event -> toggleUsersSidebar());
        }
        if (toggleUsersBtnDefault != null) {
            toggleUsersBtnDefault.setOnAction(event -> toggleUsersSidebar());
        }

        // Ensure server pane allows window resize by not consuming edge mouse events
        setupResizeEventPassthrough();
    }

    public void setServerInfo(String serverName, String serverIconLiteral) {
        this.currentServerName = serverName;
        serverNameLabel.setText(serverName);
        if (serverIcon != null) {
            serverIcon.setIconLiteral(serverIconLiteral);
        }
        updateMemberCount();

        // Update privacy badge (Mock logic for demonstration)
        if (serverPrivacyBadge != null) {
            boolean isPrivate = serverName.toLowerCase().contains("private")
                    || serverName.toLowerCase().contains("secure");
            serverPrivacyBadge.setText(isPrivate ? "PRIVATE" : "PUBLIC");
            serverPrivacyBadge.getStyleClass().removeAll("private", "public");
            serverPrivacyBadge.getStyleClass().add(isPrivate ? "private" : "public");
        }
    }

    private void setupChannelHandlers() {
        // Voice Channel Handlers
        voiceGeneral.setOnMouseClicked(event -> joinVoiceChannel("General", "fas-volume-up"));
        voiceMeeting.setOnMouseClicked(event -> joinVoiceChannel("Meeting Room", "fas-volume-up"));
        voicePrivate.setOnMouseClicked(event -> joinVoiceChannel("Private Discussion", "fas-lock"));

        // Text Channel Handlers
        textGeneral.setOnMouseClicked(
                event -> openTextChannel("general", "fas-hashtag", "General discussion for the team"));
        textAnnouncements.setOnMouseClicked(
                event -> openTextChannel("announcements", "fas-bullhorn", "Important announcements and updates"));
        textTeam.setOnMouseClicked(
                event -> openTextChannel("team-chat", "fas-hashtag", "Team coordination and planning"));
        textPrivate.setOnMouseClicked(event -> openTextChannel("private", "fas-lock", "Private team discussions"));

        // File Channel Handlers
        fileResources.setOnMouseClicked(event -> openTextChannel("resources", "fas-file-alt", "Shared resources"));
        fileProject.setOnMouseClicked(
                event -> openTextChannel("project-files", "fas-file-code", "Project files repository"));

        // Voice users indicators

        // Update channel selection styling
        updateChannelSelection();

        // Offline users section toggle
        setupOfflineUsersToggle();
    }

    private void setupOfflineUsersToggle() {
        Node offlineHeader = offlineUsersSection.getChildren().get(0);
        offlineHeader.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                offlineUsersExpanded = !offlineUsersExpanded;
                offlineUsersList.setVisible(offlineUsersExpanded);
                offlineUsersList.setManaged(offlineUsersExpanded);

                // Update arrow icon
                HBox headerBox = (HBox) offlineHeader;
                FontIcon arrow = (FontIcon) headerBox.getChildren().get(0);
                arrow.setIconLiteral(offlineUsersExpanded ? "fas-chevron-down" : "fas-chevron-right");
            }
        });
    }

    private void setupChannelCategoryToggles() {
        // Voice Channels Toggle
        if (voiceChannelsSection != null && !voiceChannelsSection.getChildren().isEmpty()) {
            Node header = voiceChannelsSection.getChildren().get(0);
            header.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    voiceChannelsExpanded = !voiceChannelsExpanded;
                    voiceChannelsList.setVisible(voiceChannelsExpanded);
                    voiceChannelsList.setManaged(voiceChannelsExpanded);

                    if (header instanceof HBox) {
                        HBox headerBox = (HBox) header;
                        if (!headerBox.getChildren().isEmpty() && headerBox.getChildren().get(0) instanceof FontIcon) {
                            FontIcon arrow = (FontIcon) headerBox.getChildren().get(0);
                            arrow.setIconLiteral(voiceChannelsExpanded ? "fas-chevron-down" : "fas-chevron-right");
                        }
                    }
                }
            });
        }

        // Text Channels Toggle
        if (textChannelsSection != null && !textChannelsSection.getChildren().isEmpty()) {
            Node header = textChannelsSection.getChildren().get(0);
            header.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    textChannelsExpanded = !textChannelsExpanded;
                    textChannelsList.setVisible(textChannelsExpanded);
                    textChannelsList.setManaged(textChannelsExpanded);

                    if (header instanceof HBox) {
                        HBox headerBox = (HBox) header;
                        if (!headerBox.getChildren().isEmpty() && headerBox.getChildren().get(0) instanceof FontIcon) {
                            FontIcon arrow = (FontIcon) headerBox.getChildren().get(0);
                            arrow.setIconLiteral(textChannelsExpanded ? "fas-chevron-down" : "fas-chevron-right");
                        }
                    }
                }
            });
        }

        // File Channels Toggle
        if (fileChannelsSection != null && !fileChannelsSection.getChildren().isEmpty()) {
            Node header = fileChannelsSection.getChildren().get(0);
            header.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    fileChannelsExpanded = !fileChannelsExpanded;
                    fileChannelsList.setVisible(fileChannelsExpanded);
                    fileChannelsList.setManaged(fileChannelsExpanded);

                    if (header instanceof HBox) {
                        HBox headerBox = (HBox) header;
                        if (!headerBox.getChildren().isEmpty() && headerBox.getChildren().get(0) instanceof FontIcon) {
                            FontIcon arrow = (FontIcon) headerBox.getChildren().get(0);
                            arrow.setIconLiteral(fileChannelsExpanded ? "fas-chevron-down" : "fas-chevron-right");
                        }
                    }
                }
            });
        }
    }

    private void setupUserControls() {
        muteBtn.setOnAction(event -> toggleMute());
        deafenBtn.setOnAction(event -> toggleDeafen());
        settingsBtn.setOnAction(event -> openSettings());

        // Voice controls
        toggleMicBtn.setOnAction(event -> toggleMute());
        toggleDeafenBtn.setOnAction(event -> toggleDeafen());
        shareScreenBtn.setOnAction(event -> shareScreen());
        leaveVoiceBtn.setOnAction(event -> leaveVoiceChannel());

        // Chat controls
        sendMessageBtn.setOnAction(event -> sendMessage());
        messageTextField.setOnAction(event -> sendMessage());
    }

    private void initializeMockVoiceUsers() {
        if (voiceUsersContainer != null) {
            voiceUsersContainer.getChildren().clear();

            java.util.List<User> mockVoiceUsers = new java.util.ArrayList<>();
            mockVoiceUsers.add(new User("v1", "SilentBob", "Online", "Member", "", true, "Listening"));
            mockVoiceUsers.add(new User("v2", "LoudMouth", "Online", "Admin", "fas-crown", true, "Talking"));
            mockVoiceUsers.add(new User("v3", "MusicBot", "Online", "Bot", "fas-robot", true, "Playing Music"));

            // Set mock speaking states
            for (User user : mockVoiceUsers) {
                VBox userItem = createVoiceUserItem(user);

                // Mock states for visual demo
                if (user.getUsername().equals("LoudMouth")) {
                    userItem.getStyleClass().add("speaking");
                }
                if (user.getUsername().equals("SilentBob")) {
                    userItem.getStyleClass().add("muted");
                    // Find mic icon and update it
                    if (userItem.getChildren().size() > 2 && userItem.getChildren().get(2) instanceof FontIcon) {
                        ((FontIcon) userItem.getChildren().get(2)).setIconLiteral("fas-microphone-slash");
                    }
                }

                voiceUsersContainer.getChildren().add(userItem);
            }
        }
    }

    // Sprint 10: Real presence integration
    private DataFSM currentFSM;

    private void initializeRoomSession() {
        DataFSM fsm = ActiveRoomSession.getInstance().getFSM();
        if (fsm != null) {
            this.currentFSM = fsm;
            System.out.println("[ServerController] Connected to real room session: "
                    + ActiveRoomSession.getInstance().getRoomName());

            // Listen for real presence updates and events
            fsm.addUIListener(new DataFSM.UIListener() {
                @Override
                public void onPresenceUpdate(java.util.List<RoomPeer> peers) {
                    Platform.runLater(() -> updateMembersFromPresence(peers));
                }

                @Override
                public void onStateChange(DataFSM.State newState) {
                    System.out.println("[ServerController] FSM state: " + newState);
                }

                @Override
                public void onChannelCreated(com.saferoom.rooms.grpc.ChannelMetadata ch) {
                    Platform.runLater(() -> {
                        System.out.println("[ServerController] New channel received: " + ch.getName());
                        if ("VOICE".equals(ch.getType())) {
                            if (voiceChannels.stream().noneMatch(c -> c.getId().equals(ch.getChannelId()))) {
                                voiceChannels.add(new Channel(ch.getChannelId(), ch.getName(), "voice", false));
                            }
                        } else if ("TEXT".equals(ch.getType())) {
                            if (textChannels.stream().noneMatch(c -> c.getId().equals(ch.getChannelId()))) {
                                textChannels.add(new Channel(ch.getChannelId(), ch.getName(), "text", false));
                            }
                        }
                    });
                }

                @Override
                public void onChannelDeleted(String channelId) {
                    Platform.runLater(() -> {
                        System.out.println("[ServerController] Channel deleted: " + channelId);
                        voiceChannels.removeIf(c -> c.getId().equals(channelId));
                        textChannels.removeIf(c -> c.getId().equals(channelId));
                        if (channelId.equals(currentChannelId)) {
                            messagesListView.getItems().clear();
                            currentChannelName.setText("Channel Deleted");
                            currentChannelId = null;
                            messageTextField.setDisable(true);
                        }
                    });
                }

                @Override
                public void onMessageReceived(com.saferoom.rooms.grpc.ChatMessage msg) {
                    Platform.runLater(() -> {
                        if (msg.getChannelId().equals(currentChannelId)) {
                            String content = msg.getContent();
                            String sender = msg.getSenderUsername();
                            String avatar = sender.isEmpty() ? "?" : sender.substring(0, 1).toUpperCase();
                            messagesListView.getItems().add(new Message(content, sender, avatar));
                            messagesListView.scrollTo(messagesListView.getItems().size() - 1);
                        }
                    });
                }
            });

            // Sprint 11: Load channels from DB
            loadChannelsFromDB();
        } else {
            // Fallback to mock data if no active session
            System.out.println("[ServerController] No active room session, using mock data");
            initializeMockData();
        }
    }

    // Sprint 13: Load persisted channels via RPC
    private void loadChannelsFromDB() {
        String roomId = ActiveRoomSession.getInstance().getRoomId();
        if (roomId == null)
            return;

        // Clear existing mock channels
        voiceChannels.clear();
        textChannels.clear();

        if (com.saferoom.client.ClientMenu.roomClient != null) {
            try {
                var response = com.saferoom.client.ClientMenu.roomClient.listChannels(roomId);
                var channels = response.getChannelsList();
                System.out.println("[ServerController] Loading " + channels.size() + " channels via RPC");

                for (var ch : channels) {
                    switch (ch.getType()) {
                        case "VOICE":
                            voiceChannels.add(new Channel(ch.getChannelId(), ch.getName(), "voice", false));
                            break;
                        case "TEXT":
                            textChannels.add(new Channel(ch.getChannelId(), ch.getName(), "text", false));
                            break;
                        case "FILE":
                            // TODO: Add file channels support
                            break;
                    }
                }
            } catch (Exception e) {
                System.err.println("[ServerController] Failed to load channels via RPC: " + e.getMessage());
            }
        }

        // If no channels exist, create default ones handled by logic elsewhere or UI
        // remains empty
        if (voiceChannels.isEmpty() && textChannels.isEmpty()) {
            System.out.println("[ServerController] No channels found via RPC.");
        }
    }

    private void updateMembersFromPresence(java.util.List<RoomPeer> peers) {
        System.out.println("[ServerController] Updating members from presence: " + peers.size() + " peers");

        // Clear existing users
        serverUsers.clear();

        // Convert RoomPeer to User objects
        for (RoomPeer peer : peers) {
            boolean isOnline = true; // Presence = online
            String username = peer.getNodeId();
            String status = "Online";
            String role = "Member"; // TODO: Get from room membership
            String roleIcon = "";
            String activity = "Connected";

            serverUsers.add(new User(peer.getNodeId(), username, status, role, roleIcon, isOnline, activity));
        }

        // If no peers yet, add at least the current user
        if (serverUsers.isEmpty()) {
            String currentUser = com.saferoom.gui.service.ChatService.getInstance().getCurrentUsername();
            if (currentUser != null) {
                serverUsers.add(new User(currentUser, currentUser, "Online", "Member", "", true, "Connected"));
            }
        }

        // Refresh the UI
        populateUsers();
    }

    private void initializeMockData() {
        // Create mock users
        serverUsers.add(new User("1", "Username", "Online", "Owner", "fas-crown", true, "Working on project"));
        serverUsers.add(new User("2", "Alice Cooper", "Online", "Admin", "fas-shield-alt", true, "In meeting"));
        serverUsers.add(new User("3", "Bob Smith", "Online", "Member", "", true, "Available"));
        serverUsers.add(new User("4", "Carol Johnson", "Idle", "Member", "", true, "Away"));
        serverUsers.add(new User("5", "David Wilson", "Do Not Disturb", "Moderator", "fas-hammer", true, "Focusing"));
        serverUsers.add(new User("6", "Emma Davis", "Online", "Member", "", true, "Chatting"));

        // Add some offline users
        for (int i = 7; i <= 20; i++) {
            serverUsers.add(
                    new User(String.valueOf(i), "User" + i, "Offline", "Member", "", false, "Last seen 2 hours ago"));
        }

        // Create mock channels
        voiceChannels.add(new Channel("voice_general", "General", "voice", false));
        voiceChannels.add(new Channel("voice_meeting", "Meeting Room", "voice", false));
        voiceChannels.add(new Channel("voice_private", "Private Discussion", "voice", true));

        textChannels.add(new Channel("text_general", "general", "text", false));
        textChannels.add(new Channel("text_announcements", "announcements", "text", false));
        textChannels.add(new Channel("text_team", "team-chat", "text", false));
        textChannels.add(new Channel("text_private", "private", "text", true));
    }

    private void populateUsers() {
        onlineUsersList.getChildren().clear();
        offlineUsersList.getChildren().clear();

        int onlineCount = 0;
        int offlineCount = 0;

        for (User user : serverUsers) {
            HBox userItem = createUserItem(user);

            if (user.isOnline()) {
                onlineUsersList.getChildren().add(userItem);
                onlineCount++;
            } else {
                offlineUsersList.getChildren().add(userItem);
                offlineCount++;
            }
        }

        onlineUsersCount.setText(String.valueOf(onlineCount));
        offlineUsersCount.setText(String.valueOf(offlineCount));
        updateMemberCount();
    }

    private HBox createUserItem(User user) {
        HBox userItem = new HBox(8.0);
        userItem.setAlignment(Pos.CENTER_LEFT);
        userItem.getStyleClass().add("user-item");

        // User avatar
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("user-avatar-tiny");
        // Add status style class to avatar for border
        avatar.getStyleClass().add(getStatusStyleClass(user.getStatus()));
        Label avatarText = new Label(user.getUsername().substring(0, 1).toUpperCase());
        avatarText.getStyleClass().add("user-avatar-text-tiny");
        avatar.getChildren().add(avatarText);

        // Username and role
        VBox userInfo = new VBox(1.0);
        HBox nameAndRole = new HBox(5.0);
        nameAndRole.setAlignment(Pos.CENTER_LEFT);

        Label username = new Label(user.getUsername());
        username.getStyleClass().add("user-name-small");
        nameAndRole.getChildren().add(username);

        if (!user.getRoleIcon().isEmpty()) {
            FontIcon roleIcon = new FontIcon(user.getRoleIcon());
            roleIcon.getStyleClass().add("user-role-icon");
            nameAndRole.getChildren().add(roleIcon);
        }

        userInfo.getChildren().add(nameAndRole);

        if (!user.getActivity().isEmpty()) {
            Label activity = new Label(user.getActivity());
            activity.getStyleClass().add("user-activity");
            userInfo.getChildren().add(activity);
        }

        userItem.getChildren().addAll(avatar, userInfo);

        // Click handler to show user info popup
        userItem.setOnMouseClicked(event -> showUserInfoPopup(user, userItem));

        return userItem;
    }

    private String getStatusStyleClass(String status) {
        switch (status.toLowerCase()) {
            case "online":
                return "status-online";
            case "idle":
                return "status-idle";
            case "do not disturb":
                return "status-dnd";
            case "offline":
                return "status-offline";
            default:
                return "status-offline";
        }
    }

    private void showUserInfoPopup(User user, javafx.scene.Node anchorNode) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/UserInfoPopup.fxml"));
            Parent root = loader.load();

            UserInfoPopupController controller = loader.getController();
            controller.setUserInfo(user);

            // Set the close callback to hide the overlay
            controller.setOnCloseCallback(() -> {
                popupOverlay.setVisible(false);
                popupOverlay.setManaged(false);
                popupContainer.getChildren().clear();
                // Reset styling
                popupContainer.setTranslateX(0);
                popupContainer.setTranslateY(0);
                javafx.scene.layout.StackPane.setAlignment(popupContainer, Pos.CENTER);
            });

            // Add content to overlay
            popupContainer.getChildren().setAll(root);

            // Position Logic
            if (anchorNode != null) {

                javafx.geometry.Bounds anchorBounds = anchorNode.localToScene(anchorNode.getBoundsInLocal());
                double popupWidth = 300;
                double popupHeight = 360;

                double x = anchorBounds.getMinX() - popupWidth - 90;
                double y = anchorBounds.getMinY();

                double sceneHeight = popupOverlay.getScene().getHeight();

                if (y + popupHeight > sceneHeight) {
                    y = sceneHeight - popupHeight - 60;
                }

                if (y < 10) {
                    y = 10;
                }

                if (x < 10) {
                    x = anchorBounds.getMaxX() + 10;
                }

                javafx.scene.layout.StackPane.setAlignment(popupContainer, Pos.TOP_LEFT);
                popupContainer.setTranslateX(x);
                popupContainer.setTranslateY(y);
            } else {
                javafx.scene.layout.StackPane.setAlignment(popupContainer, Pos.CENTER);
            }

            popupOverlay.setVisible(true);
            popupOverlay.setManaged(true);

            popupOverlay.setOnMouseClicked(event -> {
                if (event.getTarget() == popupOverlay) {
                    popupOverlay.setVisible(false);
                    popupOverlay.setManaged(false);
                    popupContainer.getChildren().clear();
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Could not load UserInfoPopup.fxml");
        }
    }

    private void openTextChannel(String channelName, String iconLiteral, String topic) {
        currentChannelName.setText(channelName);
        currentChannelIcon.setIconLiteral(iconLiteral);
        currentChannelTopic.setText(topic);
        messageTextField.setPromptText("Message #" + channelName);

        // Sprint 12: Find channel ID and load messages from DB
        messagesListView.getItems().clear();
        currentChannelId = findChannelIdByName(channelName);

        if (currentChannelId != null) {
            // Sprint 14: Load messages via RPC
            if (com.saferoom.client.ClientMenu.roomClient != null) {
                try {
                    var response = com.saferoom.client.ClientMenu.roomClient.getMessages(currentChannelId, 50);
                    var messages = response.getMessagesList();
                    System.out.println("[Messages] Loading " + messages.size() + " messages via RPC");
                    for (var msg : messages) {
                        String sender = msg.getSenderUsername();
                        String avatar = sender.isEmpty() ? "?" : sender.substring(0, 1).toUpperCase();
                        messagesListView.getItems().add(new Message(msg.getContent(), sender, avatar));
                    }
                } catch (Exception e) {
                    System.err.println("[Messages] Failed to load messages via RPC: " + e.getMessage());
                }
            } else {
                // Fallback to mock data or empty if RPC not available
                // For now, doing nothing effectively leaves it empty, which is correct until
                // messages arrive
            }
        }

        // If no messages loaded, show a welcome message
        if (messagesListView.getItems().isEmpty()) {
            messagesListView.getItems().add(
                    new Message("Welcome to #" + channelName + "! Start the conversation.", "System", "S"));
        }

        showTextChatView();
        updateChannelSelection();

        // Clear notification badges for this channel
        clearChannelNotifications(channelName);
    }

    // Sprint 12: Find channel ID by name from current room's channels
    private String findChannelIdByName(String channelName) {
        for (Channel ch : textChannels) {
            if (ch.getName().equalsIgnoreCase(channelName) ||
                    ch.getName().equalsIgnoreCase(channelName.toLowerCase().replace(" ", "-"))) {
                return ch.getId();
            }
        }
        return null;
    }

    private void joinVoiceChannel(String channelName, String iconLiteral) {
        voiceChannelName.setText(channelName);

        // Populate with mock voice users
        voiceUsersContainer.getChildren().clear();

        List<User> voiceUsers = new java.util.ArrayList<>();

        // Add CURRENT USER first (use actual username from currentUserName label)
        String actualUsername = currentUserName.getText();
        if (actualUsername == null || actualUsername.isEmpty()) {
            actualUsername = "Guest"; // Fallback
        }
        User currentUser = new User("me", actualUsername, "Online", "Member", "", true, "");
        voiceUsers.add(currentUser);

        // Add some other mock users
        voiceUsers.addAll(serverUsers.subList(0, Math.min(5, serverUsers.size())));

        for (User user : voiceUsers) {
            if (user.isOnline()) {
                VBox voiceUser = createVoiceUserItem(user);
                voiceUsersContainer.getChildren().add(voiceUser);

                // Special styling for current user and store references
                if (user.getId().equals("me")) {
                    voiceUser.getStyleClass().add("current-user-voice-card");
                    currentUserVoiceCard = voiceUser;
                    // Find and store the mic icon (it's the 3rd child: avatar, username, mic)
                    if (voiceUser.getChildren().size() >= 3) {
                        Node micNode = voiceUser.getChildren().get(2);
                        if (micNode instanceof FontIcon) {
                            currentUserMicIcon = (FontIcon) micNode;
                            // Apply current mute/deafen state
                            updateCurrentUserVoiceStatus();
                        }
                    }
                }
            }
        }

        showVoiceChatView();
        updateChannelSelection();
    }

    private VBox createVoiceUserItem(User user) {
        VBox voiceUser = new VBox(5.0);
        voiceUser.setAlignment(Pos.CENTER);
        voiceUser.getStyleClass().add("voice-user-item");

        // User avatar - set explicit size for circular shape
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("voice-user-avatar");
        avatar.setMinSize(64, 64);
        avatar.setMaxSize(64, 64);
        avatar.setPrefSize(64, 64);
        Label avatarText = new Label(user.getUsername().substring(0, 1).toUpperCase());
        avatarText.getStyleClass().add("voice-user-avatar-text");
        avatar.getChildren().add(avatarText);

        // Username
        Label username = new Label(user.getUsername());
        username.getStyleClass().add("voice-user-name");

        // Mic status
        FontIcon micIcon = new FontIcon("fas-microphone");
        micIcon.getStyleClass().add("voice-user-mic");

        voiceUser.getChildren().addAll(avatar, username, micIcon);

        // Add speaking/muted logic (mock)
        if (user.getUsername().equals("Alice Cooper")) {
            voiceUser.getStyleClass().add("speaking");
        }
        if (user.getUsername().equals("Bob Smith")) {
            voiceUser.getStyleClass().add("muted");
            micIcon.setIconLiteral("fas-microphone-slash");
        }

        return voiceUser;
    }

    private void clearChannelNotifications(String channelName) {
        switch (channelName) {
            case "general":
                textGeneralNotification.setVisible(false);
                break;
            case "team-chat":
                textTeamNotification.setVisible(false);
                break;
        }
    }

    private void showDefaultView() {
        defaultChannelView.setVisible(true);
        textChatView.setVisible(false);
        voiceChatView.setVisible(false);
    }

    private void showTextChatView() {
        defaultChannelView.setVisible(false);
        textChatView.setVisible(true);
        voiceChatView.setVisible(false);
    }

    private void showVoiceChatView() {
        defaultChannelView.setVisible(false);
        textChatView.setVisible(false);
        voiceChatView.setVisible(true);
    }

    private void updateChannelSelection() {
        // Remove active class from all channels
        clearChannelSelections();

        // Add active class to current channel (implementation can be enhanced)
        // This would typically track the current channel and apply styling
    }

    private void clearChannelSelections() {
        // Remove active styling from all channel items
        voiceGeneral.getStyleClass().remove("channel-active");
        voiceMeeting.getStyleClass().remove("channel-active");
        voicePrivate.getStyleClass().remove("channel-active");
        textGeneral.getStyleClass().remove("channel-active");
        textAnnouncements.getStyleClass().remove("channel-active");
        textTeam.getStyleClass().remove("channel-active");
        textPrivate.getStyleClass().remove("channel-active");
        fileResources.getStyleClass().remove("channel-active");
        fileProject.getStyleClass().remove("channel-active");
    }

    private void sendMessage() {
        String messageText = messageTextField.getText().trim();
        if (messageText.isEmpty()) {
            return;
        }

        // Get the actual current user's name
        String actualUsername = currentUserName.getText();
        if (actualUsername == null || actualUsername.isEmpty()) {
            actualUsername = "Guest"; // Fallback
        }

        // Get first character for avatar
        String avatarChar = actualUsername.substring(0, 1).toUpperCase();

        // Create and add the message
        Message newMessage = new Message(messageText, actualUsername, avatarChar);
        messagesListView.getItems().add(newMessage);

        // Sprint 14: Persist message via RPC
        if (currentChannelId != null && com.saferoom.client.ClientMenu.roomClient != null) {
            try {
                var response = com.saferoom.client.ClientMenu.roomClient.sendMessageToChannel(currentChannelId,
                        actualUsername, messageText);
                if (response.getSuccess()) {
                    System.out
                            .println("[Messages] Saved message via RPC: " + response.getSavedMessage().getMessageId());
                } else {
                    System.err.println("[Messages] RPC failed: " + response.getMessage());
                }
            } catch (Exception e) {
                System.err.println("[Messages] Failed to save message via RPC: " + e.getMessage());
            }
        }

        // Clear the input field
        messageTextField.clear();

        // Scroll to bottom
        messagesListView.scrollTo(messagesListView.getItems().size() - 1);
    }

    private void toggleMute() {
        isUserMuted = !isUserMuted;
        FontIcon micIcon = (FontIcon) muteBtn.getGraphic();
        FontIcon voiceMicIcon = (FontIcon) toggleMicBtn.getGraphic();

        if (isUserMuted) {
            micIcon.setIconLiteral("fas-microphone-slash");
            voiceMicIcon.setIconLiteral("fas-microphone-slash");
            muteBtn.getStyleClass().add("muted");
            toggleMicBtn.getStyleClass().add("muted");
        } else {
            micIcon.setIconLiteral("fas-microphone");
            voiceMicIcon.setIconLiteral("fas-microphone");
            muteBtn.getStyleClass().remove("muted");
            toggleMicBtn.getStyleClass().remove("muted");
        }

        // Update current user's voice card
        updateCurrentUserVoiceStatus();
    }

    private void toggleDeafen() {
        isUserDeafened = !isUserDeafened;

        // Get StackPane from buttons
        StackPane headphonesStack = (StackPane) deafenBtn.getGraphic();
        StackPane voiceHeadphonesStack = (StackPane) toggleDeafenBtn.getGraphic();

        // Get slash icons (Shadow is index 1, Red Slash is index 2)
        Node slashShadow = headphonesStack.getChildren().get(1);
        Node slashIcon = headphonesStack.getChildren().get(2);

        Node voiceSlashShadow = voiceHeadphonesStack.getChildren().get(1);
        Node voiceSlashIcon = voiceHeadphonesStack.getChildren().get(2);

        if (isUserDeafened) {
            slashShadow.setVisible(true);
            slashIcon.setVisible(true);
            voiceSlashShadow.setVisible(true);
            voiceSlashIcon.setVisible(true);

            deafenBtn.getStyleClass().add("deafened");
            toggleDeafenBtn.getStyleClass().add("deafened");

            // If deafened, also mute
            if (!isUserMuted) {
                toggleMute();
            }
        } else {
            slashShadow.setVisible(false);
            slashIcon.setVisible(false);
            voiceSlashShadow.setVisible(false);
            voiceSlashIcon.setVisible(false);

            deafenBtn.getStyleClass().remove("deafened");
            toggleDeafenBtn.getStyleClass().remove("deafened");
        }

        // Update current user's voice card
        updateCurrentUserVoiceStatus();
    }

    private void updateCurrentUserVoiceStatus() {
        if (currentUserMicIcon != null && currentUserVoiceCard != null) {
            // Update mic icon based on mute/deafen status
            if (isUserDeafened || isUserMuted) {
                currentUserMicIcon.setIconLiteral("fas-microphone-slash");
                currentUserVoiceCard.getStyleClass().remove("speaking");
                if (!currentUserVoiceCard.getStyleClass().contains("muted")) {
                    currentUserVoiceCard.getStyleClass().add("muted");
                }
            } else {
                currentUserMicIcon.setIconLiteral("fas-microphone");
                currentUserVoiceCard.getStyleClass().remove("muted");
            }
        }
    }

    private void shareScreen() {
        System.out.println("Screen sharing feature would be implemented here");
        // Implementation for screen sharing
    }

    private void leaveVoiceChannel() {
        showDefaultView();
        System.out.println("Left voice channel");
    }

    private void openSettings() {
        if (MainController.getInstance() != null) {
            MainController.getInstance().handleSettings();
        }
    }

    private void updateMemberCount() {
        int totalMembers = serverUsers.size();
        serverMembersLabel.setText(totalMembers + " members");
        totalUsersCount.setText(String.valueOf(totalMembers));
    }

    // Method to be called from RoomsController
    public void enterServer(String serverName, String serverIconLiteral, boolean isPrivate, boolean isCustom) {
        setServerInfo(serverName, serverIconLiteral);

        if (serverPrivacyBadge != null) {
            serverPrivacyBadge.setText(isPrivate ? "PRIVATE" : "PUBLIC");
            serverPrivacyBadge.getStyleClass().removeAll("private", "public");
            serverPrivacyBadge.getStyleClass().add(isPrivate ? "private" : "public");
        }

        if (isCustom) {
            // Clear all existing channels and sections
            channelsContainer.getChildren().clear();

            // Create one default category
            createCategory("General", null);
        }
    }

    private void setupContextMenusForExistingNodes() {
        // Setup for existing voice channels
        if (voiceChannelsList != null) {
            for (Node node : voiceChannelsList.getChildren()) {
                setupChannelContextMenu(node, true);
            }
        }
        // Setup for existing text channels
        if (textChannelsList != null) {
            for (Node node : textChannelsList.getChildren()) {
                setupChannelContextMenu(node, false);
            }
        }
        // Setup for existing file channels
        if (fileChannelsList != null) {
            for (Node node : fileChannelsList.getChildren()) {
                setupChannelContextMenu(node, false);
            }
        }

        // Setup for existing categories (headers)
        if (voiceChannelsSection != null && !voiceChannelsSection.getChildren().isEmpty()) {
            setupCategoryContextMenu(voiceChannelsSection.getChildren().get(0), voiceChannelsSection);
        }
        if (textChannelsSection != null && !textChannelsSection.getChildren().isEmpty()) {
            setupCategoryContextMenu(textChannelsSection.getChildren().get(0), textChannelsSection);
        }
        if (fileChannelsSection != null && !fileChannelsSection.getChildren().isEmpty()) {
            setupCategoryContextMenu(fileChannelsSection.getChildren().get(0), fileChannelsSection);
        }
    }

    // Method to set MainController reference
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        if (mainController != null) {
            mainController.addStatusListener(this::onStatusChanged);
            updateCurrentUserDisplay();
        }
    }

    private void onStatusChanged(MainController.UserStatus status) {
        if (currentUserStatus != null) {
            // Map UserStatus enum to string text and style class
            String statusText = "Online";
            String styleClass = "status-online";

            switch (status) {
                case ONLINE:
                    statusText = "Online";
                    styleClass = "status-online";
                    break;
                case IDLE:
                    statusText = "Idle";
                    styleClass = "status-idle";
                    break;
                case DND:
                    statusText = "Do Not Disturb";
                    styleClass = "status-dnd";
                    break;
                case OFFLINE:
                    statusText = "Offline";
                    styleClass = "status-offline";
                    break;
            }

            String finalStatusText = statusText;
            String finalStyleClass = styleClass;

            // Ensure UI update happens on JavaFX Application Thread
            if (javafx.application.Platform.isFxApplicationThread()) {
                currentUserStatus.setText(finalStatusText);
                currentUserStatus.getStyleClass().removeAll("status-online", "status-idle", "status-dnd",
                        "status-offline");
                if (!currentUserStatus.getStyleClass().contains(finalStyleClass)) {
                    currentUserStatus.getStyleClass().add(finalStyleClass);
                }
            } else {
                javafx.application.Platform.runLater(() -> {
                    currentUserStatus.setText(finalStatusText);
                    currentUserStatus.getStyleClass().removeAll("status-online", "status-idle", "status-dnd",
                            "status-offline");
                    if (!currentUserStatus.getStyleClass().contains(finalStyleClass)) {
                        currentUserStatus.getStyleClass().add(finalStyleClass);
                    }
                });
            }
        }
    }

    private void updateCurrentUserDisplay() {
        if (mainController != null) {
            // Get data from UserSession which MainController also uses
            String username = UserSession.getInstance().getDisplayName();
            if (currentUserName != null) {
                currentUserName.setText(username);
            }

            // Get status from MainController
            MainController.UserStatus status = mainController.getUserStatus();
            onStatusChanged(status);
        }
    }

    private void setupResizeEventPassthrough() {
        if (serverPane != null) {
            // Set up mouse event filters to allow window resize functionality
            serverPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                // Check if mouse is near window edges
                double x = event.getSceneX();
                double y = event.getSceneY();
                double sceneWidth = serverPane.getScene().getWidth();
                double sceneHeight = serverPane.getScene().getHeight();

                final int RESIZE_BORDER = 10;

                boolean isNearEdge = x <= RESIZE_BORDER || x >= sceneWidth - RESIZE_BORDER ||
                        y <= RESIZE_BORDER || y >= sceneHeight - RESIZE_BORDER;

                if (isNearEdge) {
                    // Don't consume the event - let it bubble up to the main window
                    // for resize handling
                    return;
                }
            });

            serverPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
                // Allow drag events near edges to bubble up for window resize
                double x = event.getSceneX();
                double y = event.getSceneY();
                double sceneWidth = serverPane.getScene().getWidth();
                double sceneHeight = serverPane.getScene().getHeight();

                final int RESIZE_BORDER = 10;

                boolean isNearEdge = x <= RESIZE_BORDER || x >= sceneWidth - RESIZE_BORDER ||
                        y <= RESIZE_BORDER || y >= sceneHeight - RESIZE_BORDER;

                if (isNearEdge) {
                    // Don't consume - let the main window handle resize
                    return;
                }
            });

            serverPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> {
                // Allow mouse moved events near edges to bubble up for cursor changes
                double x = event.getSceneX();
                double y = event.getSceneY();
                double sceneWidth = serverPane.getScene().getWidth();
                double sceneHeight = serverPane.getScene().getHeight();

                final int RESIZE_BORDER = 10;

                boolean isNearEdge = x <= RESIZE_BORDER || x >= sceneWidth - RESIZE_BORDER ||
                        y <= RESIZE_BORDER || y >= sceneHeight - RESIZE_BORDER;

                if (isNearEdge) {
                    // Don't consume - let main window handle cursor changes
                    return;
                }
            });
        }
    }

    private void setupReordering(VBox container) {
        if (container == null)
            return;
        for (Node child : container.getChildren()) {
            makeDraggable(child, container);
        }

        // Listen for new items if list changes
        container.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Node node : c.getAddedSubList()) {
                        makeDraggable(node, container);
                    }
                }
            }
        });
    }

    private void makeDraggable(Node node, VBox container) {
        node.setOnDragDetected(event -> {
            // Collapse if it's a section header container
            if (container == channelsContainer) {
                // Logic to force collapse will be handled by UI state listeners if needed,
                // but simpler approach: temporarily hide content if we were dragging headers
                // specifically.
                // However, `node` here IS the VBox section (e.g. voiceChannelsSection).
                // We can access its children to find the list and hide it.
                if (node instanceof VBox) {
                    VBox section = (VBox) node;
                    // Assuming standard structure: Header HBox is index 0, List VBox is index 1
                    if (section.getChildren().size() > 1 && section.getChildren().get(1) instanceof VBox) {
                        Node list = section.getChildren().get(1);
                        list.setVisible(false);
                        list.setManaged(false);
                    }
                }
            }

            Dragboard db = node.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(container.getChildren().indexOf(node)));
            db.setContent(content);

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            db.setDragView(node.snapshot(params, null));

            event.consume();
        });

        node.setOnDragOver(event -> {
            if (event.getGestureSource() != node &&
                    event.getDragboard().hasString() &&
                    container.getChildren().contains(event.getGestureSource())) {

                event.acceptTransferModes(TransferMode.MOVE);

                double y = event.getY();
                double height = node.getLayoutBounds().getHeight();

                // Clear previous styles first
                node.getStyleClass().removeAll("drag-over-top", "drag-over-bottom");

                if (y < height / 2) {
                    if (!node.getStyleClass().contains("drag-over-top")) {
                        node.getStyleClass().add("drag-over-top");
                    }
                } else {
                    if (!node.getStyleClass().contains("drag-over-bottom")) {
                        node.getStyleClass().add("drag-over-bottom");
                    }
                }
            }
            event.consume();
        });

        node.setOnDragExited(event -> {
            node.getStyleClass().removeAll("drag-over-top", "drag-over-bottom");
            event.consume();
        });

        node.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasString()) {
                Node source = (Node) event.getGestureSource();
                // Ensure we are moving within the same container
                if (container.getChildren().contains(source)) {
                    double y = event.getY();
                    double height = node.getLayoutBounds().getHeight();

                    container.getChildren().remove(source);
                    // Find target index AFTER removing source (indices shift)
                    int targetIndex = container.getChildren().indexOf(node);

                    if (y < height / 2) {
                        // Insert before
                        container.getChildren().add(targetIndex, source);
                    } else {
                        // Insert after
                        container.getChildren().add(targetIndex + 1, source);
                    }
                    success = true;
                }
            }
            event.setDropCompleted(success);
            node.getStyleClass().removeAll("drag-over-top", "drag-over-bottom");
            event.consume();
        });

        node.setOnDragDone(event -> {
            // Restore visibility if it was a section
            if (container == channelsContainer) {
                Node source = (Node) event.getSource();
                if (source instanceof VBox) {
                    VBox section = (VBox) source;
                    if (section.getChildren().size() > 1 && section.getChildren().get(1) instanceof VBox) {
                        Node list = section.getChildren().get(1);

                        // Determine visibility based on the arrow icon in the header
                        boolean shouldBeVisible = false;
                        if (!section.getChildren().isEmpty() && section.getChildren().get(0) instanceof HBox) {
                            HBox header = (HBox) section.getChildren().get(0);
                            // Look for the arrow icon which is typically the first child
                            if (!header.getChildren().isEmpty() && header.getChildren().get(0) instanceof FontIcon) {
                                FontIcon arrow = (FontIcon) header.getChildren().get(0);
                                // If arrow is down, it should be visible
                                shouldBeVisible = "fas-chevron-down".equals(arrow.getIconLiteral());
                            }
                        }

                        list.setVisible(shouldBeVisible);
                        list.setManaged(shouldBeVisible);
                    }
                }
            }
            event.consume();
        });
    }

    private void setupMessageListView() {
        messagesListView.setCellFactory(listView -> new javafx.scene.control.ListCell<Message>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    // Check if previous message is from same user (grouping)
                    boolean isGrouped = false;
                    int index = getIndex();
                    if (index > 0) {
                        Message prev = getListView().getItems().get(index - 1);
                        if (prev != null && prev.getSenderId().equals(message.getSenderId())) {
                            // Check time difference (e.g., 5 minutes)
                            java.time.Duration diff = java.time.Duration.between(prev.getTimestamp(),
                                    message.getTimestamp());
                            if (diff.toMinutes() < 5) {
                                isGrouped = true;
                            }
                        }
                    }

                    // Main Container
                    HBox container = new HBox(10);
                    container.setAlignment(Pos.TOP_LEFT);
                    container.setPadding(new javafx.geometry.Insets(2, 0, 2, 10)); // Top/Bottom padding small for
                                                                                   // grouped

                    // Avatar Area (Left)
                    StackPane avatarContainer = new StackPane();
                    avatarContainer.setPrefWidth(40);
                    avatarContainer.setMinWidth(40);

                    if (!isGrouped) {
                        avatarContainer.setPrefHeight(40);
                        avatarContainer.setMinHeight(40);
                        container.setPadding(new javafx.geometry.Insets(10, 0, 2, 10)); // More top padding for new
                                                                                        // group

                        Label avatarLabel = new Label(message.getSenderAvatarChar());
                        avatarLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");

                        javafx.scene.shape.Circle avatarBg = new javafx.scene.shape.Circle(20);
                        avatarBg.setFill(Color.web("#5865F2")); // Discord Blurple-ish

                        avatarContainer.getChildren().addAll(avatarBg, avatarLabel);
                    } else {
                        // Grouped: no specific height, naturally collapsed
                    }

                    // Content Area (Right)
                    VBox contentBox = new VBox(2);
                    contentBox.setAlignment(Pos.TOP_LEFT);

                    if (!isGrouped) {
                        // Header: Username + Timestamp
                        HBox header = new HBox(8);
                        header.setAlignment(Pos.BASELINE_LEFT);

                        Label usernameLabel = new Label(message.getSenderId()); // Use senderId as name for now
                        usernameLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                                .ofPattern("HH:mm");
                        Label timeLabel = new Label("Today at " + message.getTimestamp().format(formatter));
                        timeLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");

                        header.getChildren().addAll(usernameLabel, timeLabel);
                        contentBox.getChildren().add(header);
                    }

                    // Message Text
                    Label messageLabel = new Label(message.getText());
                    messageLabel.setWrapText(true);
                    messageLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
                    // Limit width to avoid horizontal scroll if possible, or let ListView handle it
                    messageLabel.setMaxWidth(500); // Arbitrary max width, better to bind to list width

                    contentBox.getChildren().add(messageLabel);

                    container.getChildren().addAll(avatarContainer, contentBox);
                    setGraphic(container);
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });
    }

    private void toggleUsersSidebar() {
        switch (currentSidebarState) {
            case OPEN:
                currentSidebarState = SidebarState.COMPACT;
                break;
            case COMPACT:
                currentSidebarState = SidebarState.CLOSED;
                break;
            case CLOSED:
                currentSidebarState = SidebarState.OPEN;
                break;
        }
        updateSidebarState();
    }

    private void updateSidebarState() {
        if (usersSidebar == null)
            return;

        switch (currentSidebarState) {
            case OPEN:
                usersSidebar.setVisible(true);
                usersSidebar.setManaged(true);
                usersSidebar.setPrefWidth(240);
                usersSidebar.setMinWidth(240);
                usersSidebar.setMaxWidth(240);
                updateUserItemsState(true);
                break;
            case COMPACT:
                usersSidebar.setVisible(true);
                usersSidebar.setManaged(true);
                usersSidebar.setPrefWidth(80); // Reduced width
                usersSidebar.setMinWidth(80);
                usersSidebar.setMaxWidth(80);
                updateUserItemsState(false);
                break;
            case CLOSED:
                usersSidebar.setVisible(false);
                usersSidebar.setManaged(false);
                break;
        }

        updateSidebarHeaders(currentSidebarState == SidebarState.COMPACT);
    }

    private void updateSidebarHeaders(boolean isCompact) {
        if (usersSidebar == null)
            return;

        for (Node node : usersSidebar.getChildren()) {
            if (node instanceof HBox) {
                // Direct header (e.g. Online Users header)
                updateHeaderContent((HBox) node, isCompact);
            } else if (node instanceof VBox) {
                // Section container (e.g. Offline Users section)
                VBox section = (VBox) node;
                if (!section.getChildren().isEmpty() && section.getChildren().get(0) instanceof HBox) {
                    updateHeaderContent((HBox) section.getChildren().get(0), isCompact);
                }
            }
        }
    }

    private void updateHeaderContent(javafx.scene.layout.Pane header, boolean isCompact) {
        for (Node child : header.getChildren()) {
            if (child instanceof Label) {
                Label label = (Label) child;
                if (isCompact) {
                    // Only show if text is purely numeric (user counts)
                    boolean isNumeric = label.getText().matches("\\d+");
                    label.setVisible(isNumeric);
                    label.setManaged(isNumeric);
                } else {
                    // Show everything in open mode
                    label.setVisible(true);
                    label.setManaged(true);
                }
            }
        }
    }

    private void updateUserItemsState(boolean showDetails) {
        // Update styling for lists container if needed
        if (!showDetails) {
            onlineUsersList.setAlignment(Pos.TOP_CENTER);
            offlineUsersList.setAlignment(Pos.TOP_CENTER);
        } else {
            onlineUsersList.setAlignment(Pos.TOP_LEFT);
            offlineUsersList.setAlignment(Pos.TOP_LEFT);
        }

        // Iterate through all user items in both lists
        updateListItems(onlineUsersList, showDetails);
        updateListItems(offlineUsersList, showDetails);
    }

    private void updateListItems(VBox list, boolean showDetails) {
        for (Node node : list.getChildren()) {
            if (node instanceof HBox) {
                HBox item = (HBox) node;
                // Assuming standard structure from createUserItem: Avatar (StackPane) is index
                // 0, Details (VBox) is index 1
                if (item.getChildren().size() > 1) {
                    Node details = item.getChildren().get(1);
                    details.setVisible(showDetails);
                    details.setManaged(showDetails);
                }

                // Adjust alignment for the item itself
                item.setAlignment(showDetails ? Pos.CENTER_LEFT : Pos.CENTER);
            }
        }
    }
}
