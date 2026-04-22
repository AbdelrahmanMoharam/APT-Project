package server;

import CRDT.Document;
import CRDT.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/collab/{documentId}")
public class Server {

    private static final ObjectMapper mapper = new ObjectMapper();

    // Key: documentId, Value: The actual live CRDT Document object
    private static final Map<String, Document> liveDocuments = new ConcurrentHashMap<>();

    // Key: documentId, Value: Set of connected WebSocker sessions
    private static final Map<String, Set<Session>> rooms = new ConcurrentHashMap<>();

    // Key: SessionID, Value: UserID (to handle disconnects)
    private static final Map<String, String> sessionToUser = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("documentId") String documentId) {
        rooms.computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet()).add(session);
        System.out.println("Session opened: " + session.getId() + " for doc: " + documentId);
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("documentId") String documentId) {
        try {
            JsonNode root = mapper.readTree(message);
            String type = root.get("type").asText();

            // 1. Handle JOIN: Send the new user the CURRENT state of the CRDT
            if ("JOIN".equals(type)) {
                String userId = root.get("userId").asText();
                sessionToUser.put(session.getId(), userId);

                // Initialize document if it's the first person
                Document doc = liveDocuments.computeIfAbsent(documentId,
                        id -> new Document("Server", id, "Shared Doc"));

                // Send the FULL document state only to the person who just joined
                // We wrap the document object in a "SYNC_STATE" type
                String syncState = mapper.writeValueAsString(Map.of(
                        "type", "SYNC_STATE",
                        "payload", doc
                ));
                session.getAsyncRemote().sendText(syncState);

                // Notify others that a user joined
                broadcast(documentId, session, mapper.writeValueAsString(Map.of(
                        "type", "USER_JOINED", "userId", userId
                )));
            }

            // 2. Handle EDITS: Apply to the server's copy AND relay to others
            else if (type.contains("BLOCK") || type.contains("CHAR")) {
                Document doc = liveDocuments.get(documentId);
                if (doc != null && root.has("op")) {
                    // Convert JSON 'op' back into a Java Operation object
                    Operation op = mapper.treeToValue(root.get("op"), Operation.class);
                    // Apply to the server-side CRDT to keep it updated
                    doc.receiveEdit(op);
                }
                // Relay the raw message to everyone else
                broadcast(documentId, session, message);
            }

            // 3. Handle CURSORS: Just relay
            else if ("CURSOR_UPDATE".equals(type)) {
                broadcast(documentId, session, message);
            }

        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("documentId") String documentId) {
        Set<Session> room = rooms.get(documentId);
        if (room != null) {
            room.remove(session);
            String userId = sessionToUser.remove(session.getId());
            if (userId != null) {
                try {
                    broadcast(documentId, null, mapper.writeValueAsString(Map.of(
                            "type", "USER_LEFT", "userId", userId
                    )));
                } catch (Exception ignored) {}
            }
        }
    }

    private void broadcast(String documentId, Session excludeSession, String message) {
        Set<Session> sessions = rooms.get(documentId);
        if (sessions != null) {
            for (Session s : sessions) {
                if (s.isOpen() && (excludeSession == null || !s.getId().equals(excludeSession.getId()))) {
                    s.getAsyncRemote().sendText(message);
                }
            }
        }
    }
}
