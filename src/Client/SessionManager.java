package Client;

import CRDT.Document;
import CRDT.Operation;
import ui.EditorUI;

import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager — the "brain" of the client side.
 * Sits directly between the Swing UI, the CRDT Document, and the Network.
 */
public class SessionManager {

    // ── server base URI template ──────────────────────────────────────────────
    private static final String WS_BASE_URI = "ws://localhost:8080/collab";

    // ═════════════════════════════════════════════════════════════════ state ══

    private volatile String documentId;
    private volatile String userId;
    private volatile String userRole;

    private volatile CollabClient collabClient;

    // Hooked directly into your CRDT Engine
    private Document crdtDocument;
    private final Object crdtLock = new Object();

    private final Map<String, Integer> remoteCursorPositions = new ConcurrentHashMap<>();
    private final Set<String> activeUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private volatile boolean sessionActive = false;

    // ═══════════════════════════════════════════════════════════ dependencies ══

    // Injected via constructor — SessionManager does not own the window lifecycle.
    private final EditorUI editorUI;
    private final MessageSerializer serializer;

    // ════════════════════════════════════════════════════════════ constructor ══

    /**
     * Creates the SessionManager wired to an already-constructed EditorUI window.
     */
    public SessionManager(EditorUI editorUI, MessageSerializer serializer) {
        if (editorUI   == null) throw new IllegalArgumentException("editorUI must not be null");
        if (serializer == null) throw new IllegalArgumentException("serializer must not be null");
        this.editorUI   = editorUI;
        this.serializer = serializer;
    }

    // ═══════════════════════════════════════════════════════════ session API ══

    public void joinSession(String documentId, String userId, String role) {
        if (sessionActive) {
            System.err.println("joinSession called while a session is already active — leaving first.");
            leaveSession();
        }

        this.documentId = documentId;
        this.userId     = userId;
        this.userRole   = role;

        synchronized (crdtLock) {
            crdtDocument = createEmptyCrdtDocument();
        }

        remoteCursorPositions.clear();
        activeUsers.clear();

        collabClient  = new CollabClient(this, serializer);
        sessionActive = true;

        String serverUri = WS_BASE_URI + "/" + documentId;
        System.out.println("Joining session: documentId=" + documentId
                + ", userId=" + userId + ", role=" + role
                + ", server=" + serverUri);

        collabClient.connect(serverUri);
    }

    public void leaveSession() {
        if (!sessionActive) return;

        System.out.println("Leaving session: documentId=" + documentId + ", userId=" + userId);
        sessionActive = false;

        if (collabClient != null) {
            collabClient.disconnect();
            collabClient = null;
        }

        clearSessionState();
    }

    // ═══════════════════════════════════════════════════════ local edit hooks ══

    /**
     * Called by the UI whenever the local user makes an edit.
     */
    public void onLocalEdit(Operation op) {
        if (!sessionActive) {
            System.err.println("onLocalEdit called but no session is active.");
            return;
        }
        if (!"editor".equalsIgnoreCase(userRole)) {
            System.out.println("onLocalEdit ignored: user " + userId + " is a viewer.");
            return;
        }

        String updatedContent;
        synchronized (crdtLock) {
            applyCrdtOperation(crdtDocument, op);
            updatedContent = getCrdtContent(crdtDocument);
        }

        // Optimistic UI update — if called from the UI, invokeLater isn't strictly needed,
        // but it is completely safe to ensure it stays on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> editorUI.onDocumentChanged(updatedContent));

        if (collabClient != null && collabClient.isConnected()) {
            collabClient.sendOperation(op);
        } else {
            System.err.println("onLocalEdit: CollabClient is not connected — operation not sent.");
        }
    }

    public void onLocalCursorMove(int position) {
        if (!sessionActive) return;

        if (collabClient != null && collabClient.isConnected()) {
            collabClient.sendCursorUpdate(position);
        }
    }

    // ══════════════════════════════════════════════════ remote event callbacks ══

    public void onRemoteOperation(Operation op) {
        if (op == null) return;

        String updatedContent;
        synchronized (crdtLock) {
            applyCrdtOperation(crdtDocument, op);
            updatedContent = getCrdtContent(crdtDocument);
        }

        // Network threads MUST hand UI updates back to the Swing Event Dispatch Thread
        final String content = updatedContent;
        SwingUtilities.invokeLater(() -> editorUI.onDocumentChanged(content));

        System.out.println("Remote operation applied. Length: " + content.length());
    }

    public void onRemoteCursorUpdate(String remoteUserId, int position) {
        if (remoteUserId == null) return;

        if (position < 0) {
            remoteCursorPositions.remove(remoteUserId);
        } else {
            remoteCursorPositions.put(remoteUserId, position);
        }

        SwingUtilities.invokeLater(() ->
                editorUI.onCursorsUpdated(Collections.unmodifiableMap(remoteCursorPositions)));
    }

    public void onUserJoined(String joinedUserId) {
        if (joinedUserId == null || joinedUserId.equals(userId)) return;

        if (activeUsers.add(joinedUserId)) {
            System.out.println("User joined: " + joinedUserId);
            SwingUtilities.invokeLater(() ->
                    editorUI.onActiveUsersUpdated(Collections.unmodifiableSet(activeUsers)));
        }
    }

    public void onUserLeft(String leftUserId) {
        if (leftUserId == null) return;

        boolean removed = activeUsers.remove(leftUserId);
        remoteCursorPositions.remove(leftUserId);

        if (removed) {
            System.out.println("User left: " + leftUserId);
            SwingUtilities.invokeLater(() -> {
                editorUI.onActiveUsersUpdated(Collections.unmodifiableSet(activeUsers));
                editorUI.onCursorsUpdated(Collections.unmodifiableMap(remoteCursorPositions));
            });
        }
    }

    // ═══════════════════════════════════════════════════ connection callbacks ══

    public void onSessionClosed(String reason) {
        System.out.println("Session closed. Reason: " + reason);
        sessionActive = false;
        clearSessionState();
        final String msg = reason != null ? reason : "Connection closed";
        SwingUtilities.invokeLater(() -> editorUI.onSessionEnded(msg));
    }

    public void onConnectionError(String errorMessage) {
        System.err.println("Connection error: " + errorMessage);
        SwingUtilities.invokeLater(() ->
                editorUI.onError("Connection error: " + errorMessage));
    }

    // ═══════════════════════════════════════════════════════════════ getters ══

    public String getDocumentId() { return documentId; }
    public String getUserId()     { return userId;     }
    public String getUserRole()   { return userRole;   }
    public boolean isSessionActive() { return sessionActive; }

    public Map<String, Integer> getRemoteCursorPositions() {
        return Collections.unmodifiableMap(remoteCursorPositions);
    }

    public Set<String> getActiveUsers() {
        return Collections.unmodifiableSet(activeUsers);
    }

    // ════════════════════════════════════════════════════════════════ helpers ══

    private void clearSessionState() {
        remoteCursorPositions.clear();
        activeUsers.clear();
        synchronized (crdtLock) {
            crdtDocument = null;
        }
        documentId = null;
        userId     = null;
        userRole   = null;
    }

    // ═══════════════════════════════════════════════════ CRDT bridge ══

    private Document createEmptyCrdtDocument() {
        System.out.println("Creating empty CRDT document for user: " + userId);
        return new Document(userId, documentId, "Shared Document");
    }

    private void applyCrdtOperation(Document doc, Operation op) {
        doc.receiveEdit(op);
    }

    private String getCrdtContent(Document doc) {
        return doc.renderText();
    }
}