package client.undo;

import client.model.Document;
import client.operations.Operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class UndoRedoManager {

    private static final int MAX_HISTORY = 10;

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();

    public synchronized void recordAction(UndoableAction action) {
        if (action == null || action.inverseOps().isEmpty()) {
            return;
        }

        undoStack.push(action);
        trim(undoStack);
        redoStack.clear();
    }

    public synchronized List<Operation> undo(Document document) {
        if (undoStack.isEmpty()) {
            return List.of();
        }

        UndoableAction action = undoStack.pop();
        for (Operation inverse : action.inverseOps()) {
            inverse.apply(document);
        }
        redoStack.push(action);
        trim(redoStack);

        return action.inverseOps();
    }

    public synchronized List<Operation> redo(Document document) {
        if (redoStack.isEmpty()) {
            return List.of();
        }

        UndoableAction action = redoStack.pop();
        for (Operation operation : action.forwardOps()) {
            operation.apply(document);
        }
        undoStack.push(action);
        trim(undoStack);

        return action.forwardOps();
    }

    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void trim(Deque<UndoableAction> stack) {
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }

    public record UndoableAction(List<Operation> forwardOps, List<Operation> inverseOps) {
        public UndoableAction {
            forwardOps = normalize(forwardOps);
            inverseOps = normalize(inverseOps);
        }

        public static UndoableAction of(List<Operation> forwardOps, List<Operation> inverseOps) {
            return new UndoableAction(forwardOps, inverseOps);
        }

        private static List<Operation> normalize(List<Operation> ops) {
            if (ops == null || ops.isEmpty()) {
                return List.of();
            }
            return Collections.unmodifiableList(new ArrayList<>(ops));
        }
    }
}
