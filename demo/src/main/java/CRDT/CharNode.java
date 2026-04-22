package CRDT;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CharNode implements Comparable<CharNode> {

    private PositionID charId;
    private char value;
    private PositionID parentId;
    private boolean isDeleted;
    private Set<FormatType> formatting;

    private List<CharNode> children;

    public CharNode(PositionID charId, char value, PositionID parentId) {
        this.charId = charId;
        this.value = value;
        this.parentId = parentId;

        this.isDeleted = false;
        this.formatting = new HashSet<>();
        this.children = new ArrayList<>();
    }

    public void addChild(CharNode child) {

        int index = 0;

        while (index < children.size() &&
                children.get(index).getCharId().compareTo(child.getCharId()) < 0) {
            index++;
        }

        children.add(index, child);
    }

    public void markDeleted() { this.isDeleted = true; }

    public void toggleFormat(FormatType format) {
        if (this.formatting.contains(format)) {
            this.formatting.remove(format);
        } else {
            this.formatting.add(format);
        }
    }

    @Override
    public int compareTo(CharNode other) {
        return this.charId.compareTo(other.charId);
    }

    public PositionID getCharId() { return charId; }
    public char getValue() { return value; }
    public PositionID getParentId() { return parentId; }
    public boolean isDeleted() { return isDeleted; }
    public Set<FormatType> getFormatting() { return formatting; }
    public List<CharNode> getChildren() { return children; }

    @Override
    public String toString() {
        if (isDeleted) return "";
        return String.valueOf(value);
    }
}
