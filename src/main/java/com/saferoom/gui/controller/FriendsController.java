package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.client.ClientMenu;
import com.saferoom.gui.model.Friend;
import com.saferoom.gui.utils.UserSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class FriendsController {

    @FXML private ListView<Friend> friendsListView;
    @FXML private Label listHeaderLabel;
    @FXML private TextField searchField;
    @FXML private ListView<Map<String, Object>> searchResultsList;
    @FXML private JFXButton onlineFilterButton;
    @FXML private JFXButton allFilterButton;
    @FXML private JFXButton pendingFilterButton;
    @FXML private JFXButton blockedFilterButton;

    // Stat labels
    @FXML private Label onlineCountLabel;
    @FXML private Label totalCountLabel;
    @FXML private Label pendingCountLabel;

    // Data sources
    private final ObservableList<Friend> allFriendsData = FXCollections.observableArrayList();
    private final ObservableList<Friend> pendingFriendsData = FXCollections.observableArrayList();

    // Filtered list for performance
    private FilteredList<Friend> filteredFriends;

    // Current filter state
    private FilterMode currentFilter = FilterMode.ALL;
    private boolean isPendingMode = false;

    private Timeline searchDebouncer;
    private Timeline friendsRefresher;
    private static FriendsController currentInstance;

    public FriendsController() {
        // Stop previous instance's timer before replacing
        if (currentInstance != null) {
            currentInstance.stopAutoRefresh();
        }
        currentInstance = this;
    }

    @FXML
    public void initialize() {
        setupListView();
        setupSearchFunctionality();
        setupFilterButtons();
        setupAutoRefresh();

        // Load data
        loadFriendsData();
        
        // Cleanup when scene is removed (navigating away)
        if (friendsListView != null) {
            friendsListView.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene == null) {
                    stopAutoRefresh();
                }
            });
        }
    }



    /**
     * PERFORMANCE: Setup ListView with virtualization
     */
    private void setupListView() {
        // Create filtered list from all friends data
        filteredFriends = new FilteredList<>(allFriendsData, friend -> true);

        // Bind filtered list to ListView
        friendsListView.setItems(filteredFriends);

        // Set cell factory for friend items
        friendsListView.setCellFactory(param -> new FriendCellOptimized());

        // Placeholder for empty state
        friendsListView.setPlaceholder(createEmptyStatePlaceholder());
    }



    /**
     * PERFORMANCE: Load data once and filter in memory
     */
    private void loadFriendsData() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();

                com.saferoom.grpc.SafeRoomProto.FriendsListResponse friendsResponse =
                        ClientMenu.getFriendsList(currentUser);

                com.saferoom.grpc.SafeRoomProto.PendingRequestsResponse pendingResponse =
                        ClientMenu.getPendingFriendRequests(currentUser);

                if (friendsResponse.getSuccess()) {
                    allFriendsData.clear();
                    for (com.saferoom.grpc.SafeRoomProto.FriendInfo friendInfo : friendsResponse.getFriendsList()) {
                        String status = friendInfo.getIsOnline() ? "Online" : "Offline";
                        String lastSeen = friendInfo.getLastSeen().isEmpty() ? "Never" : friendInfo.getLastSeen();
                        String activity = friendInfo.getIsOnline() ? "Active now" : "Last seen " + lastSeen;

                        Friend friend = new Friend(
                                friendInfo.getUsername(),
                                status,
                                friendInfo.getIsOnline() ? "🟢" : "🔴",
                                activity
                        );
                        allFriendsData.add(friend);
                    }
                }

                if (pendingResponse.getSuccess()) {
                    pendingFriendsData.clear();
                    for (com.saferoom.grpc.SafeRoomProto.FriendRequestInfo requestInfo : pendingResponse.getRequestsList()) {
                        Friend pendingFriend = new Friend(
                                requestInfo.getSender(),
                                "Friend Request",
                                "Pending",
                                "Sent: " + requestInfo.getSentAt(),
                                requestInfo.getRequestId()
                        );
                        pendingFriendsData.add(pendingFriend);
                    }
                }

                return true;
            } catch (Exception e) {
                System.err.println("❌ Error loading friends data: " + e.getMessage());
                return false;
            }
        }).thenAcceptAsync(success -> {
            Platform.runLater(() -> {
                if (success) {
                    updateStatsLabels();
                    applyCurrentFilter(); // Re-apply current filter
                    System.out.println("✅ Friends data loaded successfully");
                }
            });
        });
    }

    /**
     * Update statistics labels
     */
    private void updateStatsLabels() {
        long onlineCount = allFriendsData.stream().filter(Friend::isOnline).count();

        if (onlineCountLabel != null) onlineCountLabel.setText(String.valueOf(onlineCount));
        if (totalCountLabel != null) totalCountLabel.setText(String.valueOf(allFriendsData.size()));
        if (pendingCountLabel != null) pendingCountLabel.setText(String.valueOf(pendingFriendsData.size()));
    }

    /**
     * PERFORMANCE: Setup filter buttons with instant switching
     */
    private void setupFilterButtons() {
        onlineFilterButton.setOnAction(e -> {
            setActiveFilter(onlineFilterButton);
            switchToFilter(FilterMode.ONLINE);
        });

        allFilterButton.setOnAction(e -> {
            setActiveFilter(allFilterButton);
            switchToFilter(FilterMode.ALL);
        });

        pendingFilterButton.setOnAction(e -> {
            setActiveFilter(pendingFilterButton);
            switchToPendingMode();
        });

        blockedFilterButton.setOnAction(e -> {
            setActiveFilter(blockedFilterButton);
            switchToFilter(FilterMode.BLOCKED);
        });
    }

    /**
     * PERFORMANCE: Instant filter switching without recreation
     */
    private void switchToFilter(FilterMode mode) {
        currentFilter = mode;
        isPendingMode = false;

        // Switch back to friends data
        friendsListView.setItems(filteredFriends);
        friendsListView.setCellFactory(param -> new FriendCellOptimized());

        // Apply filter predicate
        applyCurrentFilter();

        // Update header
        updateListHeader();
    }

    /**
     * Switch to pending requests mode
     */
    private void switchToPendingMode() {
        isPendingMode = true;
        currentFilter = FilterMode.PENDING;

        // Switch to pending data
        friendsListView.setItems(pendingFriendsData);
        friendsListView.setCellFactory(param -> new PendingRequestCellOptimized());

        // Update header
        listHeaderLabel.setText("PENDING REQUESTS — " + pendingFriendsData.size());
    }

    /**
     * PERFORMANCE: Apply filter without recreating cells
     */
    private void applyCurrentFilter() {
        if (isPendingMode) return; // Don't filter in pending mode

        Predicate<Friend> filterPredicate;

        switch (currentFilter) {
            case ONLINE:
                filterPredicate = Friend::isOnline;
                break;
            case BLOCKED:
                filterPredicate = friend -> false; // No blocked users for now
                break;
            case ALL:
            default:
                filterPredicate = friend -> true;
                break;
        }

        filteredFriends.setPredicate(filterPredicate);
        updateListHeader();
    }

    /**
     * Update list header with count
     */
    private void updateListHeader() {
        String headerText;
        int count = filteredFriends.size();

        switch (currentFilter) {
            case ONLINE:
                headerText = "ONLINE — " + count;
                break;
            case BLOCKED:
                headerText = "BLOCKED — 0";
                break;
            case ALL:
            default:
                headerText = "ALL FRIENDS — " + count;
                break;
        }

        listHeaderLabel.setText(headerText);
    }

    /**
     * Set active filter button
     */
    private void setActiveFilter(JFXButton activeButton) {
        onlineFilterButton.getStyleClass().remove("active-filter-pill");
        allFilterButton.getStyleClass().remove("active-filter-pill");
        pendingFilterButton.getStyleClass().remove("active-filter-pill");
        blockedFilterButton.getStyleClass().remove("active-filter-pill");

        activeButton.getStyleClass().add("active-filter-pill");
    }

    /**
     * Create empty state placeholder
     */
    private VBox createEmptyStatePlaceholder() {
        VBox emptyState = new VBox(16);
        emptyState.getStyleClass().add("friends-empty-state");
        emptyState.setAlignment(Pos.CENTER);
        VBox.setVgrow(emptyState, Priority.ALWAYS);


        StackPane iconContainer = new StackPane();
        iconContainer.setAlignment(Pos.CENTER);



        FontIcon backgroundIcon = new FontIcon("fas-user-friends");
        backgroundIcon.getStyleClass().add("empty-icon-background");


        FontIcon foregroundIcon = new FontIcon("fas-user-friends");
        foregroundIcon.getStyleClass().add("empty-icon-foreground");


        iconContainer.getChildren().addAll(backgroundIcon, foregroundIcon);


        Label titleLabel = new Label("No friends yet");
        titleLabel.getStyleClass().add("empty-title-modern");

        Label subtitleLabel = new Label("Add friends to start connecting");
        subtitleLabel.getStyleClass().add("empty-subtitle-modern");


        emptyState.getChildren().addAll(iconContainer, titleLabel, subtitleLabel);

        return emptyState;
    }

    // ========== OPTIMIZED CELL FACTORIES ==========

    /**
     * PERFORMANCE: Optimized friend cell with reuse
     */
    static class FriendCellOptimized extends ListCell<Friend> {
        private final HBox card = new HBox(14);
        private final StackPane avatarStack = new StackPane();
        private final Label avatar = new Label();
        private final Label onlineDot = new Label();
        private final VBox infoBox = new VBox(4);
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Pane spacer = new Pane();
        private final HBox actionButtons = new HBox(8);
        private final HBox messageBtn = new HBox();
        private final HBox callBtn = new HBox();

        private Friend currentFriend; // Cache current friend

        public FriendCellOptimized() {
            super();

            // Setup UI once
            avatar.getStyleClass().add("friend-avatar-modern");
            onlineDot.getStyleClass().add("online-dot-modern");
            StackPane.setAlignment(onlineDot, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(onlineDot, new Insets(0, -2, -2, 0));
            avatarStack.getChildren().addAll(avatar, onlineDot);

            nameLabel.getStyleClass().add("friend-name-modern");
            statusLabel.getStyleClass().add("friend-status-modern");
            infoBox.getChildren().addAll(nameLabel, statusLabel);

            // Message button
            FontIcon messageIcon = new FontIcon("fas-comment-dots");
            messageIcon.getStyleClass().add("action-icon-modern");
            messageBtn.getChildren().add(messageIcon);
            messageBtn.getStyleClass().add("friend-action-modern");
            messageBtn.setAlignment(Pos.CENTER);

            // Call button
            FontIcon callIcon = new FontIcon("fas-phone");
            callIcon.getStyleClass().add("action-icon-modern");
            callBtn.getChildren().add(callIcon);
            callBtn.getStyleClass().add("friend-action-modern");
            callBtn.setAlignment(Pos.CENTER);

            // Click handlers
            messageBtn.setOnMouseClicked(e -> {
                if (currentFriend != null) {
                    openMessagesWithUser(currentFriend.getName());
                }
            });

            actionButtons.getChildren().addAll(messageBtn, callBtn);
            actionButtons.setAlignment(Pos.CENTER);

            HBox.setHgrow(spacer, Priority.ALWAYS);
            card.getChildren().addAll(avatarStack, infoBox, spacer, actionButtons);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("friend-card-modern");

            // Spacing between cards
            setStyle("-fx-padding: 0 0 8 0;");
        }

        @Override
        protected void updateItem(Friend friend, boolean empty) {
            super.updateItem(friend, empty);

            if (empty || friend == null) {
                setGraphic(null);
                currentFriend = null;
            } else {
                currentFriend = friend;

                // Update content
                avatar.setText(friend.getAvatarChar());
                nameLabel.setText(friend.getName());
                statusLabel.setText(friend.getActivity());

                // Update online indicator
                if (friend.isOnline()) {
                    onlineDot.getStyleClass().setAll("online-dot-modern");
                    onlineDot.setVisible(true);
                } else {
                    onlineDot.getStyleClass().setAll("offline-dot-modern");
                    onlineDot.setVisible(true);
                }

                setGraphic(card);
            }
        }
    }

    /**
     * PERFORMANCE: Optimized pending request cell
     */
    static class PendingRequestCellOptimized extends ListCell<Friend> {
        private final HBox card = new HBox(14);
        private final Label avatar = new Label();
        private final VBox infoBox = new VBox(4);
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Pane spacer = new Pane();
        private final HBox actionButtons = new HBox(8);
        private final JFXButton acceptButton = new JFXButton("Accept");
        private final JFXButton rejectButton = new JFXButton("Reject");

        private Friend currentFriend;

        public PendingRequestCellOptimized() {
            super();

            avatar.getStyleClass().add("friend-avatar-modern");
            nameLabel.getStyleClass().add("friend-name-modern");
            statusLabel.getStyleClass().add("friend-status-modern");

            acceptButton.getStyleClass().add("pending-accept-btn-modern");
            rejectButton.getStyleClass().add("pending-reject-btn-modern");

            infoBox.getChildren().addAll(nameLabel, statusLabel);
            actionButtons.getChildren().addAll(acceptButton, rejectButton);
            actionButtons.setAlignment(Pos.CENTER);

            HBox.setHgrow(spacer, Priority.ALWAYS);
            card.getChildren().addAll(avatar, infoBox, spacer, actionButtons);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("friend-card-modern");

            setStyle("-fx-padding: 0 0 8 0;");
        }

        @Override
        protected void updateItem(Friend friend, boolean empty) {
            super.updateItem(friend, empty);

            if (empty || friend == null) {
                setGraphic(null);
                currentFriend = null;
            } else {
                currentFriend = friend;

                avatar.setText(friend.getAvatarChar());
                nameLabel.setText(friend.getName());
                statusLabel.setText(friend.getActivity());

                // Reset button states
                acceptButton.setDisable(false);
                rejectButton.setDisable(false);

                int requestId = friend.getRequestId();
                acceptButton.setOnAction(e -> {
                    acceptButton.setDisable(true);
                    rejectButton.setDisable(true);
                    acceptFriendRequest(requestId, friend.getName());
                });

                rejectButton.setOnAction(e -> {
                    acceptButton.setDisable(true);
                    rejectButton.setDisable(true);
                    rejectFriendRequest(requestId, friend.getName());
                });

                setGraphic(card);
            }
        }
    }

    // ========== SEARCH FUNCTIONALITY ==========

    private void setupSearchFunctionality() {
        searchDebouncer = new Timeline(new KeyFrame(Duration.millis(500), e -> performSearch()));

        searchField.textProperty().addListener((obs, oldText, newText) -> {
            searchDebouncer.stop();
            if (newText != null && newText.trim().length() >= 2) {
                searchDebouncer.play();
                searchResultsList.setVisible(true);
            } else {
                searchResultsList.getItems().clear();
                searchResultsList.setVisible(false);
            }
        });

        searchResultsList.setCellFactory(listView -> new SearchResultCell());
        searchResultsList.setFixedCellSize(55.0);

        searchField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                Timeline hideDelay = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                    if (!searchResultsList.isFocused()) {
                        searchResultsList.setVisible(false);
                    }
                }));
                hideDelay.play();
            }
        });
    }

    private void performSearch() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.length() < 2) return;

        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                return ClientMenu.searchUsers(searchTerm, currentUser);
            } catch (Exception e) {
                System.err.println("❌ Search error: " + e.getMessage());
                return Collections.<Map<String, Object>>emptyList();
            }
        }).thenAcceptAsync(results -> {
            Platform.runLater(() -> {
                searchResultsList.getItems().clear();
                searchResultsList.getItems().addAll(results);
            });
        });
    }

    static class SearchResultCell extends ListCell<Map<String, Object>> {
        private final HBox hbox = new HBox(12);
        private final Label avatar = new Label();
        private final VBox userInfo = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label emailLabel = new Label();
        private final Pane spacer = new Pane();
        private final JFXButton addButton = new JFXButton("Add");

        public SearchResultCell() {
            super();

            avatar.getStyleClass().add("friend-avatar-modern");
            avatar.setMinSize(32, 32);
            avatar.setMaxSize(32, 32);
            avatar.setAlignment(Pos.CENTER);

            nameLabel.getStyleClass().add("search-result-name");
            emailLabel.getStyleClass().add("search-result-email");

            addButton.getStyleClass().add("search-add-button");

            userInfo.getChildren().addAll(nameLabel, emailLabel);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            hbox.getChildren().addAll(avatar, userInfo, spacer, addButton);
            hbox.setAlignment(Pos.CENTER_LEFT);
            hbox.setPadding(new Insets(8));
        }

        @Override
        protected void updateItem(Map<String, Object> user, boolean empty) {
            super.updateItem(user, empty);

            if (empty || user == null) {
                setGraphic(null);
                setText(null);
            } else {
                String username = (String) user.get("username");
                String email = (String) user.get("email");
                Boolean isFriend = (Boolean) user.get("is_friend");
                Boolean hasPending = (Boolean) user.get("has_pending_request");

                nameLabel.setText(username);
                emailLabel.setText(email);
                avatar.setText(username.substring(0, 1).toUpperCase());

                if (isFriend != null && isFriend) {
                    addButton.setText("Friends");
                    addButton.setDisable(true);
                } else if (hasPending != null && hasPending) {
                    addButton.setText("Pending");
                    addButton.setDisable(true);
                } else {
                    addButton.setText("Add");
                    addButton.setDisable(false);
                    addButton.setOnAction(e -> sendFriendRequest(username));
                }

                setGraphic(hbox);
            }
        }
    }

    // ========== BACKEND ACTIONS ==========

    private static void sendFriendRequest(String username) {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                return ClientMenu.sendFriendRequest(currentUser, username);
            } catch (Exception e) {
                System.err.println("❌ Error sending friend request: " + e.getMessage());
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getSuccess()) {
                    System.out.println("✅ Friend request sent!");
                }
            });
        });
    }

    private static void acceptFriendRequest(int requestId, String senderUsername) {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                return ClientMenu.acceptFriendRequest(requestId, currentUser);
            } catch (Exception e) {
                System.err.println("❌ Error accepting friend request: " + e.getMessage());
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getCode() == 0) {
                    System.out.println("✅ Friend request accepted!");
                    FriendsController instance = getCurrentInstance();
                    if (instance != null) {
                        instance.loadFriendsData();
                    }
                }
            });
        });
    }

    private static void rejectFriendRequest(int requestId, String senderUsername) {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                return ClientMenu.rejectFriendRequest(requestId, currentUser);
            } catch (Exception e) {
                System.err.println("❌ Error rejecting friend request: " + e.getMessage());
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getCode() == 0) {
                    System.out.println("✅ Friend request rejected!");
                    FriendsController instance = getCurrentInstance();
                    if (instance != null) {
                        instance.loadFriendsData();
                    }
                }
            });
        });
    }

    private static void openMessagesWithUser(String username) {
        try {
            MainController mainController = MainController.getInstance();
            if (mainController != null) {
                mainController.handleMessages();
                MessagesController.openChatWithUser(username);
            }
        } catch (Exception e) {
            System.err.println("Error opening messages: " + e.getMessage());
        }
    }

    /**
     * Setup auto-refresh for friends list
     * OPTIMIZED: 
     * - Stops existing timer before creating new one (prevents memory leak)
     * - Increased interval to 60 seconds (was 30)
     * - Only refreshes if view is visible
     */
    private void setupAutoRefresh() {
        // CRITICAL: Stop existing timer to prevent multiple timers running
        if (friendsRefresher != null) {
            friendsRefresher.stop();
            friendsRefresher = null;
        }
        
        // Use 60 seconds interval (reduced CPU overhead)
        friendsRefresher = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            // Only refresh if ListView is visible and in scene
            if (friendsListView != null && friendsListView.isVisible() && 
                friendsListView.getScene() != null) {
                System.out.println("🔄 Auto-refreshing friends list...");
                loadFriendsData();
            }
        }));
        friendsRefresher.setCycleCount(Timeline.INDEFINITE);
        friendsRefresher.play();
    }
    
    /**
     * Stop auto-refresh when controller is no longer needed
     * Call this when navigating away from Friends view
     */
    public void stopAutoRefresh() {
        if (friendsRefresher != null) {
            friendsRefresher.stop();
            friendsRefresher = null;
            System.out.println("⏹️ Friends auto-refresh stopped");
        }
    }

    private static FriendsController getCurrentInstance() {
        return currentInstance;
    }

    // Filter modes
    private enum FilterMode {
        ALL, ONLINE, PENDING, BLOCKED
    }
}