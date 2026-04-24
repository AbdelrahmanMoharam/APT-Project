package client.undo;

import client.model.Document;
import client.operations.Operation;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoRedoManager {

    private static final int MAX_HISTORY = 10;

    private final Deque<Operation> undoStack = new ArrayDeque<>();
    private final Deque<Operation> redoStack = new ArrayDeque<>();

    public synchronized void recordLocalOperation(Operation operation) {
        if (operation == null) {
            return;
        }

        undoStack.push(operation);
        trim(undoStack);
        redoStack.clear();
    }

    public synchronized Operation undo(Document document) {
        if (undoStack.isEmpty()) {
            return null;
        }

        Operation original = undoStack.pop();
        Operation inverse = original.getInverse();
        if (inverse == null) {
            return null;
        }

        inverse.apply(document);
        redoStack.push(original);
        trim(redoStack);

        return inverse;
    }

    public synchronized Operation redo(Document document) {
        if (redoStack.isEmpty()) {
            return null;
        }

        Operation operation = redoStack.pop();
        operation.apply(document);
        undoStack.push(operation);
        trim(undoStack);

        return operation;
    }

    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    private void trim(Deque<Operation> stack) {
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }
}
