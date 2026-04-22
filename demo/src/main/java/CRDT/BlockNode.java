package CRDT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockNode {

    private PositionID blockId;
    private boolean isDeleted;


    private Map<PositionID, CharNode> nodeMap;

    private List<CharNode> roots;
    private Map<PositionID, List<CharNode>> pendingChildren;

    public BlockNode(PositionID blockId) {
        this.blockId = blockId;
        this.isDeleted = false;
        this.nodeMap = new HashMap<>();
        this.roots = new ArrayList<>();
        this.pendingChildren = new HashMap<>();
    }


    public void insertChar(PositionID charId, char value, PositionID parentId) {
        CharNode newNode = new CharNode(charId, value, parentId);
        nodeMap.put(charId, newNode);

        if (parentId == null) {
            roots.add(newNode);
        } else {
            CharNode parent = nodeMap.get(parentId);

            if (parent != null) {
                parent.addChild(newNode);
            } else {
                pendingChildren
                        .computeIfAbsent(parentId, k -> new ArrayList<>())
                        .add(newNode);
                return;
            }
        }
        attachPendingChildren(charId);
    }

    public void deleteChar(PositionID charId) {
        CharNode node = nodeMap.get(charId);
        if (node != null) {
            node.markDeleted();
        }
    }

    public void markDeleted() {
        this.isDeleted = true;
    }

    public PositionID getBlockId() { return blockId; }
    public boolean isDeleted() { return isDeleted; }


    @Override
    public String toString() {
        if (isDeleted) return "";
        StringBuilder sb = new StringBuilder();
        for (CharNode root : roots) {
            dfs(root, sb);
        }
        return sb.toString();
    }

    private void dfs(CharNode node, StringBuilder sb) {
        sb.append(node.toString());


        for (CharNode child : node.getChildren()) {
            dfs(child, sb);
        }
    }
    private void attachPendingChildren(PositionID parentId) {
        List<CharNode> waiting = pendingChildren.remove(parentId);

        if (waiting != null) {
            CharNode parent = nodeMap.get(parentId);

            for (CharNode child : waiting) {
                parent.addChild(child);
                attachPendingChildren(child.getCharId());
            }
        }
    }

    public Map<PositionID, CharNode> getNodeMap() {
        return nodeMap;
    }


    public List<CharNode> getRoots() {
        return roots;
    }
}