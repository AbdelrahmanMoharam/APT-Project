package Client;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;

/**
 * CollabClient — the "phone line."
 *
 * Sole responsibility: open/close a WebSocket connection to the Spring Boot
 * server and ferry raw JSON strings in both directions.
 *
 * It knows NOTHING about the CRDT, the UI, or application logic.
 */
@ClientEndpoint
public class CollabClient {

    // ------------------------------------------------------------------ deps
    /** The application-logic layer that reacts to incoming messages. */
    private final SessionManager sessionManager;

    /** Converts between domain objects and JSON strings. */
    private final MessageSerializer serializer;

    // ---------------------------------------------------------------- state
    /** Live WebSocket session — null when not connected. */
    private Session wsSession;

    // ----------------------------------------------------------------- ctor
    public CollabClient(SessionManager sessionManager, MessageSerializer serializer) {
        this.sessionManager = sessionManager;
        this.serializer     = serializer;
    }

    // ============================================================= lifecycle

    /**
     * Opens a WebSocket connection to the given server URI.
     */
    public void connect(String serverUri) {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            // connect() blocks until onOpen fires or the connection fails.
            container.connectToServer(this, URI.create(serverUri));
            System.out.println("WebSocket connecting to: " + serverUri);
        } catch (DeploymentException | IOException e) {
            System.err.println("Failed to connect to WebSocket server: " + serverUri);
            e.printStackTrace();
            sessionManager.onConnectionError("Could not connect to server: " + e.getMessage());
        }
    }

    /**
     * Closes the WebSocket connection gracefully.
     */
    public void disconnect() {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.close(new CloseReason(
                        CloseReason.CloseCodes.NORMAL_CLOSURE, "User left session"));
            } catch (IOException e) {
                System.err.println("Error while closing WebSocket session");
                e.printStackTrace();
            }
        }
    }

    // ======================================================= WebSocket hooks

    @OnOpen
    public void onOpen(Session session) {
        this.wsSession = session;
        System.out.println("WebSocket connection opened. Session ID: " + session.getId());

        // Ask SessionManager for the join payload; it owns the session state.
        String joinJson = serializer.serializeJoinMessage(
                sessionManager.getDocumentId(),
                sessionManager.getUserId(),
                sessionManager.getUserRole()
        );
        sendRaw(joinJson);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("Message received: " + message);

        try {
            // Let the serializer figure out what kind of message this is.
            MessageSerializer.IncomingMessage incoming = serializer.deserialize(message);

            // We expect the incoming type to match the String names of your OpType enum,
            // plus network-specific events like CURSOR_UPDATE.
            switch (incoming.getType()) {

                // --- ALL CRDT OPERATIONS ---
                // Notice how they all fall through to the exact same method.
                // The Document class will handle the specific logic.
                case "INSERT_BLOCK":
                case "DELETE_BLOCK":
                case "SPLIT_BLOCK":
                case "MOVE_BLOCK":
                case "INSERT_CHAR":
                case "DELETE_CHAR":
                case "FORMAT_CHAR":
                case "FORMAT_RANGE":
                    sessionManager.onRemoteOperation(incoming.getOperation());
                    break;

                // --- NETWORK & SESSION OPERATIONS ---
                case "CURSOR_UPDATE":
                    sessionManager.onRemoteCursorUpdate(
                            incoming.getUserId(),
                            incoming.getCursorPosition()
                    );
                    break;

                case "USER_JOINED":
                    sessionManager.onUserJoined(incoming.getUserId());
                    break;

                case "USER_LEFT":
                    sessionManager.onUserLeft(incoming.getUserId());
                    break;
                case "SYNC_STATE": // ADD THIS CASE
                    sessionManager.syncFullDocument(incoming.getDocument());
                    break;
                default:
                    System.err.println("Received unknown message type: " + incoming.getType());
            }

        } catch (Exception e) {
            System.err.println("Failed to process incoming message: " + message);
            e.printStackTrace();
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        System.out.println("WebSocket closed: " + closeReason.getReasonPhrase()
                + " (code " + closeReason.getCloseCode() + ")");
        this.wsSession = null;
        sessionManager.onSessionClosed(closeReason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket error on session "
                + (session != null ? session.getId() : "null"));
        throwable.printStackTrace();
        sessionManager.onConnectionError(throwable.getMessage());
    }

    // ================================================== outbound public API

    public void sendOperation(Object op) {
        if (!isConnected()) {
            System.err.println("sendOperation called but WebSocket is not connected.");
            return;
        }
        String json = serializer.serializeOperation(op);
        sendRaw(json);
    }

    public void sendCursorUpdate(int position) {
        if (!isConnected()) {
            System.err.println("sendCursorUpdate called but WebSocket is not connected.");
            return;
        }
        String json = serializer.serializeCursorUpdate(
                sessionManager.getUserId(), position);
        sendRaw(json);
    }

    // ============================================================== helpers

    public boolean isConnected() {
        return wsSession != null && wsSession.isOpen();
    }

    private void sendRaw(String json) {
        if (wsSession == null || !wsSession.isOpen()) {
            System.err.println("Attempted to send message but session is not open: " + json);
            return;
        }
        try {
            wsSession.getAsyncRemote().sendText(json, result -> {
                if (!result.isOK()) {
                    System.err.println("Failed to send message: " + json);
                    result.getException().printStackTrace();
                }
            });
        } catch (Exception e) {
            System.err.println("Unexpected error while sending message");
            e.printStackTrace();
        }
    }
}