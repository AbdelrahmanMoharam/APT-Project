package client.operations;

import client.model.Block;
import client.model.Document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class DeleteOp extends Operation {

    private final String blockId;
    private final String nodeId;
    private final String parentId;
    private final char value;
    private final boolean bold;
    private final boolean italic;

    public DeleteOp(String sessionId,
                    String userId,
                    long timestamp,
                    String blockId,
                    String nodeId,
                    String parentId,
                    char value,
                    boolean bold,
                    boolean italic) {
        super(sessionId, userId, timestamp);
        this.blockId = blockId;
        this.nodeId = nodeId;
        this.parentId = parentId;
        this.value = value;
        this.bold = bold;
        this.italic = italic;
    }

    @Override
    public String getType() {
        return "DELETE_CHAR";
    }

    @Override
    public void apply(Document document) {
        if (document == null || blockId == null || nodeId == null) {
            return;
        }

        Block block = document.getBlockCRDT().getBlock(blockId);
        if (block == null) {
            return;
        }
        block.getCharTree().tombstone(nodeId);
    }

    @Override
    public Operation getInverse() {
        return new InsertOp(
                getSessionId(),
                getUserId(),
                System.currentTimeMillis(),
                blockId,
                nodeId,
                parentId,
                value,
                bold,
                italic);
    }

    @Override
    protected void writeOperationPayload(ObjectNode opNode) {
        opNode.put("targetBlockId", blockId);
        opNode.put("targetCharId", nodeId);
        if (parentId != null) {
            opNode.put("parentCharId", parentId);
        }
        if (value != '\0') {
            opNode.put("value", String.valueOf(value));
        }
        opNode.put("bold", bold);
        opNode.put("italic", italic);
    }

    public static DeleteOp fromPayload(String sessionId, String userId, long timestamp, JsonNode payload) {
        String blockId = readText(payload, "targetBlockId", "blockId");
        String nodeId = readText(payload, "targetCharId", "charId", "nodeId");
        String parentId = readText(payload, "parentCharId", "parentId");

        String valueAsText = readText(payload, "value");
        char value = valueAsText == null || valueAsText.isEmpty() ? '\0' : valueAsText.charAt(0);

        boolean bold = readBoolean(payload, false, "bold");
        boolean italic = readBoolean(payload, false, "italic");

        return new DeleteOp(sessionId, userId, timestamp, blockId, nodeId, parentId, value, bold, italic);
    }

    public String getBlockId() {
        return blockId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getParentId() {
        return parentId;
    }

    public char getValue() {
        return value;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isItalic() {
        return italic;
    }
}
