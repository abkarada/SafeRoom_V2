package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.gui.MainApp;
import com.saferoom.gui.controller.strategy.AdminRoleStrategy;
import com.saferoom.gui.controller.strategy.MeetingRoleStrategy;
import com.saferoom.gui.controller.strategy.UserRoleStrategy;
import com.saferoom.gui.model.Meeting;
import com.saferoom.gui.model.Participant;
import com.saferoom.gui.model.UserRole;
import com.saferoom.gui.utils.WindowStateManager;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class MeetingPanelController {

    // FXML Değişkenleri
    @FXML private AnchorPane centerContentAnchorPane;
    @FXML private StackPane contentStackPane;
    @FXML private VBox rightPanelVBox;
    @FXML private ToggleButton participantsToggle;
    @FXML private ToggleButton chatToggle;
    @FXML private ScrollPane participantsScrollPane;
    @FXML private BorderPane meetingChatView;
    @FXML private ChatViewController meetingChatViewController;
    @FXML private GridPane videoGrid;
    @FXML private BorderPane speakerViewPane;
    @FXML private Label meetingNameLabel;
    @FXML private Label timerLabel;
    @FXML private JFXButton participantsButtonTop;
    @FXML private JFXButton gridViewButton;
    @FXML private JFXButton speakerViewButton;
    @FXML private VBox participantsListVBox;
    @FXML private JFXButton micButton;
    @FXML private JFXButton cameraButton;
    @FXML private JFXButton chatButtonBottom;
    @FXML private JFXButton screenShareButton;
    @FXML private JFXButton recordButton;
    @FXML private JFXButton leaveButton;

    // Window state manager for dragging functionality
    private WindowStateManager windowStateManager = new WindowStateManager();
    private MainController mainController;

    // Sınıf Değişkenleri
    private Meeting currentMeeting;
    private Participant currentUser;
    private MeetingRoleStrategy roleStrategy;
    private boolean isPanelOpen = false;
    private final Duration animationDuration = Duration.millis(350);
    private final DoubleProperty contentRightAnchorProperty = new SimpleDoubleProperty(0.0);

    @FXML
    public void initialize() {
        // Pencere sürükleme işlevini etkinleştir
        windowStateManager.setupBasicWindowDrag(centerContentAnchorPane);
        
        contentRightAnchorProperty.addListener((obs, oldValue, newValue) -> {
            AnchorPane.setRightAnchor(contentStackPane, newValue.doubleValue());
            AnchorPane.setRightAnchor(rightPanelVBox, newValue.doubleValue() - rightPanelVBox.getPrefWidth());
        });
        AnchorPane.setRightAnchor(contentStackPane, 0.0);
        AnchorPane.setRightAnchor(rightPanelVBox, -rightPanelVBox.getPrefWidth());
        rightPanelVBox.setVisible(false);
        rightPanelVBox.setManaged(false);
        micButton.setOnAction(e -> toggleMic());
        cameraButton.setOnAction(e -> toggleCamera());
        gridViewButton.setOnAction(e -> showGridView());
        speakerViewButton.setOnAction(e -> showSpeakerView());
        participantsToggle.setOnAction(e -> showParticipantsView());
        chatToggle.setOnAction(e -> showChatView());
    }

    public void initData(Meeting meeting, UserRole userRole) {
        this.currentMeeting = meeting;
        this.meetingNameLabel.setText(meeting.getMeetingName());

        if (userRole == UserRole.ADMIN) {
            this.roleStrategy = new AdminRoleStrategy();
            this.currentUser = new Participant("Admin User (You)", UserRole.ADMIN, true, false);
        } else {
            this.roleStrategy = new UserRoleStrategy();
            this.currentUser = new Participant("Standard User (You)", UserRole.USER, true, false);
        }

        currentMeeting.getParticipants().add(0, this.currentUser);
        meeting.addDummyParticipants();
        updateParticipantsList();
        showGridView();

        if (meetingChatViewController != null) {
            meetingChatViewController.setHeader("Toplantı Sohbeti", "Online", "G", true);
            meetingChatViewController.setWidthConstraint(rightPanelVBox.getPrefWidth());
            meetingChatViewController.setHeaderVisible(false);
            // ❌ P2P REMOVED: meetingChatViewController.initChannel(currentMeeting.getMeetingId());
        }
        updateMicButtonState();
        updateCameraButtonState();
    }

    /**
     * Ana controller referansını ayarlar (geri dönüş için)
     */
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        // Meeting panel açıldığında da sidebar'ı gizle
        if (mainController != null) {
            mainController.hideSidebarForFullScreen();
        }
    }

    private Node createParticipantListItem(Participant participant) {
        HBox itemBox = new HBox(12);
        itemBox.setAlignment(Pos.CENTER_LEFT);
        itemBox.setPadding(new Insets(8, 16, 8, 16));
        itemBox.getStyleClass().add("participant-list-item");

        Label avatar = new Label(participant.getName().substring(0, 1));
        avatar.getStyleClass().add("participant-avatar");
        VBox nameBox = new VBox(-2);
        Label nameLabel = new Label(participant.getName());
        nameLabel.getStyleClass().add("participant-name");
        Label roleLabel = new Label(participant.getRole() == UserRole.ADMIN ? "Admin" : "Member");
        roleLabel.getStyleClass().add("participant-role");
        nameBox.getChildren().addAll(nameLabel, roleLabel);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        FontIcon micIcon = new FontIcon(participant.isMuted() ? "fas-microphone-slash" : "fas-microphone");
        micIcon.getStyleClass().add("participant-list-icon");
        if (participant.isMuted()) micIcon.getStyleClass().add("icon-danger");
        FontIcon camIcon = new FontIcon(participant.isCameraOn() ? "fas-video" : "fas-video-slash");
        camIcon.getStyleClass().add("participant-list-icon");
        if (!participant.isCameraOn()) camIcon.getStyleClass().add("icon-danger");

        itemBox.getChildren().addAll(avatar, nameBox, spacer, micIcon, camIcon);

        List<MenuItem> menuItems = roleStrategy.createParticipantMenuItems(currentUser, participant, this::updateParticipantsList);

        if (menuItems.isEmpty()) {
            return itemBox;
        }

        // ## DÜZELTME: Her satır için kendi ContextMenu nesnesini oluşturuyoruz ##
        final ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(menuItems);
        contextMenu.getStyleClass().add("context-menu");

        JFXButton optionsButton = new JFXButton();
        FontIcon icon = new FontIcon("fas-ellipsis-v");
        icon.getStyleClass().add("participant-action-icon");
        optionsButton.setGraphic(icon);
        optionsButton.getStyleClass().add("participant-action-button");

        // ## DÜZELTME: Kapanma mantığı eklendi ##
        optionsButton.setOnAction(event -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            } else {
                // Bu metot, pencere taşmalarına karşı daha güvenlidir.
                contextMenu.show(optionsButton, Side.BOTTOM, -120, 5);
            }
        });

        // ## DÜZELTME: Sağ tık için en güvenli gösterme metodu kullanıldı ##
        itemBox.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                if (contextMenu.isShowing()) {
                    contextMenu.hide();
                } else {
                    // Bu metot, JavaFX'in pencere sınırlarını otomatik olarak algılaması için en iyisidir.
                    contextMenu.show(itemBox.getScene().getWindow(), event.getScreenX(), event.getScreenY());
                }
            }
        });

        itemBox.getChildren().add(optionsButton);
        return itemBox;
    }

    public void updateParticipantsList() {
        participantsListVBox.getChildren().clear();
        for (Participant p : currentMeeting.getParticipants()) {
            participantsListVBox.getChildren().add(createParticipantListItem(p));
        }
    }

    @FXML
    private void toggleMic() {
        if (currentUser == null) return;
        boolean newMutedState = !currentUser.isMuted();
        currentUser.setMuted(newMutedState);
        updateMicButtonState();
    }

    @FXML
    private void toggleCamera() {
        if (currentUser == null) return;
        boolean newCameraState = !currentUser.isCameraOn();
        currentUser.setCameraOn(newCameraState);
        updateCameraButtonState();
    }

    private void updateMicButtonState() {
        if (currentUser.isMuted()) {
            micButton.setText("Unmute");
            micButton.getStyleClass().add("active");
            if(micButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) micButton.getGraphic()).setIconLiteral("fas-microphone-slash");
            }
        } else {
            micButton.setText("Mute");
            micButton.getStyleClass().remove("active");
            if(micButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) micButton.getGraphic()).setIconLiteral("fas-microphone");
            }
        }
    }

    private void updateCameraButtonState() {
        if (!currentUser.isCameraOn()) {
            cameraButton.setText("Start Video");
            cameraButton.getStyleClass().add("active");
            if(cameraButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) cameraButton.getGraphic()).setIconLiteral("fas-video-slash");
            }
        } else {
            cameraButton.setText("Stop Video");
            cameraButton.getStyleClass().remove("active");
            if(cameraButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) cameraButton.getGraphic()).setIconLiteral("fas-video");
            }
        }
    }

    private void toggleRightPanel(Runnable onAnimationFinished) {
        final boolean closing = isPanelOpen;

        if (!closing) {
            rightPanelVBox.setManaged(true);
            rightPanelVBox.setVisible(true);
        }

        Timeline timeline = new Timeline();
        double targetAnchorValue = closing ? 0.0 : rightPanelVBox.getPrefWidth();
        KeyValue keyValue = new KeyValue(contentRightAnchorProperty, targetAnchorValue);
        KeyFrame keyFrame = new KeyFrame(animationDuration, keyValue);
        timeline.getKeyFrames().add(keyFrame);

        timeline.setOnFinished(e -> {
            if (closing) {
                rightPanelVBox.setVisible(false);
                rightPanelVBox.setManaged(false);
            }
            if (onAnimationFinished != null) {
                onAnimationFinished.run();
            }
        });

        timeline.play();
        isPanelOpen = !closing;
    }

    @FXML
    private void toggleRightPanelAndGoToParticipants() {
        Runnable switchToParticipants = () -> {
            participantsToggle.setSelected(true);
            showParticipantsView();
        };

        if (isPanelOpen) {
            toggleRightPanel(switchToParticipants);
        } else {
            switchToParticipants.run();
            toggleRightPanel(null);
        }
    }

    @FXML
    private void toggleRightPanelAndGoToChat() {
        if (isPanelOpen && chatToggle.isSelected()) {
            toggleRightPanel(null);
        } else {
            chatToggle.setSelected(true);
            showChatView();
            if (!isPanelOpen) {
                toggleRightPanel(null);
            }
        }
    }

    private void showParticipantsView() {
        participantsScrollPane.setVisible(true);
        if (meetingChatView != null) {
            meetingChatView.setVisible(false);
        }
    }

    private void showChatView() {
        participantsScrollPane.setVisible(false);
        if (meetingChatView != null) {
            meetingChatView.setVisible(true);
        }
    }

    private void showGridView() {
        videoGrid.setVisible(true);
        speakerViewPane.setVisible(false);
        updateVideoGrid();
        setActiveLayoutButton(gridViewButton);
    }

    private void showSpeakerView() {
        videoGrid.setVisible(false);
        speakerViewPane.setVisible(true);
        updateSpeakerView();
        setActiveLayoutButton(speakerViewButton);
    }

    private void setActiveLayoutButton(JFXButton activeButton) {
        gridViewButton.getStyleClass().remove("active");
        speakerViewButton.getStyleClass().remove("active");
        activeButton.getStyleClass().add("active");
    }

    private void updateSpeakerView() {
        speakerViewPane.getChildren().clear();
        if (currentMeeting.getParticipants().isEmpty()) return;
        Participant speaker = currentMeeting.getParticipants().get(0);
        StackPane speakerTile = createVideoTile(speaker);
        speakerViewPane.setCenter(speakerTile);
        VBox otherParticipantsVBox = new VBox(10);
        otherParticipantsVBox.setPadding(new Insets(0, 0, 0, 16));
        otherParticipantsVBox.setAlignment(Pos.TOP_CENTER);
        for (int i = 1; i < currentMeeting.getParticipants().size(); i++) {
            Participant p = currentMeeting.getParticipants().get(i);
            StackPane smallTile = createVideoTile(p);
            smallTile.setPrefSize(160, 90);
            smallTile.setMaxSize(160, 90);
            otherParticipantsVBox.getChildren().add(smallTile);
        }
        ScrollPane scrollPane = new ScrollPane(otherParticipantsVBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("sidebar-scroll-pane");
        speakerViewPane.setRight(scrollPane);
    }

    private void updateVideoGrid() {
        videoGrid.getChildren().clear();
        videoGrid.getColumnConstraints().clear();
        videoGrid.getRowConstraints().clear();
        int numParticipants = currentMeeting.getParticipants().size();
        if (numParticipants == 0) return;
        int numColumns = (int) Math.ceil(Math.sqrt(numParticipants));
        int numRows = (int) Math.ceil((double) numParticipants / numColumns);
        for (int i = 0; i < numColumns; i++) {
            ColumnConstraints colConst = new ColumnConstraints();
            colConst.setPercentWidth(100.0 / numColumns);
            videoGrid.getColumnConstraints().add(colConst);
        }
        for (int i = 0; i < numRows; i++) {
            RowConstraints rowConst = new RowConstraints();
            rowConst.setPercentHeight(100.0 / numRows);
            videoGrid.getRowConstraints().add(rowConst);
        }
        int col = 0;
        int row = 0;
        for (Participant p : currentMeeting.getParticipants()) {
            videoGrid.add(createVideoTile(p), col, row);
            col++;
            if (col >= numColumns) {
                col = 0;
                row++;
            }
        }
    }

    private StackPane createVideoTile(Participant participant) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("video-tile");
        if (!participant.isCameraOn()) {
            Label avatarLabel = new Label(participant.getName().substring(0, 1));
            avatarLabel.getStyleClass().add("video-tile-avatar-label");
            tile.getChildren().add(avatarLabel);
        }
        HBox nameTag = new HBox(6);
        nameTag.setAlignment(Pos.CENTER_LEFT);
        nameTag.getStyleClass().add("video-tile-nametag");
        FontIcon micIcon = new FontIcon(participant.isMuted() ? "fas-microphone-slash" : "fas-microphone");
        micIcon.getStyleClass().add("video-tile-mic-icon");
        if (participant.isMuted()) micIcon.getStyleClass().add("icon-danger");
        Label nameLabel = new Label(participant.getName());
        nameLabel.getStyleClass().add("video-tile-name-label");
        nameTag.getChildren().addAll(micIcon, nameLabel);
        StackPane.setAlignment(nameTag, Pos.BOTTOM_LEFT);
        StackPane.setMargin(nameTag, new Insets(10));
        tile.getChildren().add(nameTag);
        return tile;
    }

    @FXML
    private void leaveMeeting() {
        // Ana pencereye geri dön (content değiştirme yaklaşımı)
        if (mainController != null) {
            mainController.returnToMainView();
        }
    }
}