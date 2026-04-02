package CRDT;

import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;

import static CRDT.PositionID.computePosition;

public class Document {
    private String authorid;
    private TreeMap<PositionID, BlockNode> blocktree;
    private UndoRedo actions;
    private String documentId;

    public Document(String aid, String docid) {
        authorid = aid;
        documentId = docid;
        blocktree = new TreeMap<>();
        actions = new UndoRedo();
        PositionID firstPos = computePosition(authorid, null, null);
        BlockNode firstBlock = new BlockNode(firstPos);
        blocktree.put(firstPos, firstBlock);
    }

    public Operation addparagraph(int parindex, String text) {
        List<PositionID> positions = new ArrayList<>(blocktree.keySet());
        PositionID prevPos;
        if (parindex > 0) {
            prevPos = positions.get(parindex - 1);
        } else {
            prevPos = null;
        }
        PositionID nextPos;
        if (parindex < positions.size()) {
            nextPos = positions.get(parindex);
        } else {
            nextPos = null;
        }
        PositionID newPos = computePosition(authorid, prevPos, nextPos);
        BlockNode newBlock = new BlockNode(newPos);
        if (text != null && !text.isEmpty()) {
            PositionID prevChar = null;
            for (char c : text.toCharArray()) {
                PositionID charPos = computePosition(authorid, prevChar, null);
                newBlock.insertChar(charPos, c);
                prevChar = charPos;
            }
        }
        blocktree.put(newPos, newBlock);
        Operation op = new Operation(Operation.OpType.INSERT_BLOCK, newPos, null, text, null);
        actions.pushLocalAction(op);
        return op;
    }

    public Operation removeParagraph(PositionID blockPos) {
        BlockNode block = blocktree.get(blockPos);
        if (block == null || block.isDeleted()) return null;
        block.markDeleted();
        Operation op = new Operation(Operation.OpType.DELETE_BLOCK, blockPos, null, null, null);
        actions.pushLocalAction(op);
        return op;
    }

    public Operation splitParagraph(PositionID blockPos, int splitIndex) {
        BlockNode block = blocktree.get(blockPos);
        if (block == null || block.isDeleted())
            return null;
        List<PositionID> charPositions = new ArrayList<>(block.getCharacters().keySet());
        if (splitIndex >= charPositions.size())
            return null;
        PositionID splitCharPos = charPositions.get(splitIndex);
        List<PositionID> blockPositions = new ArrayList<>(blocktree.keySet());
        int currentIdx = blockPositions.indexOf(blockPos);
        PositionID nextBlockPos = (currentIdx + 1 < blockPositions.size()) ? blockPositions.get(currentIdx + 1) : null;
        PositionID newBlockPos = computePosition(authorid,blockPos, nextBlockPos);
        BlockNode newBlock = block.splitAt(splitCharPos, newBlockPos);
        blocktree.put(newBlockPos, newBlock);
        Operation op = new Operation(Operation.OpType.SPLIT_BLOCK,blockPos,splitCharPos,null,null);
        actions.pushLocalAction(op);
        return op;
    }

    public Operation moveParagraph(PositionID blockPos, int targetIndex)
    {
        BlockNode block = blocktree.get(blockPos);
        if (block == null || block.isDeleted()) return null;
        blocktree.remove(blockPos);
        List<PositionID> positions = new ArrayList<>(blocktree.keySet());
        PositionID prevPos = (targetIndex > 0) ? positions.get(targetIndex - 1) : null;
        PositionID nextPos = (targetIndex < positions.size()) ? positions.get(targetIndex) : null;
        PositionID freshPos = computePosition(authorid,prevPos, nextPos);
        blocktree.put(freshPos, block);
        Operation op = new Operation(Operation.OpType.MOVE_BLOCK,blockPos,null,null,null);
        op.setInverseData(freshPos);
        actions.pushLocalAction(op);
        return op;
    }

    public Operation typeCharacter(PositionID blockPos, int cursorIndex, char letter) {

        BlockNode block = blocktree.get(blockPos);
        if (block == null || block.isDeleted())
            return null;
        List<PositionID> charPositions =
                new ArrayList<>(block.getCharacters().keySet());
        PositionID prevChar = (cursorIndex > 0) ? charPositions.get(cursorIndex - 1) : null;
        PositionID nextChar = (cursorIndex < charPositions.size()) ? charPositions.get(cursorIndex) : null;
        PositionID newCharPos = computePosition(authorid,prevChar, nextChar);
        block.insertChar(newCharPos, letter);
        Operation op = new Operation(Operation.OpType.INSERT_CHAR,blockPos,newCharPos,String.valueOf(letter),null);
        actions.pushLocalAction(op);
        return op;
    }


    public Operation eraseCharacter(PositionID blockPos, PositionID charPos)
    {
        BlockNode block = blocktree.get(blockPos);
        if (block == null || block.isDeleted())
            return null;

        CharNode target = block.getCharacters().get(charPos);
        if (target == null || target.isDeleted())
            return null;

        char savedLetter = target.getValue();

        block.deleteChar(charPos);

        Operation op = new Operation(Operation.OpType.DELETE_CHAR,blockPos,charPos,String.valueOf(savedLetter),null);
        actions.pushLocalAction(op);

        return op;
    }




}
