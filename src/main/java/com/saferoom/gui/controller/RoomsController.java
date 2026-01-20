package com.saferoom.gui.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;
import com.saferoom.client.ClientMenu;
import com.saferoom.rooms.client.logic.DataFSM;
import com.saferoom.rooms.client.ActiveRoomSession;
import com.saferoom.rooms.grpc.*;

import java.net.URL;
import java.util.ResourceBundle;

public class RoomsController implements Initializable {

    private static class RoomData {
        String id;
        String name;
        boolean isPrivate;
        boolean isCustom;

        public RoomData(String id, String name, boolean isPrivate, boolean isCustom) {
            this.id = id;
            this.name = name;
            this.isPrivate = isPrivate;
            this.isCustom = isCustom;
        }
    }

    @FXML
    private TextField searchTextField;

    @FXML
    private FlowPane hubListContainer;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // Initialize search functionality for rooms
        searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            filterRooms(newValue);
        });

        // Clear any static/mock rooms defined in FXML
        if (hubListContainer != null) {
            hubListContainer.getChildren().clear();
        }

        // Setup room card click handlers (for any pre-existing? No, we cleared them)
        // setupRoomHandlers(); // No longer needed if we clear list

        // Load rooms from server
        refreshRooms();
    }

    private void refreshRooms() {
        if (ClientMenu.roomClient == null)
            return;

        try {
            javafx.application.Platform.runLater(() -> {
                if (hubListContainer != null)
                    hubListContainer.getChildren().clear();
            });

            var response = ClientMenu.roomClient.listRooms("");
            for (var room : response.getRoomsList()) {
                String id = room.getRoomId();
                String name = room.getName();
                boolean isPrivate = room.getIsPrivate();
                // Create card for each room
                javafx.application.Platform.runLater(() -> {
                    addRoomCard(id, name, isPrivate, null); // Null image for now
                });
            }
        } catch (Exception e) {
            System.err.println("Error listing rooms: " + e.getMessage());
        }
    }

    private void filterRooms(String searchText) {
        String lowerCaseSearchText = searchText.toLowerCase();

        if (hubListContainer != null) {
            for (Node node : hubListContainer.getChildren()) {
                if (node instanceof VBox && node.getStyleClass().contains("compact-room-card")) {
                    VBox roomCard = (VBox) node;
                    Label roomNameLabel = findRoomNameLabel(roomCard);

                    if (roomNameLabel != null) {
                        String roomName = roomNameLabel.getText().toLowerCase();
                        if (roomName.contains(lowerCaseSearchText) || searchText.isEmpty()) {
                            roomCard.setVisible(true);
                            roomCard.setManaged(true);
                        } else {
                            roomCard.setVisible(false);
                            roomCard.setManaged(false);
                        }
                    }
                }
            }
        }
    }

    private Label findRoomNameLabel(VBox roomCard) {
        // Look for the room name label in the compact room card
        for (Node node : roomCard.getChildren()) {
            if (node instanceof VBox && node.getStyleClass().contains("room-info-compact")) {
                VBox roomInfo = (VBox) node;
                for (Node infoChild : roomInfo.getChildren()) {
                    if (infoChild instanceof Label && infoChild.getStyleClass().contains("room-name-compact")) {
                        return (Label) infoChild;
                    }
                }
            }
        }
        return null;
    }

    private void setupRoomHandlers() {
        if (hubListContainer != null) {
            for (Node node : hubListContainer.getChildren()) {
                if (node instanceof VBox && node.getStyleClass().contains("compact-room-card")) {
                    VBox roomCard = (VBox) node;

                    // Setup click handler for room card to navigate to room
                    roomCard.setOnMouseClicked(event -> {
                        // Check if click was on action buttons, if so, don't navigate
                        if (!isActionButtonClick(event.getTarget())) {
                            navigateToRoom(roomCard);
                        }
                    });

                    // Setup handlers for action buttons
                    setupCompactActionButtons(roomCard);
                }
            }
        }
    }

    // Helper to get RoomData safely
    private RoomData getRoomData(VBox roomCard) {
        if (roomCard.getUserData() instanceof RoomData) {
            return (RoomData) roomCard.getUserData();
        }
        return null; // Should ideally not happen for real rooms
    }

    private boolean isActionButtonClick(Object target) {
        // Check if the click target is an action button or its child elements
        if (target instanceof Node) {
            Node node = (Node) target;
            while (node != null) {
                if (node.getStyleClass().contains("quick-action-btn")) {
                    return true;
                }
                node = node.getParent();
            }
        }
        return false;
    }

    private void navigateToRoom(VBox roomCard) {
        // Get room data first
        RoomData data = getRoomData(roomCard);
        String roomId = null;
        String roomName = "Unknown Room";
        boolean isPrivate = false;
        boolean isCustom = false;

        if (data != null) {
            roomId = data.id;
            roomName = data.name;
            isPrivate = data.isPrivate;
            isCustom = data.isCustom;
        } else {
            // Fallback to label for legacy/mock
            Label roomNameLabel = findRoomNameLabel(roomCard);
            if (roomNameLabel != null) {
                roomName = roomNameLabel.getText();
                roomId = roomName; // Use name as ID for fallback
            }
        }

        System.out.println("Navigating to room: " + roomName + " (ID: " + roomId + ")");

        if (roomId == null)
            return;

        // [Rooms v1] Start DataFSM for this room
        try {
            // Using username as nodeId for v1 PoC
            String nodeId = com.saferoom.gui.service.ChatService.getInstance().getCurrentUsername();
            if (nodeId == null)
                nodeId = "unknown-user";

            if (ClientMenu.roomClient != null) {
                System.out.println("[Rooms] Joining Room " + roomName + " [" + roomId + "] as " + nodeId);

                // 1. Join via RPC (Persistence + Membership)
                // TODO: Get real PubKey
                var joinResp = ClientMenu.roomClient.joinRoom(roomId, nodeId, "dummy-pub-key");

                if (!joinResp.getSuccess()) {
                    System.err.println("Failed to join room: " + joinResp.getMessage());
                    // TODO: Show Error
                    return;
                }

                System.out.println("[Rooms] Starting DataFSM for " + roomName);

                // Sprint 7: Use Real Native WebRTC Manager
                com.saferoom.rooms.client.logic.RoomWebRTCManager webRTCManager = new com.saferoom.rooms.client.logic.RoomWebRTCManagerImpl();

                DataFSM fsm = new DataFSM(roomId, nodeId, ClientMenu.roomClient, webRTCManager);
                fsm.start();

                // Sprint 10: Store FSM in ActiveRoomSession for ServerController access
                ActiveRoomSession.getInstance().setActiveSession(roomId, roomName, fsm);
            }
        } catch (Exception e) {
            System.err.println("Failed to start Rooms v1 FSM: " + e.getMessage());
            e.printStackTrace();
        }

        // Navigate to ServerView
        if (MainController.getInstance() != null) {
            MainController.getInstance().loadServerView(roomName, getRoomIcon(roomCard), isPrivate, isCustom);
        } else {
            System.err.println("MainController instance is null. Cannot load ServerView.");
        }
    }

    private String getRoomIcon(VBox roomCard) {
        // Extract the icon from the room card to pass to the server view
        // Look for FontIcon in the room avatar
        for (Node node : roomCard.getChildren()) {
            if (node instanceof StackPane && node.getStyleClass().contains("room-avatar-large")) {
                StackPane avatar = (StackPane) node;
                for (Node avatarChild : avatar.getChildren()) {
                    if (avatarChild instanceof FontIcon) {
                        FontIcon icon = (FontIcon) avatarChild;
                        return icon.getIconLiteral();
                    }
                }
            }
        }
        return "fas-shield-alt"; // Default icon
    }

    private void setupCompactActionButtons(VBox roomCard) {
        // Find and setup action buttons in the room card
        for (Node node : roomCard.getChildren()) {
            if (node instanceof VBox && node.getStyleClass().contains("room-info-compact")) {
                VBox roomInfo = (VBox) node;
                for (Node infoChild : roomInfo.getChildren()) {
                    if (infoChild instanceof HBox && infoChild.getStyleClass().contains("quick-actions")) {
                        HBox actionsBox = (HBox) infoChild;
                        for (Node actionNode : actionsBox.getChildren()) {
                            if (actionNode instanceof Button) {
                                Button actionBtn = (Button) actionNode;
                                // Pass roomCard directly to avoid fragile parent traversal
                                setupActionButtonHandler(actionBtn, roomCard);
                            }
                        }
                    }
                }
            }
        }
    }

    private void setupActionButtonHandler(Button actionBtn, VBox roomCard) {
        actionBtn.setOnAction(event -> {
            String nodeId = com.saferoom.gui.service.ChatService.getInstance().getCurrentUsername();
            if (nodeId == null)
                nodeId = "unknown-user";

            RoomData data = getRoomData(roomCard);
            String roomId = (data != null && data.id != null) ? data.id : "mock-room-id";

            if (actionBtn.getStyleClass().contains("voice-quick")) {
                System.out.println("Voice action clicked for " + nodeId + " in room " + roomId);
                if (ClientMenu.roomClient != null) {
                    ClientMenu.roomClient.joinVoice(roomId, nodeId);
                }
            } else if (actionBtn.getStyleClass().contains("chat-quick")) {
                System.out.println("Chat action clicked");
                // Trigger navigation (which starts FSM)
                navigateToRoom(roomCard);
            }
        });
    }

    @FXML
    private void handleCreateRoom() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/view/CreateRoomDialog.fxml"));
            javafx.scene.Parent root = loader.load();

            CreateRoomDialogController controller = loader.getController();

            // Create a transparent overlay stage
            javafx.stage.Stage ownerStage = (javafx.stage.Stage) searchTextField.getScene().getWindow();
            javafx.stage.Stage dialogStage = new javafx.stage.Stage();
            dialogStage.initOwner(ownerStage);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            dialogStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);

            // Container for the dim effect and centering
            StackPane overlay = new StackPane();
            overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5);");

            // Limit dialog size to preferred size so it doesn't stretch
            if (root instanceof javafx.scene.layout.Region) {
                ((javafx.scene.layout.Region) root).setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                        javafx.scene.layout.Region.USE_PREF_SIZE);
            }

            overlay.getChildren().add(root);

            // Close if clicked outside
            overlay.setOnMouseClicked(event -> {
                if (event.getTarget() == overlay &&
                        event.getButton() == javafx.scene.input.MouseButton.PRIMARY &&
                        event.isStillSincePress()) {
                    dialogStage.close();
                }
            });

            root.setOnMouseClicked(event -> event.consume());

            javafx.scene.Scene scene = new javafx.scene.Scene(overlay);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            dialogStage.setScene(scene);

            // Bind size to owner size
            dialogStage.setX(ownerStage.getX());
            dialogStage.setY(ownerStage.getY());
            dialogStage.setWidth(ownerStage.getWidth());
            dialogStage.setHeight(ownerStage.getHeight());

            ownerStage.xProperty().addListener((obs, oldVal, newVal) -> dialogStage.setX(newVal.doubleValue()));
            ownerStage.yProperty().addListener((obs, oldVal, newVal) -> dialogStage.setY(newVal.doubleValue()));
            ownerStage.widthProperty().addListener((obs, oldVal, newVal) -> dialogStage.setWidth(newVal.doubleValue()));
            ownerStage.heightProperty()
                    .addListener((obs, oldVal, newVal) -> dialogStage.setHeight(newVal.doubleValue()));

            dialogStage.showAndWait();

            if (controller.isConfirmed()) {
                String roomName = controller.getResultName();
                boolean isPrivate = controller.isPrivate();
                javafx.scene.image.Image roomImage = controller.getResultImage();

                System.out.println("Creating new room: " + roomName + ", Private: " + isPrivate);

                // Call Server RPC
                if (ClientMenu.roomClient != null) {
                    try {
                        String ownerId = com.saferoom.gui.service.ChatService.getInstance().getCurrentUsername();
                        if (ownerId == null)
                            ownerId = "unknown";

                        var response = ClientMenu.roomClient.createRoom(roomName,
                                ownerId, isPrivate);
                        if (response.getSuccess()) {
                            System.out.println("Room created successfully: " + response.getMessage());
                            // Add the new room card to the UI
                            addRoomCard(response.getRoom().getRoomId(), roomName, isPrivate, roomImage);
                        } else {
                            System.err.println("Failed to create room: " + response.getMessage());
                            // TODO: Show error dialog
                        }
                    } catch (Exception e) {
                        System.err.println("RPC Error creating room: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    System.err.println("RoomClient is null, cannot create room.");
                }
            }

        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    private void addRoomCard(String roomId, String roomName, boolean isPrivate, javafx.scene.image.Image roomImage) {
        if (hubListContainer == null)
            return;

        VBox roomCard = new VBox();
        roomCard.getStyleClass().add("compact-room-card");

        // Avatar
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("room-avatar-large");

        if (roomImage != null) {
            javafx.scene.shape.Circle imageCircle = new javafx.scene.shape.Circle(24);
            imageCircle.setFill(new javafx.scene.paint.ImagePattern(roomImage));
            avatar.getChildren().add(imageCircle);
        } else {
            FontIcon icon = new FontIcon("fas-rocket");
            icon.getStyleClass().add("room-icon-large");
            avatar.getChildren().add(icon);
        }

        // Room Info
        VBox roomInfo = new VBox(4);
        roomInfo.getStyleClass().add("room-info-compact");
        roomInfo.setAlignment(javafx.geometry.Pos.CENTER);

        Label nameLabel = new Label(roomName);
        nameLabel.getStyleClass().add("room-name-compact");

        // Stats (Mock data for new room)
        HBox onlineStat = new HBox(4);
        onlineStat.setAlignment(javafx.geometry.Pos.CENTER);
        onlineStat.getChildren().addAll(new FontIcon("fas-circle"), new Label("1"));
        ((FontIcon) onlineStat.getChildren().get(0)).getStyleClass().add("online-indicator");
        ((Label) onlineStat.getChildren().get(1)).getStyleClass().add("stat-number");

        HBox totalStat = new HBox(4);
        totalStat.setAlignment(javafx.geometry.Pos.CENTER);
        totalStat.getChildren().addAll(new FontIcon("fas-users"), new Label("1"));
        ((FontIcon) totalStat.getChildren().get(0)).getStyleClass().add("total-indicator");
        ((Label) totalStat.getChildren().get(1)).getStyleClass().add("stat-number");

        HBox stats = new HBox(8);
        stats.getStyleClass().add("room-stats");
        stats.setAlignment(javafx.geometry.Pos.CENTER);
        stats.getChildren().addAll(onlineStat, totalStat);

        // Quick Actions
        HBox actions = new HBox(8);
        actions.getStyleClass().add("quick-actions");
        actions.setAlignment(javafx.geometry.Pos.CENTER);

        Button voiceBtn = new Button();
        voiceBtn.getStyleClass().addAll("quick-action-btn", "voice-quick");
        voiceBtn.setGraphic(new FontIcon("fas-microphone"));
        ((FontIcon) voiceBtn.getGraphic()).getStyleClass().add("quick-action-icon");

        Button chatBtn = new Button();
        chatBtn.getStyleClass().addAll("quick-action-btn", "chat-quick");
        chatBtn.setGraphic(new FontIcon("fas-comments"));
        ((FontIcon) chatBtn.getGraphic()).getStyleClass().add("quick-action-icon");

        actions.getChildren().addAll(voiceBtn, chatBtn);

        // Activity Indicators
        VBox activity = new VBox(2);
        activity.getStyleClass().add("activity-indicators");
        activity.setAlignment(javafx.geometry.Pos.CENTER);

        HBox voiceActivity = new HBox(4);
        voiceActivity.setAlignment(javafx.geometry.Pos.CENTER);
        voiceActivity.getChildren().addAll(new FontIcon("fas-volume-up"), new Label("0 in voice"));
        ((FontIcon) voiceActivity.getChildren().get(0)).getStyleClass().add("activity-icon");
        ((Label) voiceActivity.getChildren().get(1)).getStyleClass().add("activity-text");

        HBox msgActivity = new HBox(4);
        msgActivity.setAlignment(javafx.geometry.Pos.CENTER);
        msgActivity.getChildren().addAll(new FontIcon("fas-envelope"), new Label("No new messages"));
        ((FontIcon) msgActivity.getChildren().get(0)).getStyleClass().add("activity-icon");
        ((Label) msgActivity.getChildren().get(1)).getStyleClass().add("activity-text");

        activity.getChildren().addAll(voiceActivity, msgActivity);

        roomInfo.getChildren().addAll(nameLabel, stats, actions, activity);
        roomCard.getChildren().addAll(avatar, roomInfo);

        // Add click handlers
        roomCard.setOnMouseClicked(event -> {
            if (!isActionButtonClick(event.getTarget())) {
                navigateToRoom(roomCard);
            }
        });
        setupActionButtonHandler(voiceBtn, roomCard);
        setupActionButtonHandler(chatBtn, roomCard);

        // Store room data
        roomCard.setUserData(new RoomData(roomId, roomName, isPrivate, true));

        hubListContainer.getChildren().add(0, roomCard); // Add to top
    }
}