package CRDT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Document {

    // ─────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────

    // The current user's unique ID e.g. "alice"
    private String authorId;

    // The document's unique ID in the database
    private String documentId;

    // The document's name/title
    private String title;

    // The entire document — a map of blocks
    // Key   = block's PositionID
    // Value = the block itself
    // No longer TreeMap because RGA handles ordering internally
    private Map<PositionID, BlockNode> blockSequence;

    // Ordered list of block IDs — this is what determines reading order
    // Because HashMap has no order, we keep a separate list
    private List<PositionID> blockOrder;

    // Remembers the last 10 operations for undo/redo
    private UndoRedo actionHistory;

    // Tracks where each user's cursor currently is
    private Map<String, Integer> userCursors;

    // Local clock — increases by 1 every time we create a new PositionID
    // This guarantees every PositionID we generate is unique
    private int localClock;

    // ─────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────
    public Document() {}
    public Document(String authorId, String documentId, String title) {
        this.authorId       = authorId;
        this.documentId     = documentId;
        this.title          = title;
        this.blockSequence  = new HashMap<>();
        this.blockOrder     = new ArrayList<>();
        this.actionHistory  = new UndoRedo();
        this.userCursors    = new HashMap<>();
        this.localClock     = 0;

        // Every document starts with one empty block
        PositionID firstBlockId = generateId();
        BlockNode firstBlock    = new BlockNode(firstBlockId);
        blockSequence.put(firstBlockId, firstBlock);
        blockOrder.add(firstBlockId);
    }

    // ─────────────────────────────────────────
    // HELPER: generateId
    // Creates a new unique PositionID using the local clock
    // Clock increases by 1 every time so no two IDs are ever the same
    // ─────────────────────────────────────────
    private PositionID generateId() {
        localClock++;
        return new PositionID(localClock, authorId);
    }

    // ─────────────────────────────────────────
    // 1. addParagraph
    // Called when user presses Enter to create a new paragraph
    // paragraphIndex = where in the document to insert
    // initialText    = starting text for the block (usually "")
    // ─────────────────────────────────────────
    public Operation addParagraph(int paragraphIndex, String initialText) {

        // Generate a unique ID for the new block
        PositionID newBlockId = generateId();
        BlockNode  newBlock   = new BlockNode(newBlockId);

        // If there is starting text insert each character into the block
        if (initialText != null && !initialText.isEmpty()) {
            PositionID prevCharId = null;
            for (char c : initialText.toCharArray()) {
                PositionID newCharId = generateId();
                newBlock.insertChar(newCharId, c, prevCharId);
                prevCharId = newCharId;
            }
        }

        // Add block to the map
        blockSequence.put(newBlockId, newBlock);

        // Insert into the order list at the correct position
        // paragraphIndex tells us exactly where in reading order it goes
        if (paragraphIndex >= blockOrder.size()) {
            blockOrder.add(newBlockId);
        } else {
            blockOrder.add(paragraphIndex, newBlockId);
        }

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.INSERT_BLOCK,  // type
                newBlockId,           // targetBlockId
                null,                 // targetCharId — no character involved
                initialText,          // value — starting text
                null                  // format — no formatting
        );

        // Build inverse operation — inverse of insert block is delete block
        Operation inverseOp = new Operation(
                Operation.OpType.DELETE_BLOCK,  // type
                newBlockId,           // targetBlockId
                null,                 // targetCharId
                null,                 // value
                null                  // format
        );

        // Store the inverse inside the original operation
        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 2. removeParagraph
    // Called when user deletes an entire paragraph
    // ─────────────────────────────────────────
    public Operation removeParagraph(PositionID blockId) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        // Tombstone — do NOT remove from map
        block.markDeleted();

        // Also remove from reading order
        blockOrder.remove(blockId);

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.DELETE_BLOCK,  // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                null,                 // value
                null                  // format
        );

        // Inverse of delete block is insert block
        Operation inverseOp = new Operation(
                Operation.OpType.INSERT_BLOCK,  // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                null,                 // value
                null                  // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 3. breakParagraph
    // Called when user presses Enter mid paragraph
    // Everything after splitIndex moves into a new block
    // ─────────────────────────────────────────
    public Operation breakParagraph(PositionID blockId, int splitIndex) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        // Get the visible characters of the current block as a list
        List<CharNode> visibleChars = getVisibleChars(block);
        if (splitIndex >= visibleChars.size()) return null;

        // The character at splitIndex is where we cut
        PositionID splitCharId = visibleChars.get(splitIndex).getCharId();

        // Create a new block for the second half
        PositionID newBlockId = generateId();
        BlockNode  newBlock   = new BlockNode(newBlockId);

        // Move all characters from splitIndex onwards into the new block
        for (int i = splitIndex; i < visibleChars.size(); i++) {
            CharNode ch = visibleChars.get(i);
            newBlock.insertChar(ch.getCharId(), ch.getValue(), ch.getParentId());
            // Tombstone the character in the old block
            block.deleteChar(ch.getCharId());
        }

        // Insert the new block right after the current block in reading order
        int currentIdx = blockOrder.indexOf(blockId);
        blockSequence.put(newBlockId, newBlock);
        blockOrder.add(currentIdx + 1, newBlockId);

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.SPLIT_BLOCK,   // type
                blockId,              // targetBlockId
                splitCharId,          // targetCharId — where the split happens
                null,                 // value
                null                  // format
        );

        // Inverse of split is merging the two blocks back
        Operation inverseOp = new Operation(
                Operation.OpType.INSERT_BLOCK,  // type
                blockId,              // targetBlockId
                splitCharId,          // targetCharId
                null,                 // value
                null                  // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 4. relocateParagraph
    // Called when user drags a paragraph to a different position
    // ─────────────────────────────────────────
    public Operation relocateParagraph(PositionID blockId, int targetIndex) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        // Remember the old position for the inverse operation
        int oldIndex = blockOrder.indexOf(blockId);

        // Remove from current position in reading order
        blockOrder.remove(blockId);

        // Insert at new position
        if (targetIndex >= blockOrder.size()) {
            blockOrder.add(blockId);
        } else {
            blockOrder.add(targetIndex, blockId);
        }

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.MOVE_BLOCK,    // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                String.valueOf(targetIndex), // value — store new index
                null                  // format
        );

        // Inverse of move is moving back to the old position
        Operation inverseOp = new Operation(
                Operation.OpType.MOVE_BLOCK,    // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                String.valueOf(oldIndex), // value — old index to go back to
                null                  // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 5. typeCharacter
    // Called every single time the user types a letter
    // ─────────────────────────────────────────
    public Operation typeCharacter(PositionID blockId,
                                   PositionID parentCharId, char letter) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        // Generate a unique ID for this new character
        PositionID newCharId = generateId();

        // Tell the block to insert it
        // parentCharId = the character just before the cursor
        // null parentCharId means insert at the beginning
        block.insertChar(newCharId, letter, parentCharId);

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.INSERT_CHAR,   // type
                blockId,              // targetBlockId
                newCharId,            // targetCharId — the new character's ID
                String.valueOf(letter), // value — the actual letter
                null                  // format
        );

        // Inverse of insert char is delete char
        Operation inverseOp = new Operation(
                Operation.OpType.DELETE_CHAR,   // type
                blockId,              // targetBlockId
                newCharId,            // targetCharId — same character to delete
                String.valueOf(letter), // value — saved for redo
                null                  // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 6. eraseCharacter
    // Called when user presses Backspace or Delete
    // ─────────────────────────────────────────
    public Operation eraseCharacter(PositionID blockId, PositionID charId) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        // We need to get the character's value before erasing
        // so the inverse operation can re-insert it later
        CharNode target = block.getNodeMap().get(charId);
        if (target == null || target.isDeleted()) return null;

        char savedLetter  = target.getValue();
        PositionID parentId = target.getParentId();

        // Tombstone the character
        block.deleteChar(charId);

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.DELETE_CHAR,   // type
                blockId,              // targetBlockId
                charId,               // targetCharId
                String.valueOf(savedLetter), // value — saved for undo
                null                  // format
        );

        // Inverse of delete char is insert char back in the same place
        Operation inverseOp = new Operation(
                Operation.OpType.INSERT_CHAR,   // type
                blockId,              // targetBlockId
                charId,               // targetCharId — same ID so it goes back exactly
                String.valueOf(savedLetter), // value — the letter to re-insert
                null                  // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 7. applyFormatting
    // Called when user selects text and clicks Bold or Italic
    // ─────────────────────────────────────────
    public Operation applyFormatting(PositionID blockId,
                                     int rangeStart, int rangeEnd,
                                     FormatType style) {

        BlockNode block = blockSequence.get(blockId);
        if (block == null || block.isDeleted()) return null;

        List<CharNode> visibleChars = getVisibleChars(block);

        // Toggle format on every character in the selected range
        for (int i = rangeStart; i <= rangeEnd
                && i < visibleChars.size(); i++) {
            visibleChars.get(i).toggleFormat(style);
        }

        // Build the operation
        Operation op = new Operation(
                Operation.OpType.FORMAT_RANGE,  // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                rangeStart + "," + rangeEnd, // value — store range as string
                style                 // format — bold or italic
        );

        // Inverse of format is applying the same format again
        // because toggleFormat turns it on then off
        Operation inverseOp = new Operation(
                Operation.OpType.FORMAT_RANGE,  // type
                blockId,              // targetBlockId
                null,                 // targetCharId
                rangeStart + "," + rangeEnd, // value
                style                 // format
        );

        op.setInverseData(inverseOp);

        actionHistory.pushLocalAction(op);
        return op;
    }

    // ─────────────────────────────────────────
    // 8. receiveEdit
    // Called when an operation arrives from another user over the network
    // This is how Bob's machine applies Alice's edits
    // ─────────────────────────────────────────
    public void receiveEdit(Operation incoming) {

        switch (incoming.getType()) {

            case INSERT_CHAR:
                BlockNode b1 = blockSequence.get(incoming.getTargetBlockId());
                if (b1 != null && !b1.isDeleted()) {
                    b1.insertChar(
                            incoming.getTargetCharId(),
                            incoming.getValue().charAt(0),
                            // parentCharId is stored in inverseData's targetCharId
                            incoming.getTargetCharId()
                    );
                }
                break;

            case DELETE_CHAR:
                BlockNode b2 = blockSequence.get(incoming.getTargetBlockId());
                if (b2 != null)
                    b2.deleteChar(incoming.getTargetCharId());
                break;

            case INSERT_BLOCK:
                if (!blockSequence.containsKey(incoming.getTargetBlockId())) {
                    BlockNode fresh = new BlockNode(incoming.getTargetBlockId());
                    blockSequence.put(incoming.getTargetBlockId(), fresh);
                    blockOrder.add(incoming.getTargetBlockId());
                }
                break;

            case DELETE_BLOCK:
                BlockNode b3 = blockSequence.get(incoming.getTargetBlockId());
                if (b3 != null) {
                    b3.markDeleted();
                    blockOrder.remove(incoming.getTargetBlockId());
                }
                break;

            case SPLIT_BLOCK:
                // Re-apply the split using the stored splitCharId
                breakParagraph(
                        incoming.getTargetBlockId(),
                        getCharIndex(incoming.getTargetBlockId(),
                                incoming.getTargetCharId())
                );
                break;

            case MOVE_BLOCK:
                int newIndex = Integer.parseInt(incoming.getValue());
                relocateParagraph(incoming.getTargetBlockId(), newIndex);
                break;

            case FORMAT_RANGE:
                String[] parts   = incoming.getValue().split(",");
                int start        = Integer.parseInt(parts[0]);
                int end          = Integer.parseInt(parts[1]);
                applyFormatting(incoming.getTargetBlockId(),
                        start, end, incoming.getFormat());
                break;

            default:
                System.out.println("Unknown edit type: " + incoming.getType());
        }
    }

    // ─────────────────────────────────────────
    // 9. undoLastAction
    // Called when user presses Ctrl+Z
    // ─────────────────────────────────────────
    public Operation undoLastAction() {
        Operation last = actionHistory.undo();
        if (last == null) {
            System.out.println("Nothing to undo");
            return null;
        }
        // Apply the inverse operation that was stored inside the original
        receiveEdit(last.getInverseData());
        return last;
    }

    // ─────────────────────────────────────────
    // 10. redoLastAction
    // Called when user presses Ctrl+Y
    // ─────────────────────────────────────────
    public Operation redoLastAction() {
        Operation last = actionHistory.redo();
        if (last == null) {
            System.out.println("Nothing to redo");
            return null;
        }
        receiveEdit(last);
        return last;
    }

    // ─────────────────────────────────────────
    // 11. markCursorPosition
    // Called whenever the current user moves their cursor
    // ─────────────────────────────────────────
    public void markCursorPosition(int cursorIndex) {
        userCursors.put(authorId, cursorIndex);
    }

    // ─────────────────────────────────────────
    // 12. renderText
    // Returns the full document as a plain string
    // Used for testing and displaying in the UI
    // ─────────────────────────────────────────
    public String renderText() {
        StringBuilder sb = new StringBuilder();
        // blockOrder gives us blocks in correct reading order
        for (PositionID blockId : blockOrder) {
            BlockNode block = blockSequence.get(blockId);
            if (block != null && !block.isDeleted()) {
                sb.append(block.toString());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────
    // HELPER: getVisibleChars
    // Returns all non-tombstoned characters in a block in order
    // ─────────────────────────────────────────
    private List<CharNode> getVisibleChars(BlockNode block) {
        List<CharNode> visible = new ArrayList<>();
        collectVisible(block, visible);
        return visible;
    }

    private void collectVisible(BlockNode block, List<CharNode> result) {
        // BlockNode already does DFS traversal in toString()
        // We replicate that here but collect nodes instead of chars
        for (CharNode root : block.getRoots()) {
            dfsCollect(root, result);
        }
    }

    private void dfsCollect(CharNode node, List<CharNode> result) {
        if (!node.isDeleted()) result.add(node);
        for (CharNode child : node.getChildren()) {
            dfsCollect(child, result);
        }
    }

    // ─────────────────────────────────────────
    // HELPER: getCharIndex
    // Finds the index of a character in a block's visible list
    // Used by receiveEdit for SPLIT_BLOCK
    // ─────────────────────────────────────────
    private int getCharIndex(PositionID blockId, PositionID charId) {
        BlockNode block = blockSequence.get(blockId);
        if (block == null) return -1;
        List<CharNode> visible = getVisibleChars(block);
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).getCharId().equals(charId)) return i;
        }
        return -1;
    }

    // ─────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────
    public Map<PositionID, BlockNode> getBlockSequence() {
        return blockSequence;
    }

    public List<PositionID> getBlockOrder() {
        return blockOrder;
    }

    public String getAuthorId()   { return authorId; }
    public String getDocumentId() { return documentId; }
    public String getTitle()      { return title; }
    public Map<String, Integer> getUserCursors() { return userCursors; }
    public PositionID getCharIdAtIndex(PositionID blockId, int index) {
        BlockNode block = blockSequence.get(blockId);
        if (block == null) return null;

        List<CharNode> visible = getVisibleChars(block);

        if (index <= 0 || visible.isEmpty()) return null;

        if (index - 1 < visible.size()) {
            return visible.get(index - 1).getCharId();
        }


        return visible.get(visible.size() - 1).getCharId();
    }

}