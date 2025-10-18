package com.saferoom.gui.service;


import com.saferoom.gui.model.Message;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashMap;
import java.util.Map;

/**
 * Mesajları yöneten, gönderen ve alan servis.
 * Singleton deseni ile tasarlandı, yani uygulamanın her yerinden tek bir
 * nesnesine erişilebilir.
 */
public class ChatService {

    // Singleton deseni için statik nesne
    private static final ChatService instance = new ChatService();

    // Current user's username (set by ClientMenu during initialization)
    private String currentUsername = null;

    // Veri saklama alanı (eskiden kontrolcüdeydi)
    private final Map<String, ObservableList<Message>> channelMessages = new HashMap<>();

    // DİKKAT: Bu, yeni bir mesaj geldiğinde bunu dinleyenleri haberdar eden sihirli kısımdır.
    private final ObjectProperty<Message> newMessageProperty = new SimpleObjectProperty<>();

    // Constructor'ı private yaparak dışarıdan yeni nesne oluşturulmasını engelliyoruz.
    private ChatService() {
        // Başlangıç için sahte verileri yükle
        setupDummyMessages();
    }

    // Servisin tek nesnesine erişim metodu
    public static ChatService getInstance() {
        return instance;
    }

    /**
     * Set the current user's username (called by ClientMenu during initialization)
     * @param username The current user's username
     */
    public void setCurrentUsername(String username) {
        this.currentUsername = username;
        System.out.printf("[ChatService] 👤 Current user set to: %s%n", username);
    }

    /**
     * Get the current user's username
     * @return The current user's username
     */
    public String getCurrentUsername() {
        return currentUsername;
    }

    /**
     * Belirtilen kanala yeni bir mesaj gönderir.
     * P2P bağlantı varsa P2P kullanır, yoksa server relay kullanır.
     * @param channelId Sohbet kanalının ID'si
     * @param text Gönderilecek mesaj metni
     * @param sender Mesajı gönderen kullanıcı
     */
        /**
     * Belirtilen kanalın mesaj listesini döndürür.
     * @param channelId Sohbet kanalının ID'si
     * @return O kanala ait ObservableList<Message>
     */
    public ObservableList<Message> getMessagesForChannel(String channelId) {
        return channelMessages.computeIfAbsent(channelId, k -> FXCollections.observableArrayList());
    }

    // Yeni mesaj dinleyicisi için property'e erişim metodu
    public ObjectProperty<Message> newMessageProperty() {
        return newMessageProperty;
    }
    
    /**
     * P2P'den gelen mesajı al ve GUI'de göster
     */
    public void receiveP2PMessage(String sender, String receiver, String messageText) {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("[Chat] 📥 P2P message received: %s -> %s: \"%s\"%n", sender, receiver, messageText);
        System.out.printf("[Chat] 🔍 Stack trace:%n");
        for (StackTraceElement elem : Thread.currentThread().getStackTrace()) {
            if (elem.getClassName().contains("saferoom")) {
                System.out.printf("    at %s.%s(%s:%d)%n", 
                    elem.getClassName(), elem.getMethodName(), 
                    elem.getFileName(), elem.getLineNumber());
            }
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
        
        Message incomingMessage = new Message(
            messageText,
            sender,
            sender.isEmpty() ? "?" : sender.substring(0, 1).toUpperCase()
        );
        
        // Mesajı doğru channel'a ekle
        ObservableList<Message> messages = getMessagesForChannel(sender);
        messages.add(incomingMessage);
        
        System.out.printf("[Chat] 📬 Updated contact last message for %s%n", sender);
        System.out.printf("[Chat] ✅ P2P message added to channel: %s%n", sender);
        
        // Update contact's last message (not from me - will increment unread if not active)
        try {
            com.saferoom.gui.service.ContactService contactService = 
                com.saferoom.gui.service.ContactService.getInstance();
            
            // Add contact if doesn't exist
            if (!contactService.hasContact(sender)) {
                contactService.addNewContact(sender);
            }
            
            // Update last message (isFromMe = false)
            contactService.updateLastMessage(sender, messageText, false);
            
            System.out.printf("[Chat] 📬 Updated contact last message for %s%n", sender);
            
        } catch (Exception e) {
            System.err.println("[Chat] Error updating contact for P2P message: " + e.getMessage());
        }
        
        // GUI'yi güncelle
        newMessageProperty.set(incomingMessage);
        
        System.out.printf("[Chat] ✅ P2P message added to channel: %s%n", sender);
    }
    
    /**
     * Dosya transfer işlemi başlat (P2P)
     * @param targetUser Dosya gönderilecek kullanıcı
     * @param filePath Gönderilecek dosyanın yolu
     */
    private void setupDummyMessages() {
        // All chat channels start empty - real messages will be added via P2P
        System.out.println("[ChatService] 🧹 Started with clean message history - no dummy messages");
    }
}