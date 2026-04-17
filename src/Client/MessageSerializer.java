package Client;

import CRDT.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

            // "type" is mandatory on every server frame.
            if (!root.has("type")) {
                throw new MessageParseException("Missing 'type' field in message: " + json);
            }

            // We treat the type strictly as a String to match your Operation.OpType enum
            // AND network events (like CURSOR_UPDATE)
            String type = root.get("type").asText().toUpperCase();

            String userId     = root.has("userId")   ? root.get("userId").asText()   : null;
            int position      = root.has("position") ? root.get("position").asInt()  : -1;

            // If the message has an "op" payload, ask Jackson to turn it directly
            // into your CRDT.Operation class!
            Operation operation = null;
            if (root.has("op")) {
                operation = mapper.treeToValue(root.get("op"), Operation.class);
            }

            return new IncomingMessage(type, userId, position, operation);

        } catch (MessageParseException e) {
            throw e; // re-throw as-is
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

        private final String    type; // Changed to String to match your OpType enum natively
        private final String    userId;
        private final int       cursorPosition;
        private final Operation operation; // Strictly uses your CRDT.Operation

        public IncomingMessage(String type, String userId, int cursorPosition, Operation operation) {
            this.type           = type;
            this.userId         = userId;
            this.cursorPosition = cursorPosition;
            this.operation      = operation;
        }

        public String    getType()           { return type;           }
        public String    getUserId()         { return userId;         }
        public int       getCursorPosition() { return cursorPosition; }
        public Operation getOperation()      { return operation;      }
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