package CRDT;

import java.util.Stack;

public class UndoRedo {

    private Stack<Operation> undoStack;
    private Stack<Operation> redoStack;
    private static final int MAX_HISTORY = 10;
    public UndoRedo()
    {
        undoStack = new Stack<>();
        redoStack = new Stack<>();
    }
    public void pushLocalAction(Operation op) {
        if (op == null) return;
        undoStack.push(op);
        if (undoStack.size() > MAX_HISTORY) {
            undoStack.remove(0);
        }
        redoStack.clear();
    }


    public Operation undo() {
        if (undoStack.isEmpty()) return null;
        Operation op = undoStack.pop();
        redoStack.push(op);
        return generateInverse(op);
    }


    public Operation redo() {
        if (redoStack.isEmpty()) return null;
        Operation op = redoStack.pop();
        undoStack.push(op);
        return op;
    }

    private Operation generateInverse(Operation op) {
        if (op == null) return null;
        if (op.getInverseData() instanceof Operation) {
            return (Operation) op.getInverseData();
        }
        switch (op.getType()) {

            case INSERT_CHAR:
                return Operation.createDeleteChar(
                        op.getTargetBlockId(),
                        op.getTargetCharId()
                );

            case DELETE_CHAR:
                return Operation.createInsertChar(
                        op.getTargetBlockId(),
                        op.getTargetCharId(),
                        op.getValue().charAt(0) // restore deleted char
                );

            case FORMAT_CHAR:

                return Operation.createFormatChar(
                        op.getTargetBlockId(),
                        op.getTargetCharId(),
                        op.getFormat()
                );

            case INSERT_BLOCK:
                return new Operation(
                        Operation.OpType.DELETE_BLOCK,
                        op.getTargetBlockId(),
                        null,
                        null,
                        null
                );

            case DELETE_BLOCK:
                return new Operation(
                        Operation.OpType.INSERT_BLOCK,
                        op.getTargetBlockId(),
                        null,
                        op.getValue(),
                        null
                );

            default:
                throw new UnsupportedOperationException(
                        "Undo not supported for: " + op.getType()
                );
        }
    }
}