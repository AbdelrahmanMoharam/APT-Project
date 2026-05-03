package client.network;

import client.operations.Operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ClientEndpoint
public class WebSocketClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Listener listener;

    private volatile Session session;
    private volatile String sessionId;
    private volatile String userId;
    private volatile String role;

    public WebSocketClient(Listener listener) {
        this.listener = listener;
    }

    public void connect(String endpointUri) {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, URI.create(endpointUri));
        } catch (Exception e) {
            listener.onError("Could not connect to server: " + e.getMessage());
        }
    }

    public void disconnect() {
        Session current = this.session;
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            current.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client requested disconnect"));
        } catch (IOException e) {
            listener.onError("Disconnect failed: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public void createSession(String userId, String title) {
        this.userId = userId;

        ObjectNode payload = mapper.createObjectNode();
        String documentName = title == null || title.isBlank() ? "Untitled Document" : title;
        payload.put("name", documentName);
        payload.put("title", documentName);

        ObjectNode root = mapper.createObjectNode();
        root.put("type", "CREATE_SESSION");
        root.put("userId", userId);
        root.set("payload", payload);

        sendNode(root);
    }

    public void joinSession(String userId, String code) {
        this.userId = userId;

        ObjectNode payload = mapper.createObjectNode();
        payload.put("code", code);

        ObjectNode root = mapper.createObjectNode();
        root.put("type", "JOIN_SESSION");
        root.put("userId", userId);
        root.set("payload", payload);

        sendNode(root);
    }

    public void deleteDocument(String docId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "DELETE_DOCUMENT");
        root.put("documentId", docId);
        sendNode(root);
    }

    public void renameDocument(String docId, String newName) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "RENAME_DOCUMENT");
        root.put("documentId", docId);
        root.put("newName", newName);
        sendNode(root);
    }

    public void sendOperation(Operation operation) {
        if (operation == null) {
            return;
        }

        operation.setSessionId(sessionId);
        if (operation.getUserId() == null) {
            operation.setUserId(userId);
        }
        operation.setTimestamp(System.currentTimeMillis());

        sendNode(operation.toNetworkMessage(mapper));
    }

    public void sendCursorUpdate(int position) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "CURSOR_UPDATE");
        root.put("sessionId", sessionId);
        root.put("userId", userId);
        root.put("position", position);

        sendNode(root);
    }

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        listener.onConnected();
    }

    @OnMessage
    public void onMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            String type = root.path("type").asText("").toUpperCase(Locale.ROOT);

            switch (type) {
                case "SESSION_CREATED":
                    handleSessionCreated(root);
                    break;
                case "SESSION_JOINED":
                    handleSessionJoined(root);
                    break;
                case "USER_LIST":
                    handleUserList(root);
                    break;
                case "USER_JOINED":
                    listener.onUserJoined(root.path("userId").asText(null));
                    break;
                case "USER_LEFT":
                    listener.onUserLeft(root.path("userId").asText(null));
                    break;
                case "CURSOR_UPDATE":
                    handleCursorUpdate(root);
                    break;
                case "MISSED_OPERATIONS":
                    handleMissedOperations(root);
                    break;
                case "ERROR":
                    handleError(root);
                    break;
                case "DOCUMENT_DELETED":
                    listener.onDocumentDeleted(root.path("documentId").asText());
                    break;
                case "DOCUMENT_RENAMED":
                    listener.onDocumentRenamed(root.path("documentId").asText(), root.path("newName").asText());
                    break;
                default:
                    Operation operation = Operation.fromNetwork(root);
                    if (operation != null) {
                        listener.onRemoteOperation(operation);
                    }
                    break;
            }
        } catch (Exception e) {
            listener.onError("Failed to parse server message: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        this.session = null;
        String reason = closeReason == null ? "Connection closed" : closeReason.getReasonPhrase();
        listener.onDisconnected(reason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        listener.onError(throwable == null ? "Unknown socket error" : throwable.getMessage());
    }

    private void handleSessionCreated(JsonNode root) {
        JsonNode payload = root.path("payload");

        this.sessionId = payload.path("sessionId").asText(root.path("sessionId").asText(null));
        this.role = payload.path("role").asText("EDITOR");

        SessionInfo info = new SessionInfo(
                sessionId,
                payload.path("documentId").asText(sessionId),
                payload.path("role").asText("EDITOR"),
                payload.path("editorCode").asText(""),
                payload.path("viewerCode").asText(""));

        listener.onSessionCreated(info);
    }

    private void handleSessionJoined(JsonNode root) {
        JsonNode payload = root.path("payload");

        this.sessionId = payload.path("sessionId").asText(root.path("sessionId").asText(null));
        this.role = payload.path("role").asText("VIEWER");

        SessionInfo info = new SessionInfo(
                sessionId,
                payload.path("documentId").asText(sessionId),
                payload.path("role").asText("VIEWER"),
                payload.path("editorCode").asText(""),
                payload.path("viewerCode").asText(""));

        listener.onSessionJoined(info);
    }

    private void handleUserList(JsonNode root) {
        JsonNode payload = root.path("payload");
        ArrayNode usersNode = payload.has("users") && payload.get("users").isArray()
                ? (ArrayNode) payload.get("users")
                : mapper.createArrayNode();

        List<UserPresence> users = new ArrayList<>();
        for (JsonNode node : usersNode) {
            users.add(new UserPresence(
                    node.path("userId").asText(),
                    node.path("role").asText("VIEWER"),
                    node.path("cursor").asInt(-1)));
        }
        listener.onActiveUsers(users);
    }

    private void handleCursorUpdate(JsonNode root) {
        String remoteUser = root.path("userId").asText(null);
        int position = root.has("position") ? root.get("position").asInt(-1) : root.path("payload").path("position").asInt(-1);
        if (remoteUser != null) {
            listener.onRemoteCursor(remoteUser, position);
        }
    }

    private void handleMissedOperations(JsonNode root) {
        JsonNode payload = root.path("payload");
        JsonNode operations = payload.path("operations");
        if (!operations.isArray()) {
            return;
        }

        for (JsonNode opNode : operations) {
            Operation operation = Operation.fromNetwork(opNode);
            if (operation != null) {
                listener.onRemoteOperation(operation);
            }
        }
    }

    private void handleError(JsonNode root) {
        String message = root.path("payload").path("message").asText(root.path("message").asText("Unknown server error"));
        listener.onError(message);
    }

    private void sendNode(JsonNode node) {
        Session current = this.session;
        if (current == null || !current.isOpen()) {
            listener.onError("Socket is not connected");
            return;
        }

        try {
            current.getAsyncRemote().sendText(mapper.writeValueAsString(node));
        } catch (Exception e) {
            listener.onError("Failed to send message: " + e.getMessage());
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public interface Listener {
        void onConnected();

        void onDisconnected(String reason);

        void onSessionCreated(SessionInfo info);

        void onSessionJoined(SessionInfo info);

        void onRemoteOperation(Operation operation);

        void onRemoteCursor(String userId, int position);

        void onActiveUsers(List<UserPresence> users);

        void onUserJoined(String userId);

        void onUserLeft(String userId);

        void onError(String message);

        void onDocumentDeleted(String documentId);

        void onDocumentRenamed(String documentId, String newName);
    }

    public record SessionInfo(String sessionId,
                              String documentId,
                              String role,
                              String editorCode,
                              String viewerCode) {
    }

    public record UserPresence(String userId, String role, int cursorPosition) {
    }
}
