package com.saferoom.rooms.client;

import com.saferoom.rooms.client.logic.DataFSM;

/**
 * Singleton to hold the currently active room session.
 * Allows sharing DataFSM across UI controllers.
 */
public class ActiveRoomSession {

    private static ActiveRoomSession instance;

    private DataFSM currentFSM;
    private String currentRoomId;
    private String currentRoomName;

    private ActiveRoomSession() {
    }

    public static synchronized ActiveRoomSession getInstance() {
        if (instance == null) {
            instance = new ActiveRoomSession();
        }
        return instance;
    }

    public void setActiveSession(String roomId, String roomName, DataFSM fsm) {
        // Clean up previous session if any
        if (currentFSM != null) {
            currentFSM.stop();
        }
        this.currentRoomId = roomId;
        this.currentRoomName = roomName;
        this.currentFSM = fsm;
    }

    public DataFSM getFSM() {
        return currentFSM;
    }

    public String getRoomId() {
        return currentRoomId;
    }

    public String getRoomName() {
        return currentRoomName;
    }

    public void clearSession() {
        if (currentFSM != null) {
            currentFSM.stop();
            currentFSM = null;
        }
        currentRoomId = null;
        currentRoomName = null;
    }
}
