package Client;

import CRDT.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import CRDT.Document;
/**
 * MessageSerializer — the "translator."
 *
 * Sole responsibility: convert between Java objects and the JSON strings that
 * travel over the WebSocket to/from the Spring Boot server.
 *
 * It knows nothing about the network (CollabClient's job) and nothing about
 * application logic (SessionManager's job). It is a pure data-transformation utility.
 */
public class MessageSerializer {

    /** Jackson mapper — thread-safe and reusable. */
    private final ObjectMapper mapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════ Incoming (deserialize)

    /**
     * Parses a raw JSON string received from the server into a structured
     * IncomingMessage that CollabClient.onMessage can dispatch on.
     */
    public IncomingMessage deserialize(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            if (!root.has("type")) {
                throw new MessageParseException("Missing 'type' field in message: " + json);
            }
            String type = root.get("type").asText().toUpperCase();
            String userId = root.has("userId") ? root.get("userId").asText() : null;
            int position = root.has("position") ? root.get("position").asInt() : -1;

            Operation operation = null;
            if (root.has("op")) {
                operation = mapper.treeToValue(root.get("op"), Operation.class);
            }

            // ════════ ADD THIS PART ════════
            CRDT.Document document = null;
            if (root.has("payload")) {
                document = mapper.treeToValue(root.get("payload"), CRDT.Document.class);
            }
            // ═══════════════════════════════

            // Update the return to include the document
            return new IncomingMessage(type, userId, position, operation, document);

        } catch (Exception e) {
            System.err.println("Failed to deserialize message: " + json);
            e.printStackTrace();
            throw new MessageParseException("Failed to deserialize message: " + json, e);
        }
    }

    // ═══════════════════════════════════════════════════ Outgoing (serialize)

    /**
     * Builds the JSON "join" frame that the client sends to the server.
     */
    public String serializeJoinMessage(String documentId, String userId, String role) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type",       "JOIN");
            node.put("documentId", documentId);
            node.put("userId",     userId);
            node.put("role",       role);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            System.err.println("Failed to serialize JOIN message");
            e.printStackTrace();
            throw new MessageSerializeException("Could not serialize join message", e);
        }
    }

    /**
     * Converts your CRDT.Operation object into the JSON frame sent to the server.
     */
    public String serializeOperation(Object op) {
        try {
            // Cast the object to your specific CRDT Operation class
            Operation crdtOp = (Operation) op;

            // Extract the exact type (e.g., "INSERT_CHAR", "DELETE_BLOCK")
            String opType = crdtOp.getType().name();

            ObjectNode root = mapper.createObjectNode();
            root.put("type", opType);

            // Nest the full CRDT payload under "op"
            root.set("op", mapper.valueToTree(crdtOp));

            return mapper.writeValueAsString(root);

        } catch (Exception e) {
            System.err.println("Failed to serialize operation: " + op);
            e.printStackTrace();
            throw new MessageSerializeException("Could not serialize operation", e);
        }
    }

    /**
     * Builds the JSON cursor-update frame sent whenever the local user's cursor moves.
     */
    public String serializeCursorUpdate(String userId, int position) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type",     "CURSOR_UPDATE");
            node.put("userId",   userId);
            node.put("position", position);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            System.err.println("Failed to serialize cursor update");
            e.printStackTrace();
            throw new MessageSerializeException("Could not serialize cursor update", e);
        }
    }

    // ══════════════════════════════════════════════════════════ inner types

    /**
     * Parsed representation of a server-to-client message.
     */
    public static class IncomingMessage {

        private final String    type;
        private final String    userId;
        private final int       cursorPosition;
        private final Operation operation;
        private final CRDT.Document document; // 1. Added this field

        // 2. Updated this constructor to take 5 arguments
        public IncomingMessage(String type, String userId, int cursorPosition, Operation operation, CRDT.Document document) {
            this.type           = type;
            this.userId         = userId;
            this.cursorPosition = cursorPosition;
            this.operation      = operation;
            this.document       = document; // 3. Set the field
        }

        public String        getType()           { return type;           }
        public String        getUserId()         { return userId;         }
        public int           getCursorPosition() { return cursorPosition; }
        public Operation     getOperation()      { return operation;      }
        public CRDT.Document getDocument()       { return document;       } // 4. Add this getter
    }

    // ══════════════════════════════════════════════════════ checked exceptions

    /** Thrown when an incoming JSON frame cannot be parsed. */
    public static class MessageParseException extends RuntimeException {
        public MessageParseException(String msg)                { super(msg);       }
        public MessageParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** Thrown when an outgoing object cannot be serialized to JSON. */
    public static class MessageSerializeException extends RuntimeException {
        public MessageSerializeException(String msg, Throwable cause) { super(msg, cause); }
    }
}