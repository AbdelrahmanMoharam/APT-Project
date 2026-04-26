package server.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Document {

    private String documentId;
    private String name;
    private Map<String, Block> blocks = new HashMap<>();
    private List<JsonNode> operationLog = new ArrayList<>();
    private long updatedAt;

    public Document() {
    }

    public Document(String documentId, String name) {
        this.documentId = documentId;
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void addOperation(JsonNode fullOperationMessage) {
        if (fullOperationMessage == null) {
            return;
        }

        JsonNode copy = fullOperationMessage.deepCopy();
        operationLog.add(copy);

        String blockId = resolveBlockId(copy);
        blocks.computeIfAbsent(blockId, Block::new).addOperation(copy);

        updatedAt = System.currentTimeMillis();
    }

    private String resolveBlockId(JsonNode operationMessage) {
        if (operationMessage == null || operationMessage.isNull()) {
            return "GLOBAL";
        }

        JsonNode opNode = operationMessage.get("op");
        if (opNode != null && opNode.has("targetBlockId")) {
            return normalizeBlockId(opNode.get("targetBlockId"));
        }

        JsonNode payloadNode = operationMessage.get("payload");
        if (payloadNode != null && payloadNode.has("targetBlockId")) {
            return normalizeBlockId(payloadNode.get("targetBlockId"));
        }

        if (payloadNode != null && payloadNode.has("blockId")) {
            return normalizeBlockId(payloadNode.get("blockId"));
        }

        return "GLOBAL";
    }

    private String normalizeBlockId(JsonNode rawBlockId) {
        if (rawBlockId == null || rawBlockId.isNull()) {
            return "GLOBAL";
        }
        if (rawBlockId.isTextual()) {
            return rawBlockId.asText();
        }
        return rawBlockId.toString();
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return getName();
    }

    public void setTitle(String title) {
        setName(title);
    }

    public Map<String, Block> getBlocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public void setBlocks(Map<String, Block> blocks) {
        this.blocks = blocks == null ? new HashMap<>() : new HashMap<>(blocks);
    }

    public List<JsonNode> getOperationLog() {
        return Collections.unmodifiableList(operationLog);
    }

    public void setOperationLog(List<JsonNode> operationLog) {
        this.operationLog = operationLog == null ? new ArrayList<>() : new ArrayList<>(operationLog);
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
