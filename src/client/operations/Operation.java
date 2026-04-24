package client.operations;

import client.model.Document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

public abstract class Operation {

    private String sessionId;
    private String userId;
    private long timestamp;

    protected Operation(String sessionId, String userId, long timestamp) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.timestamp = timestamp;
    }

    public abstract String getType();

    public abstract void apply(Document document);

    public abstract Operation getInverse();

    protected abstract void writeOperationPayload(ObjectNode opNode);

    public ObjectNode toNetworkMessage(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", getType());

        if (sessionId != null) {
            root.put("sessionId", sessionId);
        }
        if (userId != null) {
            root.put("userId", userId);
        }
        root.put("timestamp", timestamp);

        ObjectNode opNode = mapper.createObjectNode();
        writeOperationPayload(opNode);
        root.set("op", opNode);

        return root;
    }

    public static Operation fromNetwork(JsonNode rootNode) {
        if (rootNode == null || !rootNode.hasNonNull("type")) {
            return null;
        }

        String type = rootNode.get("type").asText().toUpperCase(Locale.ROOT);
        String sessionId = readText(rootNode, "sessionId");
        String userId = readText(rootNode, "userId");
        long timestamp = rootNode.has("timestamp")
                ? rootNode.get("timestamp").asLong()
                : System.currentTimeMillis();

        JsonNode payload = rootNode.has("op") ? rootNode.get("op") : rootNode.get("payload");

        switch (type) {
            case "INSERT_CHAR":
                return InsertOp.fromPayload(sessionId, userId, timestamp, payload);
            case "DELETE_CHAR":
                return DeleteOp.fromPayload(sessionId, userId, timestamp, payload);
            case "FORMAT":
            case "FORMAT_CHAR":
            case "FORMAT_RANGE":
                int start = readInt(payload, 0, "rangeStart", "start");
                int end = readInt(payload, start, "rangeEnd", "end");
                boolean toggleBold = readBoolean(payload, false, "bold", "toggleBold");
                boolean toggleItalic = readBoolean(payload, false, "italic", "toggleItalic");
                return new FormatOp(sessionId, userId, timestamp, start, end, toggleBold, toggleItalic);

            case "INSERT_BLOCK":
                return BlockOp.insert(
                        sessionId,
                        userId,
                        timestamp,
                        readText(payload, "targetBlockId", "blockId"),
                        readInt(payload, Integer.MAX_VALUE, "targetIndex", "index"));

            case "DELETE_BLOCK":
                return BlockOp.delete(
                        sessionId,
                        userId,
                        timestamp,
                        readText(payload, "targetBlockId", "blockId"),
                        readInt(payload, 0, "targetIndex", "index"));

            case "SPLIT_BLOCK":
                return BlockOp.split(
                        sessionId,
                        userId,
                        timestamp,
                        readText(payload, "targetBlockId", "blockId"),
                        readInt(payload, 0, "splitIndex", "index"),
                        readText(payload, "newBlockId"));

            case "MOVE_BLOCK":
                return BlockOp.move(
                        sessionId,
                        userId,
                        timestamp,
                        readText(payload, "targetBlockId", "blockId"),
                        readInt(payload, 0, "targetIndex", "index"),
                        readInt(payload, -1, "previousIndex"));

            default:
                return null;
        }
    }

    protected static String readText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            if (node.hasNonNull(key)) {
                JsonNode value = node.get(key);
                if (value.isTextual()) {
                    return value.asText();
                }
                return value.toString();
            }
        }
        return null;
    }

    protected static int readInt(JsonNode node, int defaultValue, String... keys) {
        if (node == null) {
            return defaultValue;
        }
        for (String key : keys) {
            if (node.has(key)) {
                return node.get(key).asInt(defaultValue);
            }
        }
        return defaultValue;
    }

    protected static boolean readBoolean(JsonNode node, boolean defaultValue, String... keys) {
        if (node == null) {
            return defaultValue;
        }
        for (String key : keys) {
            if (!node.has(key)) {
                continue;
            }

            JsonNode value = node.get(key);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isInt()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String text = value.asText();
                return "1".equals(text) || "true".equalsIgnoreCase(text);
            }
        }
        return defaultValue;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public static final class FormatOp extends Operation {

        private final int start;
        private final int endExclusive;
        private final boolean toggleBold;
        private final boolean toggleItalic;

        public FormatOp(String sessionId,
                        String userId,
                        long timestamp,
                        int start,
                        int endExclusive,
                        boolean toggleBold,
                        boolean toggleItalic) {
            super(sessionId, userId, timestamp);
            this.start = start;
            this.endExclusive = endExclusive;
            this.toggleBold = toggleBold;
            this.toggleItalic = toggleItalic;
        }

        @Override
        public String getType() {
            return "FORMAT";
        }

        @Override
        public void apply(Document document) {
            if (document == null) {
                return;
            }
            document.toggleFormatRange(start, endExclusive, toggleBold, toggleItalic);
        }

        @Override
        public Operation getInverse() {
            // Toggle is self-inverse.
            return new FormatOp(
                    getSessionId(),
                    getUserId(),
                    System.currentTimeMillis(),
                    start,
                    endExclusive,
                    toggleBold,
                    toggleItalic);
        }

        @Override
        protected void writeOperationPayload(ObjectNode opNode) {
            opNode.put("rangeStart", start);
            opNode.put("rangeEnd", endExclusive);
            opNode.put("bold", toggleBold);
            opNode.put("italic", toggleItalic);
        }
    }

    public static final class BlockOp extends Operation {

        public enum Action {
            INSERT_BLOCK,
            DELETE_BLOCK,
            SPLIT_BLOCK,
            MOVE_BLOCK
        }

        private final Action action;
        private final String blockId;
        private final int targetIndex;
        private final int previousIndex;
        private final int splitIndex;
        private final String newBlockId;

        private BlockOp(String sessionId,
                        String userId,
                        long timestamp,
                        Action action,
                        String blockId,
                        int targetIndex,
                        int previousIndex,
                        int splitIndex,
                        String newBlockId) {
            super(sessionId, userId, timestamp);
            this.action = action;
            this.blockId = blockId;
            this.targetIndex = targetIndex;
            this.previousIndex = previousIndex;
            this.splitIndex = splitIndex;
            this.newBlockId = newBlockId;
        }

        public static BlockOp insert(String sessionId, String userId, long timestamp, String blockId, int targetIndex) {
            return new BlockOp(sessionId, userId, timestamp, Action.INSERT_BLOCK, blockId, targetIndex, -1, -1, null);
        }

        public static BlockOp delete(String sessionId, String userId, long timestamp, String blockId, int targetIndex) {
            return new BlockOp(sessionId, userId, timestamp, Action.DELETE_BLOCK, blockId, targetIndex, -1, -1, null);
        }

        public static BlockOp split(String sessionId,
                                    String userId,
                                    long timestamp,
                                    String blockId,
                                    int splitIndex,
                                    String newBlockId) {
            return new BlockOp(sessionId, userId, timestamp, Action.SPLIT_BLOCK, blockId, -1, -1, splitIndex, newBlockId);
        }

        public static BlockOp move(String sessionId,
                                   String userId,
                                   long timestamp,
                                   String blockId,
                                   int targetIndex,
                                   int previousIndex) {
            return new BlockOp(sessionId, userId, timestamp, Action.MOVE_BLOCK, blockId, targetIndex, previousIndex, -1, null);
        }

        @Override
        public String getType() {
            return action.name();
        }

        @Override
        public void apply(Document document) {
            if (document == null || blockId == null) {
                return;
            }

            switch (action) {
                case INSERT_BLOCK:
                    document.getBlockCRDT().insertBlock(blockId, targetIndex);
                    break;
                case DELETE_BLOCK:
                    document.getBlockCRDT().deleteBlock(blockId);
                    break;
                case SPLIT_BLOCK:
                    if (newBlockId != null) {
                        document.getBlockCRDT().splitBlock(blockId, splitIndex, newBlockId);
                    }
                    break;
                case MOVE_BLOCK:
                    document.getBlockCRDT().moveBlock(blockId, targetIndex);
                    break;
                default:
                    break;
            }
        }

        @Override
        public Operation getInverse() {
            switch (action) {
                case INSERT_BLOCK:
                    return delete(getSessionId(), getUserId(), System.currentTimeMillis(), blockId, targetIndex);
                case DELETE_BLOCK:
                    return insert(getSessionId(), getUserId(), System.currentTimeMillis(), blockId, targetIndex);
                case SPLIT_BLOCK:
                    if (newBlockId == null) {
                        return null;
                    }
                    return delete(getSessionId(), getUserId(), System.currentTimeMillis(), newBlockId, targetIndex);
                case MOVE_BLOCK:
                    if (previousIndex < 0) {
                        return null;
                    }
                    return move(
                            getSessionId(),
                            getUserId(),
                            System.currentTimeMillis(),
                            blockId,
                            previousIndex,
                            targetIndex);
                default:
                    return null;
            }
        }

        @Override
        protected void writeOperationPayload(ObjectNode opNode) {
            opNode.put("targetBlockId", blockId);
            if (targetIndex >= 0 && targetIndex < Integer.MAX_VALUE) {
                opNode.put("targetIndex", targetIndex);
            }
            if (previousIndex >= 0) {
                opNode.put("previousIndex", previousIndex);
            }
            if (splitIndex >= 0) {
                opNode.put("splitIndex", splitIndex);
            }
            if (newBlockId != null) {
                opNode.put("newBlockId", newBlockId);
            }
        }
    }

    public static final class CursorOp extends Operation {

        private final int position;

        public CursorOp(String sessionId, String userId, long timestamp, int position) {
            super(sessionId, userId, timestamp);
            this.position = position;
        }

        @Override
        public String getType() {
            return "CURSOR_UPDATE";
        }

        @Override
        public void apply(Document document) {
            // Cursor operations do not mutate document state.
        }

        @Override
        public Operation getInverse() {
            return new CursorOp(getSessionId(), getUserId(), System.currentTimeMillis(), position);
        }

        @Override
        protected void writeOperationPayload(ObjectNode opNode) {
            opNode.put("position", position);
        }

        public int getPosition() {
            return position;
        }
    }
}
