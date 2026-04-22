package CRDT;

import CRDT.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


public class MessageSerializer {


    private final ObjectMapper mapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════ Incoming (deserialize)

    public IncomingMessage deserialize(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            if (!root.has("type")) {
                throw new MessageParseException("Missing 'type' field in message: " + json);
            }

            String type = root.get("type").asText().toUpperCase();

            String userId     = root.has("userId")   ? root.get("userId").asText()   : null;
            int position      = root.has("position") ? root.get("position").asInt()  : -1;

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


    public String serializeOperation(Object op) {
        try {
            Operation crdtOp = (Operation) op;

            String opType = crdtOp.getType().name();

            ObjectNode root = mapper.createObjectNode();
            root.put("type", opType);

            root.set("op", mapper.valueToTree(crdtOp));

            return mapper.writeValueAsString(root);

        } catch (Exception e) {
            System.err.println("Failed to serialize operation: " + op);
            e.printStackTrace();
            throw new MessageSerializeException("Could not serialize operation", e);
        }
    }


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


    public static class IncomingMessage {

        private final String    type;
        private final String    userId;
        private final int       cursorPosition;
        private final Operation operation;

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


    public static class MessageParseException extends RuntimeException {
        public MessageParseException(String msg)                { super(msg);       }
        public MessageParseException(String msg, Throwable cause) { super(msg, cause); }
    }


    public static class MessageSerializeException extends RuntimeException {
        public MessageSerializeException(String msg, Throwable cause) { super(msg, cause); }
    }
}

