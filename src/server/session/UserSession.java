package server.session;

import javax.websocket.CloseReason;
import java.io.IOException;
import java.util.Locale;

public class UserSession {

    public enum Role {
        EDITOR,
        VIEWER;

        public static Role fromString(String rawRole, Role defaultRole) {
            if (rawRole == null || rawRole.isBlank()) {
                return defaultRole;
            }
            String normalized = rawRole.trim().toUpperCase(Locale.ROOT);
            if ("EDITOR".equals(normalized)) {
                return EDITOR;
            }
            if ("VIEWER".equals(normalized)) {
                return VIEWER;
            }
            return defaultRole;
        }
    }

    private final String userId;
    private final Role role;

    private volatile javax.websocket.Session socketSession;
    private volatile boolean connected;
    private volatile long lastSeenAt;
    private volatile long disconnectedAt;
    private volatile long lastDeliveredSeq;
    private volatile int cursorPosition = -1;

    public UserSession(String userId, Role role) {
        this.userId = userId;
        this.role = role;
        this.connected = false;
        this.lastSeenAt = System.currentTimeMillis();
        this.lastDeliveredSeq = 0L;
    }

    public synchronized void attachSocket(javax.websocket.Session newSocket) {
        if (newSocket == null) {
            return;
        }

        if (socketSession != null
                && socketSession.isOpen()
                && !socketSession.getId().equals(newSocket.getId())) {
            try {
                socketSession.close(new CloseReason(
                        CloseReason.CloseCodes.NORMAL_CLOSURE,
                        "Reconnected from another client"));
            } catch (IOException ignored) {
                // Best effort close on previous socket.
            }
        }

        this.socketSession = newSocket;
        this.connected = true;
        this.lastSeenAt = System.currentTimeMillis();
        this.disconnectedAt = 0L;
    }

    public synchronized void detachSocket() {
        this.socketSession = null;
        this.connected = false;
        this.cursorPosition = -1;
        this.disconnectedAt = System.currentTimeMillis();
        this.lastSeenAt = this.disconnectedAt;
    }

    public synchronized void markSeen() {
        this.lastSeenAt = System.currentTimeMillis();
    }

    public synchronized void markDelivered(long sequence) {
        if (sequence > this.lastDeliveredSeq) {
            this.lastDeliveredSeq = sequence;
        }
    }

    public String getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public javax.websocket.Session getSocketSession() {
        return socketSession;
    }

    public boolean isConnected() {
        return connected;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public long getDisconnectedAt() {
        return disconnectedAt;
    }

    public long getLastDeliveredSeq() {
        return lastDeliveredSeq;
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public void setCursorPosition(int cursorPosition) {
        this.cursorPosition = cursorPosition;
        this.lastSeenAt = System.currentTimeMillis();
    }
}
