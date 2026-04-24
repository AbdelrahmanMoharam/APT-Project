package server.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Block {

    private String blockId;
    private List<JsonNode> crdtOperations = new ArrayList<>();
    private long updatedAt;

    public Block() {
    }

    public Block(String blockId) {
        this.blockId = blockId;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void addOperation(JsonNode operation) {
        if (operation == null) {
            return;
        }
        crdtOperations.add(operation.deepCopy());
        updatedAt = System.currentTimeMillis();
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public List<JsonNode> getCrdtOperations() {
        return Collections.unmodifiableList(crdtOperations);
    }

    public void setCrdtOperations(List<JsonNode> crdtOperations) {
        this.crdtOperations = crdtOperations == null ? new ArrayList<>() : new ArrayList<>(crdtOperations);
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
