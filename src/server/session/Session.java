package server.session;

import com.fasterxml.jackson.databind.JsonNode;
import server.model.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class Session {

    private final String sessionId;
    private final String documentId;
    private final Map<String, UserSession> users;
    private final String editorCode;
    private final String viewerCode;

    private final Document document;
    private final List<StoredOperation> operationLog;
    private final AtomicLong sequenceCounter;

    public Session(String sessionId,
                   String documentId,
                   String editorCode,
                   String viewerCode,
                   String title) {
        this(sessionId, documentId, editorCode, viewerCode, new Document(documentId, title));
    }

    public Session(String sessionId,
                   String documentId,
                   String editorCode,
                   String viewerCode,
                   Document document) {
        this.sessionId = sessionId;
        this.documentId = documentId;
        this.editorCode = editorCode;
        this.viewerCode = viewerCode;
        this.users = new ConcurrentHashMap<>();
        this.document = document;
        this.operationLog = new CopyOnWriteArrayList<>();
        this.sequenceCounter = new AtomicLong(0L);
    }

    public synchronized AttachResult attachUser(String userId,
                                                UserSession.Role requestedRole,
                                                javax.websocket.Session socket,
                                                long reconnectWindowMs) {
        long now = System.currentTimeMillis();

        UserSession existing = users.get(userId);
        boolean isReconnect = false;

        if (existing == null) {
            existing = new UserSession(userId, requestedRole);
            users.put(userId, existing);
        } else if (!existing.isConnected()
                && existing.getDisconnectedAt() > 0
                && now - existing.getDisconnectedAt() <= reconnectWindowMs) {
            isReconnect = true;
        }

        existing.attachSocket(socket);
        return new AttachResult(existing, isReconnect);
    }

    public synchronized void markUserDisconnected(String userId) {
        UserSession user = users.get(userId);
        if (user != null) {
            user.detachSocket();
        }
    }

    public synchronized List<UserSession> removeExpiredDisconnectedUsers(long cutoffEpochMs) {
        List<UserSession> removed = new ArrayList<>();
        users.entrySet().removeIf(entry -> {
            UserSession user = entry.getValue();
            boolean expired = !user.isConnected()
                    && user.getDisconnectedAt() > 0
                    && user.getDisconnectedAt() < cutoffEpochMs;
            if (expired) {
                removed.add(user);
            }
            return expired;
        });
        return removed;
    }

    public long appendOperation(String operationType, String senderUserId, JsonNode fullMessage) {
        long sequence = sequenceCounter.incrementAndGet();
        StoredOperation operation = new StoredOperation(
                sequence,
                operationType,
                senderUserId,
                fullMessage == null ? null : fullMessage.deepCopy(),
                System.currentTimeMillis());

        operationLog.add(operation);
        document.addOperation(operation.getMessage());
        return sequence;
    }

    public synchronized void restoreOperations(List<StoredOperation> persistedOperations) {
        operationLog.clear();
        if (persistedOperations == null || persistedOperations.isEmpty()) {
            sequenceCounter.set(0L);
            return;
        }

        persistedOperations.sort(Comparator.comparingLong(StoredOperation::getSequence));
        operationLog.addAll(persistedOperations);
        sequenceCounter.set(persistedOperations.get(persistedOperations.size() - 1).getSequence());
    }

    public List<StoredOperation> getOperationsAfter(long lastDeliveredSeq) {
        List<StoredOperation> results = new ArrayList<>();
        for (StoredOperation operation : operationLog) {
            if (operation.getSequence() > lastDeliveredSeq) {
                results.add(operation);
            }
        }
        return results;
    }

    public List<StoredOperation> getOperationLogSnapshot() {
        return new ArrayList<>(operationLog);
    }

    public Collection<UserSession> getUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public UserSession getUser(String userId) {
        return users.get(userId);
    }

    public boolean hasConnectedUsers() {
        for (UserSession user : users.values()) {
            if (user.isConnected()) {
                return true;
            }
        }
        return false;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getEditorCode() {
        return editorCode;
    }

    public String getViewerCode() {
        return viewerCode;
    }

    public Document getDocument() {
        return document;
    }

    public static class AttachResult {
        private final UserSession userSession;
        private final boolean reconnect;

        public AttachResult(UserSession userSession, boolean reconnect) {
            this.userSession = userSession;
            this.reconnect = reconnect;
        }

        public UserSession getUserSession() {
            return userSession;
        }

        public boolean isReconnect() {
            return reconnect;
        }
    }

    public static class StoredOperation {
        private long sequence;
        private String type;
        private String senderUserId;
        private JsonNode message;
        private long timestamp;

        public StoredOperation() {
        }

        public StoredOperation(long sequence,
                               String type,
                               String senderUserId,
                               JsonNode message,
                               long timestamp) {
            this.sequence = sequence;
            this.type = type;
            this.senderUserId = senderUserId;
            this.message = message;
            this.timestamp = timestamp;
        }

        public long getSequence() {
            return sequence;
        }

        public void setSequence(long sequence) {
            this.sequence = sequence;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSenderUserId() {
            return senderUserId;
        }

        public void setSenderUserId(String senderUserId) {
            this.senderUserId = senderUserId;
        }

        public JsonNode getMessage() {
            return message;
        }

        public void setMessage(JsonNode message) {
            this.message = message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
