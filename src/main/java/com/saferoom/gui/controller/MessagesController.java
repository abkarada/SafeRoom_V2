package com.saferoom.gui.controller;

import com.saferoom.gui.view.cell.ContactCell;
import com.saferoom.gui.service.ContactService;
import com.saferoom.client.ClientMenu;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class MessagesController {

    @FXML private SplitPane mainSplitPane;
    @FXML private ListView<Contact> contactListView;

    @FXML private ChatViewController chatViewController;
    
    // Singleton instance için
    private static MessagesController instance;
    
    // Contact selection listener - infinite loop prevention için
    private ChangeListener<Contact> contactSelectionListener;
    
    // P2P connection status tracking
    private final Map<String, String> connectionStatus = new ConcurrentHashMap<>();
    
    // Contact service for persistent storage
    private final ContactService contactService = ContactService.getInstance();

    @FXML
    public void initialize() {
        instance = this;
        
        mainSplitPane.setDividerPositions(0.30);

        setupModelAndListViews();
        if (!contactListView.getItems().isEmpty()) {
            contactListView.getSelectionModel().selectFirst();
        }
    }

    private void setupModelAndListViews() {
        contactListView.setItems(contactService.getContactList());
        contactListView.setCellFactory(param -> new ContactCell());
        
        System.out.println("[MessagesController] 📱 Initialized with persistent contact service");
    }

    public static void openChatWithUser(String username) {
        if (instance != null) {
            Platform.runLater(() -> {
                instance.selectOrAddUser(username);
            });
        }
    }
   
    /**
     * Kullanıcıyı contact listesinde seç veya ekle
     */
    private void selectOrAddUser(String username) {
        // ContactService kullanarak persistent contact management
        if (contactService.hasContact(username)) {
            // Existing contact - just select it
            Contact existingContact = contactService.getContact(username);
            contactListView.getSelectionModel().select(existingContact);
            System.out.printf("📱 Selected existing contact: %s%n", username);
        } else {
            // New contact - add to service
            contactService.addNewContact(username);
            
            // CRITICAL FIX: Wait for JavaFX to process the ObservableList change
            // before attempting selection. This prevents IndexOutOfBoundsException
            // when ListView hasn't updated its internal state yet.
            Platform.runLater(() -> {
                // Find and select the newly added contact
                Contact newContact = contactService.getContact(username);
                if (newContact != null) {
                    contactListView.getSelectionModel().select(newContact);
                    System.out.printf("Added and selected new contact: %s%n", username);
                } else {
                    System.err.printf("Contact %s was added but not found in list%n", username);
                }
            });
        }
    }
    
    /**
     * Contact status güncelle
     */
    private void updateContactStatus(String username, String newStatus) {
        for (int i = 0; i < contactListView.getItems().size(); i++) {
            Contact contact = contactListView.getItems().get(i);
            if (contact.getId().equals(username)) {
                // Mevcut status aynıysa güncelleme yapma - infinite loop prevention
                if (contact.getStatus().equals(newStatus)) {
                    return;
                }
                
                Contact updatedContact = new Contact(
                    contact.getId(),
                    contact.getName(),
                    newStatus,
                    contact.getLastMessage(),
                    contact.getTime(),
                    contact.getUnreadCount(),
                    contact.isGroup()
                );
                
                // Selection listener'ı geçici olarak devre dışı bırak
                contactListView.getSelectionModel().selectedItemProperty().removeListener(contactSelectionListener);
                contactListView.getItems().set(i, updatedContact);
                contactListView.refresh();
                // Selection listener'ı tekrar aktif et
                contactListView.getSelectionModel().selectedItemProperty().addListener(contactSelectionListener);
                break;
            }
        }
    }

    // Geçici olarak Contact modelini burada tutuyoruz. İdealde bu da model paketinde olmalı.
    public static class Contact {
        private final String id, name, status, lastMessage, time;
        private final int unreadCount;
        private final boolean isGroup;
        public Contact(String id, String name, String status, String lastMessage, String time, int unreadCount, boolean isGroup) {
            this.id = id; this.name = name; this.status = status; this.lastMessage = lastMessage; this.time = time; this.unreadCount = unreadCount; this.isGroup = isGroup;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public String getLastMessage() { return lastMessage; }
        public String getTime() { return time; }
        public int getUnreadCount() { return unreadCount; }
        public boolean isGroup() { return isGroup; }
        public String getAvatarChar() { return name.isEmpty() ? "" : name.substring(0, 1); }
        public boolean isOnline() { return status.equalsIgnoreCase("online"); }
    }
    
}