package client.crdt;

import client.model.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockCRDT {

    public record CharacterAtom(String nodeId, char value, boolean bold, boolean italic) {
    }

    private final Map<String, Block> blockById = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    public BlockCRDT() {
        insertBlock("block-0", 0);
    }

    public synchronized Block insertBlock(String blockId, int index) {
        Block existing = blockById.get(blockId);
        if (existing != null) {
            existing.setDeleted(false);
            if (!order.contains(blockId)) {
                order.add(clampIndex(index), blockId);
            }
            return existing;
        }

        Block block = new Block(blockId);
        blockById.put(blockId, block);
        order.add(clampIndex(index), blockId);
        return block;
    }

    public synchronized void deleteBlock(String blockId) {
        Block block = blockById.get(blockId);
        if (block == null) {
            return;
        }

        block.setDeleted(true);
        order.remove(blockId);

        if (order.isEmpty()) {
            insertBlock("block-0", 0);
        }
    }

    public synchronized void moveBlock(String blockId, int targetIndex) {
        if (!order.remove(blockId)) {
            return;
        }
        order.add(clampIndex(targetIndex), blockId);
    }

    public synchronized Block copyBlock(String sourceBlockId, String newBlockId, int targetIndex) {
        Block source = blockById.get(sourceBlockId);
        if (source == null || source.isDeleted() || newBlockId == null) {
            return null;
        }

        Block copy = insertBlock(newBlockId, targetIndex);
        copy.getCharTree().clear();

        List<Node> sourceNodes = source.getCharTree().getVisibleNodesInOrder();
        String parentId = null;
        for (Node node : sourceNodes) {
            copy.getCharTree().insert(node.getId(), parentId, node.getValue(), node.isBold(), node.isItalic());
            parentId = node.getId();
        }

        return copy;
    }

    public synchronized void replaceBlockContent(String blockId, List<CharacterAtom> content) {
        Block target = getOrCreateBlock(blockId);
        target.getCharTree().clear();

        appendBlockContent(blockId, content);
    }

    public synchronized void appendBlockContent(String blockId, List<CharacterAtom> content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        Block target = getOrCreateBlock(blockId);
        List<Node> existingVisible = target.getCharTree().getVisibleNodesInOrder();
        String parentId = existingVisible.isEmpty() ? null : existingVisible.get(existingVisible.size() - 1).getId();

        for (CharacterAtom atom : content) {
            if (atom == null || atom.nodeId() == null) {
                continue;
            }

            target.getCharTree().insert(
                    atom.nodeId(),
                    parentId,
                    atom.value(),
                    atom.bold(),
                    atom.italic());
            parentId = atom.nodeId();
        }
    }

    public synchronized Block splitBlock(String sourceBlockId, int splitVisibleIndex, String newBlockId) {
        Block source = blockById.get(sourceBlockId);
        if (source == null || source.isDeleted()) {
            return null;
        }

        int sourceIndex = order.indexOf(sourceBlockId);
        Block newBlock = insertBlock(newBlockId, sourceIndex + 1);

        List<Node> visible = source.getCharTree().getVisibleNodesInOrder();
        if (splitVisibleIndex < 0 || splitVisibleIndex >= visible.size()) {
            return newBlock;
        }

        for (int i = splitVisibleIndex; i < visible.size(); i++) {
            Node node = visible.get(i);
            source.getCharTree().tombstone(node.getId());
            newBlock.getCharTree().insert(node.getId(), null, node.getValue(), node.isBold(), node.isItalic());
        }

        return newBlock;
    }

    public synchronized Block getBlock(String blockId) {
        return blockById.get(blockId);
    }

    public synchronized Block getOrCreateBlock(String blockId) {
        Block block = blockById.get(blockId);
        if (block != null) {
            return block;
        }
        return insertBlock(blockId, order.size());
    }

    public synchronized Block getPrimaryBlock() {
        List<Block> visible = getVisibleBlocks();
        if (!visible.isEmpty()) {
            return visible.get(0);
        }
        return insertBlock("block-0", 0);
    }

    public synchronized int getVisibleIndex(String blockId) {
        return order.indexOf(blockId);
    }

    public synchronized List<Block> getVisibleBlocks() {
        List<Block> visibleBlocks = new ArrayList<>();
        for (String blockId : order) {
            Block block = blockById.get(blockId);
            if (block != null && !block.isDeleted()) {
                visibleBlocks.add(block);
            }
        }
        return visibleBlocks;
    }

    public synchronized void clear() {
        blockById.clear();
        order.clear();
        insertBlock("block-0", 0);
    }

    private int clampIndex(int requestedIndex) {
        if (requestedIndex < 0) {
            return 0;
        }
        if (requestedIndex > order.size()) {
            return order.size();
        }
        return requestedIndex;
    }
}
