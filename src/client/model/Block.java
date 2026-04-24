package client.model;

import client.crdt.CharacterCRDT;

public class Block {

    private final String blockId;
    private boolean deleted;
    private final CharacterCRDT charTree;

    public Block(String blockId) {
        this.blockId = blockId;
        this.deleted = false;
        this.charTree = new CharacterCRDT();
    }

    public String getBlockId() {
        return blockId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public CharacterCRDT getCharTree() {
        return charTree;
    }
}
