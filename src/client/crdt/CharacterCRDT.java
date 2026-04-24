package client.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CharacterCRDT {

    private final Map<String, Node> nodeById = new HashMap<>();
    private final Map<String, List<Node>> childrenByParent = new HashMap<>();

    public synchronized Node insert(String nodeId,
                                    String parentId,
                                    char value,
                                    boolean bold,
                                    boolean italic) {
        Node node = new Node(nodeId, value, normalizeParent(parentId), false, bold, italic);
        return insertNode(node);
    }

    public synchronized Node insertNode(Node node) {
        Node existing = nodeById.get(node.getId());
        if (existing != null) {
            return existing;
        }

        nodeById.put(node.getId(), node);

        String normalizedParent = normalizeParent(node.getParentId());
        List<Node> siblings = childrenByParent.computeIfAbsent(normalizedParent, key -> new ArrayList<>());
        insertSorted(siblings, node);

        return node;
    }

    public synchronized void tombstone(String nodeId) {
        Node node = nodeById.get(nodeId);
        if (node != null) {
            node.setDeleted(true);
        }
    }

    public synchronized void clear() {
        nodeById.clear();
        childrenByParent.clear();
    }

    public synchronized Node getNode(String nodeId) {
        return nodeById.get(nodeId);
    }

    public synchronized List<Node> getVisibleNodesInOrder() {
        List<Node> visible = new ArrayList<>();
        List<Node> roots = childrenByParent.get(Node.ROOT_PARENT);
        if (roots == null) {
            return visible;
        }

        for (Node root : roots) {
            dfsVisible(root, visible);
        }

        return visible;
    }

    public synchronized int getVisibleIndexByNodeId(String nodeId) {
        List<Node> visible = getVisibleNodesInOrder();
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).getId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized Node getVisibleNodeAt(int index) {
        List<Node> visible = getVisibleNodesInOrder();
        if (index < 0 || index >= visible.size()) {
            return null;
        }
        return visible.get(index);
    }

    public synchronized String parentIdForInsertAt(int visibleIndex) {
        if (visibleIndex <= 0) {
            return null;
        }

        List<Node> visible = getVisibleNodesInOrder();
        if (visible.isEmpty()) {
            return null;
        }

        int parentIndex = Math.min(visibleIndex - 1, visible.size() - 1);
        return visible.get(parentIndex).getId();
    }

    public synchronized String renderPlainText() {
        StringBuilder sb = new StringBuilder();
        for (Node node : getVisibleNodesInOrder()) {
            sb.append(node.getValue());
        }
        return sb.toString();
    }

    private void dfsVisible(Node node, List<Node> output) {
        if (!node.isDeleted()) {
            output.add(node);
        }

        List<Node> children = childrenByParent.get(node.getId());
        if (children == null) {
            return;
        }

        for (Node child : children) {
            dfsVisible(child, output);
        }
    }

    private void insertSorted(List<Node> siblings, Node incoming) {
        int index = 0;
        while (index < siblings.size() && siblings.get(index).compareTo(incoming) < 0) {
            index++;
        }
        siblings.add(index, incoming);
    }

    private String normalizeParent(String parentId) {
        if (parentId == null || parentId.isBlank() || Node.ROOT_PARENT.equals(parentId)) {
            return Node.ROOT_PARENT;
        }
        return parentId;
    }
}
