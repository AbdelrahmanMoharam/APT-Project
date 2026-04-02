package CRDT;
import java.util.SortedMap;
import java.util.TreeMap;
public class BlockNode {

    private PositionID blockId;
    private boolean isDeleted;
    private TreeMap<PositionID, CharNode> characters;


    public BlockNode(PositionID blockId) {
        this.blockId = blockId;
        this.isDeleted = false;
        this.characters = new TreeMap<>();
    }


    public void insertChar(PositionID charId, char value) {
        CharNode newNode = new CharNode(charId, value);
        characters.put(charId, newNode);
    }

    public void deleteChar(PositionID charId) {
        CharNode node = characters.get(charId);
        if (node != null) {
            node.markDeleted();
        }
    }


    public BlockNode splitAt(PositionID splitCharId, PositionID newBlockId) {
        BlockNode newBlock = new BlockNode(newBlockId);

        SortedMap<PositionID, CharNode> tail = characters.tailMap(splitCharId);

        newBlock.characters.putAll(tail);

        tail.clear();

        return newBlock;
    }


    public void markDeleted() {
        this.isDeleted = true;
    }


    public PositionID getBlockId() { return blockId; }
    public boolean isDeleted() { return isDeleted; }
    public TreeMap<PositionID, CharNode> getCharacters() { return characters; }


    @Override
    public String toString() {
        if (isDeleted) return "";

        StringBuilder sb = new StringBuilder();
        for (CharNode node : characters.values()) {
            sb.append(node.toString());
        }
        return sb.toString();
    }
}
