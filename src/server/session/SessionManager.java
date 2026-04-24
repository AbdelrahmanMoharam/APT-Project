package server.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import server.model.Document;
import server.persistence.DatabaseManager;
import server.protocol.Message;
import server.protocol.MessageType;
import server.sharing.CodeManager;

import javax.websocket.CloseReason;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {

    private static final long RECONNECT_WINDOW_MS = TimeUnit.MINUTES.toMillis(5);

    private static final Set<String> OPERATION_TYPES = Set.of(
            "INSERT_CHAR",
            "DELETE_CHAR",
            "FORMAT",
            "BLOCK_OPERATION",
            "UNDO",
            "REDO",
            "INSERT_BLOCK",
            "DELETE_BLOCK",
            "SPLIT_BLOCK",
            "MOVE_BLOCK",
            "FORMAT_CHAR",
            "FORMAT_RANGE");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ConnectionBinding> connectionBindings = new ConcurrentHashMap<>();
    private final Map<String, String> legacyDocumentByConnection = new ConcurrentHashMap<>();

    private final CodeManager codeManager;
    private final DatabaseManager databaseManager;
    private final ScheduledExecutorService cleanupExecutor;

    public SessionManager() {
        this(new CodeManager(), new DatabaseManager());
    }

    public SessionManager(CodeManager codeManager, DatabaseManager databaseManager) {
        this.codeManager = Objects.requireNonNull(codeManager, "codeManager must not be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");

        restorePersistedSessions();

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredUsers,
                1,
                1,
                TimeUnit.MINUTES);
    }

    public void onOpen(javax.websocket.Session socketSession, String legacyDocumentId) {
        if (socketSession == null) {
            return;
        }
        if (legacyDocumentId != null && !legacyDocumentId.isBlank()) {
            legacyDocumentByConnection.put(socketSession.getId(), legacyDocumentId);
        }
        System.out.println("Socket opened: " + socketSession.getId());
    }

    public void onMessage(javax.websocket.Session socketSession,
                          String rawMessage,
                          String legacyDocumentId) {
        if (socketSession == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        try {
            JsonNode rootNode = mapper.readTree(rawMessage);
            if (!rootNode.isObject()) {
                sendError(socketSession, null, "Message must be a JSON object");
                return;
            }

            ObjectNode rootObject = (ObjectNode) rootNode;
            Message message = mapper.treeToValue(rootObject, Message.class);
            String rawType = normalizeType(message.getType());
            MessageType messageType = message.getMessageType();

            switch (messageType) {
                case CONNECT:
                    handleConnect(socketSession);
                    break;

                case CREATE_SESSION:
                    handleCreateSession(socketSession, message);
                    break;

                case JOIN_SESSION:
                    handleJoinSession(socketSession, message, legacyDocumentId, false);
                    break;

                case LEGACY_JOIN:
                    handleJoinSession(socketSession, message, legacyDocumentId, true);
                    break;

                case CURSOR_UPDATE:
                    handleCursorUpdate(socketSession, message, rootObject, legacyDocumentId);
                    break;

                default:
                    if (isOperationType(rawType)) {
                        handleOperation(socketSession, rawType, message, rootObject, legacyDocumentId);
                    } else {
                        sendError(socketSession, message.resolveSessionId(),
                                "Unsupported message type: " + message.getType());
                    }
                    break;
            }
        } catch (Exception e) {
            sendError(socketSession, null, "Failed to parse message: " + e.getMessage());
        }
    }

    public void onClose(javax.websocket.Session socketSession, CloseReason closeReason) {
        if (socketSession == null) {
            return;
        }

        legacyDocumentByConnection.remove(socketSession.getId());
        ConnectionBinding binding = connectionBindings.remove(socketSession.getId());
        if (binding == null) {
            return;
        }

        Session session = sessions.get(binding.sessionId());
        if (session == null) {
            return;
        }

        session.markUserDisconnected(binding.userId());
        databaseManager.saveSession(session);

        broadcastUserLeft(session, binding.userId());
        broadcastUserList(session);

        String reason = closeReason == null ? "unknown" : closeReason.getReasonPhrase();
        System.out.println("Socket closed: " + socketSession.getId() + " reason=" + reason);
    }

    public void onError(javax.websocket.Session socketSession, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        String sessionId = socketSession == null ? "unknown" : socketSession.getId();
        System.err.println("Socket error on " + sessionId + ": " + throwable.getMessage());
    }

    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    private void restorePersistedSessions() {
        List<DatabaseManager.PersistedSession> persistedSessions = databaseManager.loadSessions();
        for (DatabaseManager.PersistedSession persisted : persistedSessions) {
            String sessionId = persisted.getSessionId();
            String documentId = persisted.getDocumentId();

            if (sessionId == null || documentId == null) {
                continue;
            }

            String editorCode = persisted.getEditorCode();
            if (editorCode == null || editorCode.isBlank()) {
                editorCode = codeManager.generateCode();
            }

            String viewerCode = persisted.getViewerCode();
            if (viewerCode == null || viewerCode.isBlank()) {
                viewerCode = codeManager.generateCode();
            }

            Document document = persisted.getDocument();
            if (document == null) {
                document = new Document(documentId, "Recovered Document");
            }

            Session restoredSession = new Session(
                    sessionId,
                    documentId,
                    editorCode,
                    viewerCode,
                    document);
            restoredSession.restoreOperations(persisted.getOperations());

            sessions.put(sessionId, restoredSession);
            codeManager.registerCode(editorCode, sessionId, UserSession.Role.EDITOR);
            codeManager.registerCode(viewerCode, sessionId, UserSession.Role.VIEWER);
        }

        if (!persistedSessions.isEmpty()) {
            System.out.println("Restored sessions from disk: " + persistedSessions.size());
        }
    }

    private void handleConnect(javax.websocket.Session socketSession) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("status", "OK");
        payload.put("message", "Connected to collaboration server");
        sendMessage(socketSession, buildMessage("CONNECT", null, null, payload));
    }

    private void handleCreateSession(javax.websocket.Session socketSession, Message message) {
        String userId = normalizeString(message.getUserId());
        if (userId == null) {
            sendError(socketSession, null, "CREATE_SESSION requires userId");
            return;
        }

        String sessionId = shortId();
        String documentId = sessionId;
        String title = "Untitled Document";

        JsonNode payload = message.getPayload();
        if (payload != null) {
            if (payload.hasNonNull("documentId")) {
                documentId = payload.get("documentId").asText();
            }
            if (payload.hasNonNull("title")) {
                title = payload.get("title").asText();
            }
        }

        String editorCode = codeManager.generateCode(sessionId, UserSession.Role.EDITOR);
        String viewerCode = codeManager.generateCode(sessionId, UserSession.Role.VIEWER);

        Session session = new Session(sessionId, documentId, editorCode, viewerCode, title);
        sessions.put(sessionId, session);

        Session.AttachResult attachResult = session.attachUser(
                userId,
                UserSession.Role.EDITOR,
                socketSession,
                RECONNECT_WINDOW_MS);

        bindConnection(socketSession, sessionId, userId);
        attachResult.getUserSession().markDelivered(0L);

        databaseManager.saveSession(session);

        ObjectNode createdPayload = mapper.createObjectNode();
        createdPayload.put("sessionId", sessionId);
        createdPayload.put("documentId", documentId);
        createdPayload.put("userId", userId);
        createdPayload.put("role", UserSession.Role.EDITOR.name());
        createdPayload.put("editorCode", editorCode);
        createdPayload.put("viewerCode", viewerCode);

        sendMessage(socketSession, buildMessage("SESSION_CREATED", sessionId, userId, createdPayload));
        broadcastUserList(session);
    }

    private void handleJoinSession(javax.websocket.Session socketSession,
                                   Message message,
                                   String legacyDocumentId,
                                   boolean legacyMode) {
        String userId = normalizeString(message.getUserId());
        if (userId == null) {
            sendError(socketSession, null, "JOIN_SESSION requires userId");
            return;
        }

        Session session;
        UserSession.Role role;

        if (legacyMode) {
            String legacySessionId = normalizeString(message.resolveSessionId());
            if (legacySessionId == null) {
                legacySessionId = normalizeString(legacyDocumentId);
            }
            if (legacySessionId == null) {
                legacySessionId = legacyDocumentByConnection.get(socketSession.getId());
            }
            if (legacySessionId == null) {
                sendError(socketSession, null, "Legacy JOIN requires documentId/sessionId");
                return;
            }

            String finalLegacySessionId = legacySessionId;
            session = sessions.computeIfAbsent(legacySessionId, id -> {
                String editorCode = codeManager.generateCode(id, UserSession.Role.EDITOR);
                String viewerCode = codeManager.generateCode(id, UserSession.Role.VIEWER);
                Session created = new Session(id, id, editorCode, viewerCode, "Legacy Session");
                databaseManager.saveSession(created);
                return created;
            });

            role = UserSession.Role.fromString(message.resolveRole(), UserSession.Role.EDITOR);

            codeManager.registerCode(session.getEditorCode(), finalLegacySessionId, UserSession.Role.EDITOR);
            codeManager.registerCode(session.getViewerCode(), finalLegacySessionId, UserSession.Role.VIEWER);
        } else {
            String code = normalizeString(message.resolveCode());
            if (code == null || !codeManager.validateCode(code)) {
                sendError(socketSession, null, "Invalid collaboration code");
                return;
            }

            String sessionId = codeManager.getSessionByCode(code);
            session = sessions.get(sessionId);
            if (session == null) {
                sendError(socketSession, null, "Session not found for provided code");
                return;
            }

            role = codeManager.getRoleByCode(code);
            if (role == null) {
                role = UserSession.Role.VIEWER;
            }
        }

        Session.AttachResult attachResult = session.attachUser(userId, role, socketSession, RECONNECT_WINDOW_MS);
        UserSession userSession = attachResult.getUserSession();

        bindConnection(socketSession, session.getSessionId(), userId);

        if (legacyMode) {
            ObjectNode syncState = buildMessage("SYNC_STATE", session.getSessionId(), userId, null);
            sendMessage(socketSession, syncState);
            replayOperationsLegacy(session, userSession, attachResult.isReconnect());
        } else {
            ObjectNode joinedPayload = mapper.createObjectNode();
            joinedPayload.put("sessionId", session.getSessionId());
            joinedPayload.put("documentId", session.getDocumentId());
            joinedPayload.put("userId", userId);
            joinedPayload.put("role", userSession.getRole().name());
            joinedPayload.put("editorCode", session.getEditorCode());
            joinedPayload.put("viewerCode", session.getViewerCode());

            sendMessage(socketSession,
                    buildMessage("SESSION_JOINED", session.getSessionId(), userId, joinedPayload));

            replayOperationsModern(session, userSession, attachResult.isReconnect());
        }

        databaseManager.saveSession(session);

        broadcastUserJoined(session, userId);
        broadcastUserList(session);
    }

    private void handleCursorUpdate(javax.websocket.Session socketSession,
                                    Message message,
                                    ObjectNode rootObject,
                                    String legacyDocumentId) {
        ResolvedContext context = resolveContext(socketSession, message, legacyDocumentId);
        if (context == null) {
            sendError(socketSession, null, "CURSOR_UPDATE missing session or user context");
            return;
        }

        Session session = sessions.get(context.sessionId());
        if (session == null) {
            sendError(socketSession, context.sessionId(), "Session does not exist");
            return;
        }

        UserSession userSession = session.getUser(context.userId());
        if (userSession == null) {
            sendError(socketSession, context.sessionId(), "User is not part of the session");
            return;
        }

        int position = resolveCursorPosition(message, rootObject);
        userSession.setCursorPosition(position);

        ObjectNode cursorMessage = mapper.createObjectNode();
        cursorMessage.put("type", "CURSOR_UPDATE");
        cursorMessage.put("sessionId", context.sessionId());
        cursorMessage.put("userId", context.userId());
        cursorMessage.put("position", position);

        broadcastToOthers(session, context.userId(), cursorMessage, -1L);
    }

    private void handleOperation(javax.websocket.Session socketSession,
                                 String rawType,
                                 Message message,
                                 ObjectNode rootObject,
                                 String legacyDocumentId) {
        ResolvedContext context = resolveContext(socketSession, message, legacyDocumentId);
        if (context == null) {
            sendError(socketSession, null, "Operation missing session or user context");
            return;
        }

        Session session = sessions.get(context.sessionId());
        if (session == null) {
            sendError(socketSession, context.sessionId(), "Session does not exist");
            return;
        }

        UserSession sender = session.getUser(context.userId());
        if (sender == null) {
            sendError(socketSession, context.sessionId(), "User is not part of the session");
            return;
        }

        if (sender.getRole() == UserSession.Role.VIEWER) {
            sendError(socketSession, context.sessionId(), "Permission denied: viewer cannot edit");
            return;
        }

        ObjectNode outbound = rootObject.deepCopy();
        outbound.put("type", rawType);
        outbound.put("sessionId", context.sessionId());
        outbound.put("userId", context.userId());

        long sequence = session.appendOperation(rawType, context.userId(), outbound);
        outbound.put("sequence", sequence);

        sender.markDelivered(sequence);
        databaseManager.saveSession(session);

        broadcastToOthers(session, context.userId(), outbound, sequence);
    }

    private void replayOperationsModern(Session session,
                                        UserSession targetUser,
                                        boolean reconnect) {
        long fromSequence = reconnect ? targetUser.getLastDeliveredSeq() : 0L;
        List<Session.StoredOperation> operations = session.getOperationsAfter(fromSequence);
        if (operations.isEmpty()) {
            return;
        }

        ArrayNode operationArray = mapper.createArrayNode();
        long lastSequence = fromSequence;

        for (Session.StoredOperation operation : operations) {
            JsonNode message = operation.getMessage();
            if (message != null) {
                operationArray.add(message.deepCopy());
            }
            lastSequence = operation.getSequence();
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("fromSequenceExclusive", fromSequence);
        payload.put("toSequenceInclusive", lastSequence);
        payload.set("operations", operationArray);

        sendMessage(targetUser.getSocketSession(),
                buildMessage("MISSED_OPERATIONS", session.getSessionId(), targetUser.getUserId(), payload));
        targetUser.markDelivered(lastSequence);
    }

    private void replayOperationsLegacy(Session session,
                                        UserSession targetUser,
                                        boolean reconnect) {
        long fromSequence = reconnect ? targetUser.getLastDeliveredSeq() : 0L;
        List<Session.StoredOperation> operations = session.getOperationsAfter(fromSequence);
        if (operations.isEmpty()) {
            return;
        }

        long lastSequence = fromSequence;
        for (Session.StoredOperation operation : operations) {
            JsonNode message = operation.getMessage();
            if (message != null) {
                sendMessage(targetUser.getSocketSession(), message);
            }
            lastSequence = operation.getSequence();
        }

        targetUser.markDelivered(lastSequence);
    }

    private void broadcastToOthers(Session session,
                                   String senderUserId,
                                   JsonNode message,
                                   long deliveredSequence) {
        Collection<UserSession> users = session.getUsers();
        for (UserSession user : users) {
            if (!user.isConnected()) {
                continue;
            }
            if (senderUserId != null && senderUserId.equals(user.getUserId())) {
                continue;
            }

            sendMessage(user.getSocketSession(), message);
            if (deliveredSequence >= 0) {
                user.markDelivered(deliveredSequence);
            }
        }
    }

    private void broadcastUserJoined(Session session, String joinedUserId) {
        ObjectNode joinedMessage = mapper.createObjectNode();
        joinedMessage.put("type", "USER_JOINED");
        joinedMessage.put("sessionId", session.getSessionId());
        joinedMessage.put("userId", joinedUserId);

        broadcastToOthers(session, joinedUserId, joinedMessage, -1L);
    }

    private void broadcastUserLeft(Session session, String leftUserId) {
        ObjectNode leftMessage = mapper.createObjectNode();
        leftMessage.put("type", "USER_LEFT");
        leftMessage.put("sessionId", session.getSessionId());
        leftMessage.put("userId", leftUserId);

        broadcastToOthers(session, leftUserId, leftMessage, -1L);
    }

    private void broadcastUserList(Session session) {
        ArrayNode usersArray = mapper.createArrayNode();
        for (UserSession user : session.getUsers()) {
            if (!user.isConnected()) {
                continue;
            }

            ObjectNode userNode = mapper.createObjectNode();
            userNode.put("userId", user.getUserId());
            userNode.put("role", user.getRole().name());
            userNode.put("cursor", user.getCursorPosition());
            usersArray.add(userNode);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.set("users", usersArray);

        ObjectNode listMessage = buildMessage("USER_LIST", session.getSessionId(), null, payload);
        for (UserSession user : session.getUsers()) {
            if (user.isConnected()) {
                sendMessage(user.getSocketSession(), listMessage);
            }
        }
    }

    private void cleanupExpiredUsers() {
        long cutoff = System.currentTimeMillis() - RECONNECT_WINDOW_MS;

        for (Session session : sessions.values()) {
            List<UserSession> removed = session.removeExpiredDisconnectedUsers(cutoff);
            if (!removed.isEmpty()) {
                if (session.hasConnectedUsers()) {
                    broadcastUserList(session);
                }
                databaseManager.saveSession(session);
            }
        }
    }

    private int resolveCursorPosition(Message message, ObjectNode rootObject) {
        if (message.getPosition() != null) {
            return message.getPosition();
        }
        JsonNode payload = message.getPayload();
        if (payload != null && payload.has("position")) {
            return payload.get("position").asInt(-1);
        }
        if (rootObject.has("position")) {
            return rootObject.get("position").asInt(-1);
        }
        return -1;
    }

    private ResolvedContext resolveContext(javax.websocket.Session socketSession,
                                           Message message,
                                           String legacyDocumentId) {
        ConnectionBinding binding = connectionBindings.get(socketSession.getId());
        if (binding != null) {
            return new ResolvedContext(binding.sessionId(), binding.userId());
        }

        String sessionId = normalizeString(message.resolveSessionId());
        if (sessionId == null) {
            sessionId = normalizeString(legacyDocumentId);
        }
        if (sessionId == null) {
            sessionId = legacyDocumentByConnection.get(socketSession.getId());
        }

        String userId = normalizeString(message.getUserId());
        if (sessionId == null || userId == null) {
            return null;
        }

        bindConnection(socketSession, sessionId, userId);
        return new ResolvedContext(sessionId, userId);
    }

    private void bindConnection(javax.websocket.Session socketSession, String sessionId, String userId) {
        connectionBindings.put(socketSession.getId(), new ConnectionBinding(sessionId, userId));
    }

    private void sendError(javax.websocket.Session socketSession, String sessionId, String errorMessage) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("message", errorMessage);

        sendMessage(socketSession, buildMessage("ERROR", sessionId, null, payload));
    }

    private void sendMessage(javax.websocket.Session socketSession, JsonNode messageNode) {
        if (socketSession == null || messageNode == null || !socketSession.isOpen()) {
            return;
        }

        try {
            String serialized = mapper.writeValueAsString(messageNode);
            socketSession.getAsyncRemote().sendText(serialized);
        } catch (Exception e) {
            System.err.println("Failed to send message to socket " + socketSession.getId());
        }
    }

    private ObjectNode buildMessage(String type,
                                    String sessionId,
                                    String userId,
                                    JsonNode payload) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);

        if (sessionId != null) {
            node.put("sessionId", sessionId);
        }
        if (userId != null) {
            node.put("userId", userId);
        }
        if (payload != null) {
            node.set("payload", payload);
        }

        return node;
    }

    private boolean isOperationType(String rawType) {
        return rawType != null && OPERATION_TYPES.contains(rawType);
    }

    private String normalizeString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeType(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record ConnectionBinding(String sessionId, String userId) {
    }

    private record ResolvedContext(String sessionId, String userId) {
    }
}
