package com.saferoom.server;

import com.saferoom.grpc.SafeRoomProto;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class P2PSessionManager {

    private static final Logger LOGGER = Logger.getLogger(P2PSessionManager.class.getName());

    private static final ConcurrentHashMap<String, P2PSession> sessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, StreamObserver<SafeRoomProto.ICEStreamMessage>>> streamObservers =
        new ConcurrentHashMap<>();
    
    public static class P2PSession {
        String sessionId;
        String user1;
        String user2;
        long createdAt;
        Map<String, UserICEInfo> userICEInfos = new ConcurrentHashMap<>();
        
        public P2PSession(String sessionId, String user1, String user2) {
            this.sessionId = sessionId;
            this.user1 = user1;
            this.user2 = user2;
            this.createdAt = System.currentTimeMillis();
            userICEInfos.put(user1, new UserICEInfo(user1));
            userICEInfos.put(user2, new UserICEInfo(user2));
        }
        
        public String getOtherUser(String username) {
            return username.equals(user1) ? user2 : user1;
        }

        public String getSessionId() {
            return sessionId;
        }
    }
    
    public static class UserICEInfo {
        String username;
        List<SafeRoomProto.ICECandidate> candidates = Collections.synchronizedList(new ArrayList<>());
        boolean gatheringComplete = false;
        volatile SafeRoomProto.ICERestart pendingRestart;
        
        public UserICEInfo(String username) {
            this.username = username;
        }
        
        public void addCandidate(SafeRoomProto.ICECandidate candidate) {
            candidates.add(candidate);
            String ip = candidate.getIp();
            String ipVersion = (ip != null && ip.contains(":")) ? "IPv6" : "IPv4";
            LOGGER.log(Level.INFO, () -> String.format("Candidate added for %s (%s, total: %d)",
                username, ipVersion, candidates.size()));
            pendingRestart = null;
        }

        public List<SafeRoomProto.ICECandidate> getCandidatesFrom(int startIndex) {
            if (startIndex >= candidates.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(candidates.subList(startIndex, candidates.size()));
        }

        public void reset() {
            candidates.clear();
            gatheringComplete = false;
        }

        public void markRestart(SafeRoomProto.ICERestart restart) {
            pendingRestart = restart;
            reset();
        }
    }
    
    public static P2PSession createSession(String user1, String user2) {
        String sessionId = UUID.randomUUID().toString();
        P2PSession session = new P2PSession(sessionId, user1, user2);
        sessions.put(sessionId, session);
        streamObservers.put(sessionId, new ConcurrentHashMap<>());

        System.out.println("P2P Session created: " + sessionId);
        System.out.println("  User1: " + user1);
        System.out.println("  User2: " + user2);
        
        return session;
    }
    
    public static boolean addCandidate(String sessionId, String username, SafeRoomProto.ICECandidate candidate) {
        P2PSession session = sessions.get(sessionId);
        if (session == null) {
            System.err.println("Session not found: " + sessionId);
            return false;
        }
        
        UserICEInfo info = session.userICEInfos.get(username);
        if (info == null) {
            System.err.println("User not in session: " + username);
            return false;
        }
        
        info.addCandidate(candidate);
        notifyCandidate(session, username, candidate);
        return true;
    }

    public static boolean markGatheringComplete(String sessionId, String username) {
        P2PSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }

        UserICEInfo info = session.userICEInfos.get(username);
        if (info == null) {
            return false;
        }

        info.gatheringComplete = true;
        System.out.println("ICE gathering complete for " + username + " in session " + sessionId);
        notifyGatheringComplete(session, username);
        return true;
    }
    
    public static P2PSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    public static void removeSession(String sessionId) {
        sessions.remove(sessionId);
        streamObservers.remove(sessionId);
        System.out.println("P2P Session removed: " + sessionId);
    }

    public static void registerStreamObserver(String sessionId, String username,
                                              StreamObserver<SafeRoomProto.ICEStreamMessage> observer) {
        P2PSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        streamObservers.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>()).put(username, observer);

        String otherUser = session.getOtherUser(username);
        UserICEInfo otherInfo = session.userICEInfos.get(otherUser);
        if (otherInfo != null) {
            // Send existing candidates
            for (SafeRoomProto.ICECandidate candidate : otherInfo.getCandidatesFrom(0)) {
                SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
                    .setSessionId(sessionId)
                    .setFromUser(otherUser)
                    .setCandidate(candidate)
                    .build();
                safeOnNext(observer, message);
            }

            // Notify remote gathering status
            if (otherInfo.gatheringComplete) {
                SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
                    .setSessionId(sessionId)
                    .setFromUser(otherUser)
                    .setGathering(SafeRoomProto.ICEGatheringStatus.newBuilder().setComplete(true).build())
                    .build();
                safeOnNext(observer, message);
            }

            if (otherInfo.pendingRestart != null) {
                SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
                    .setSessionId(sessionId)
                    .setFromUser(otherUser)
                    .setRestart(otherInfo.pendingRestart)
                    .build();
                safeOnNext(observer, message);
            }
        }
    }

    public static void unregisterStreamObserver(String sessionId, String username) {
        Map<String, StreamObserver<SafeRoomProto.ICEStreamMessage>> observers = streamObservers.get(sessionId);
        if (observers != null) {
            observers.remove(username);
        }
    }

    public static void notifyRestart(String sessionId, String username, SafeRoomProto.ICERestart restart) {
        P2PSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        UserICEInfo info = session.userICEInfos.get(username);
        if (info == null) {
            return;
        }

        info.markRestart(restart);
        String otherUser = session.getOtherUser(username);
        sendRestart(session.sessionId, otherUser, username, restart);
    }

    private static void notifyCandidate(P2PSession session, String fromUser, SafeRoomProto.ICECandidate candidate) {
        String otherUser = session.getOtherUser(fromUser);
        sendCandidate(session.sessionId, otherUser, fromUser, candidate);
    }

    private static void notifyGatheringComplete(P2PSession session, String fromUser) {
        String otherUser = session.getOtherUser(fromUser);
        SafeRoomProto.ICEGatheringStatus status = SafeRoomProto.ICEGatheringStatus.newBuilder().setComplete(true).build();
        sendGathering(session.sessionId, otherUser, fromUser, status);
    }

    private static void sendCandidate(String sessionId, String toUser, String fromUser, SafeRoomProto.ICECandidate candidate) {
        Map<String, StreamObserver<SafeRoomProto.ICEStreamMessage>> observers = streamObservers.get(sessionId);
        if (observers == null) {
            return;
        }
        StreamObserver<SafeRoomProto.ICEStreamMessage> observer = observers.get(toUser);
        if (observer == null) {
            return;
        }

        SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
            .setSessionId(sessionId)
            .setFromUser(fromUser)
            .setCandidate(candidate)
            .build();
        String ipVersion = (candidate.getIp() != null && candidate.getIp().contains(":")) ? "IPv6" : "IPv4";
        LOGGER.log(Level.INFO, () -> String.format("Forwarding %s candidate from %s to %s", ipVersion, fromUser, toUser));
        safeOnNext(observer, message);
    }

    private static void sendGathering(String sessionId, String toUser, String fromUser, SafeRoomProto.ICEGatheringStatus status) {
        Map<String, StreamObserver<SafeRoomProto.ICEStreamMessage>> observers = streamObservers.get(sessionId);
        if (observers == null) {
            return;
        }
        StreamObserver<SafeRoomProto.ICEStreamMessage> observer = observers.get(toUser);
        if (observer == null) {
            return;
        }

        SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
            .setSessionId(sessionId)
            .setFromUser(fromUser)
            .setGathering(status)
            .build();
        safeOnNext(observer, message);
    }

    private static void sendRestart(String sessionId, String toUser, String fromUser, SafeRoomProto.ICERestart restart) {
        Map<String, StreamObserver<SafeRoomProto.ICEStreamMessage>> observers = streamObservers.get(sessionId);
        if (observers == null) {
            return;
        }
        StreamObserver<SafeRoomProto.ICEStreamMessage> observer = observers.get(toUser);
        if (observer == null) {
            return;
        }

        SafeRoomProto.ICEStreamMessage message = SafeRoomProto.ICEStreamMessage.newBuilder()
            .setSessionId(sessionId)
            .setFromUser(fromUser)
            .setRestart(restart)
            .build();
        safeOnNext(observer, message);
    }

    private static void safeOnNext(StreamObserver<SafeRoomProto.ICEStreamMessage> observer,
                                   SafeRoomProto.ICEStreamMessage message) {
        synchronized (observer) {
            try {
                observer.onNext(message);
            } catch (RuntimeException e) {
                System.err.println("Failed to push ICE stream message: " + e.getMessage());
            }
        }
    }
    
    static {
        Timer cleanupTimer = new Timer(true);
        cleanupTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                sessions.entrySet().removeIf(entry -> {
                    boolean isOld = (now - entry.getValue().createdAt) > 30 * 60 * 1000;
                    if (isOld) {
                        System.out.println("Cleaning old P2P session: " + entry.getKey());
                    }
                    return isOld;
                });
            }
        }, 60000, 60000);
    }
}