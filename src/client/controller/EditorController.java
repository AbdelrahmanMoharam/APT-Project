package client.controller;

import client.crdt.CharacterCRDT;
import client.crdt.BlockCRDT;
import client.crdt.Node;
import client.model.Block;
import client.model.Document;
import client.network.WebSocketClient;
import client.operations.DeleteOp;
import client.operations.InsertOp;
import client.operations.Operation;
import client.ui.EditorUI;
import client.ui.Toolbar;
import client.undo.UndoRedoManager;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EditorController implements WebSocketClient.Listener {

    private final EditorUI ui;
    private final WebSocketClient socketClient;

    private final Document document;
    private final UndoRedoManager undoRedoManager;

    private final Map<String, Integer> remoteCursorPositions = new ConcurrentHashMap<>();
    private final AtomicLong localSequence = new AtomicLong(0L);

    private volatile String currentUserId = "user1";
    private volatile String currentSessionId;
    private volatile boolean editorMode = false;

    private volatile boolean suppressLocalInput = false;
    private volatile String copiedBlockId;
    private volatile List<BlockCRDT.CharacterAtom> copiedBlockContent = List.of();

    public EditorController(EditorUI ui, String serverEndpoint) {
        this.ui = ui;
        this.socketClient = new WebSocketClient(this);
        this.document = new Document("local-document", "Untitled");
        this.undoRedoManager = new UndoRedoManager();

        wireUiActions();

        this.ui.renderDocument(document);
        this.ui.setEditorMode(false);
        this.ui.setStatus("Connecting to server...");

        this.socketClient.connect(serverEndpoint);
    }

    private void wireUiActions() {
        Toolbar toolbar = ui.getToolbar();

        toolbar.getCreateSessionButton().addActionListener(event -> {
            String user = sanitized(toolbar.getUserField().getText(), "user1");
            String title = sanitized(toolbar.getTitleField().getText(), "Shared Document");
            this.currentUserId = user;
            socketClient.createSession(user, title);
        });

        toolbar.getJoinSessionButton().addActionListener(event -> {
            String user = sanitized(toolbar.getUserField().getText(), "user1");
            String code = sanitized(toolbar.getJoinCodeField().getText(), "");
            if (code.isBlank()) {
                ui.showError("Please enter a valid join code");
                return;
            }

            this.currentUserId = user;
            socketClient.joinSession(user, code);
        });

        toolbar.getBoldButton().addActionListener(event -> applyFormat(true, false));
        toolbar.getItalicButton().addActionListener(event -> applyFormat(false, true));

        toolbar.getUndoButton().addActionListener(event -> undoLocal());
        toolbar.getRedoButton().addActionListener(event -> redoLocal());

        toolbar.getImportButton().addActionListener(event -> importDocument());
        toolbar.getExportButton().addActionListener(event -> exportDocument());

        wireTextInput(ui.getTextPane());
    }

    private void wireTextInput(JTextPane textPane) {
        textPane.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (suppressLocalInput || !editorMode) {
                    return;
                }

                char typed = e.getKeyChar();
                if (Character.isISOControl(typed)) {
                    return;
                }

                e.consume();
                insertAtCaret(typed);
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (suppressLocalInput || !editorMode) {
                    return;
                }

                if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    e.consume();
                    copyCurrentBlock();
                    return;
                }

                if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_V) {
                    e.consume();
                    pasteCopiedBlockAfterCurrent();
                    return;
                }

                if (e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && e.getKeyCode() == KeyEvent.VK_V) {
                    e.consume();
                    pasteFromClipboardAtCaret();
                    return;
                }

                if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    insertEmptyBlockAfterCurrent();
                    return;
                }

                if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_DELETE) {
                    e.consume();
                    deleteCurrentBlock();
                    return;
                }

                if (e.isControlDown() && e.isAltDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    e.consume();
                    copyCurrentBlockContent();
                    return;
                }

                if (e.isControlDown() && e.isAltDown() && e.getKeyCode() == KeyEvent.VK_V) {
                    e.consume();
                    pasteCopiedContentIntoCurrentBlock();
                    return;
                }

                if (e.isAltDown() && e.getKeyCode() == KeyEvent.VK_UP) {
                    e.consume();
                    moveCurrentBlockBy(-1);
                    return;
                }

                if (e.isAltDown() && e.getKeyCode() == KeyEvent.VK_DOWN) {
                    e.consume();
                    moveCurrentBlockBy(1);
                    return;
                }

                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    e.consume();
                    deleteBeforeCaret();
                } else if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    e.consume();
                    deleteAtCaret();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    splitBlockAtCaret();
                }
            }
        });

        textPane.addCaretListener(event -> {
            if (!socketClient.isConnected() || currentSessionId == null) {
                return;
            }
            socketClient.sendCursorUpdate(event.getDot());
        });
    }

    private void insertAtCaret(char value) {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        Block block = location.block();
        CharacterCRDT tree = block.getCharTree();

        int caret = ui.getTextPane().getCaretPosition();
        String parentId = tree.parentIdForInsertAt(location.offsetInBlock());
        String nodeId = Node.buildId(currentUserId, System.currentTimeMillis(), localSequence.incrementAndGet());

        InsertOp op = new InsertOp(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                block.getBlockId(),
                nodeId,
                parentId,
                value,
                false,
                false);

        applyOperationLocally(op, true, true, caret + 1);
    }

    private void deleteBeforeCaret() {
        int caret = ui.getTextPane().getCaretPosition();
        CaretLocation location = resolveCaretLocation(caret);
        Block block = location.block();
        CharacterCRDT tree = block.getCharTree();

        if (caret <= 0) {
            return;
        }

        if (location.offsetInBlock() == 0 && location.blockIndex() > 0) {
            mergeWithPreviousBlock(location);
            return;
        }

        if (location.offsetInBlock() <= 0) {
            return;
        }

        Node node = tree.getVisibleNodeAt(location.offsetInBlock() - 1);
        if (node == null) {
            return;
        }

        DeleteOp op = new DeleteOp(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                block.getBlockId(),
                node.getId(),
                normalizeParent(node.getParentId()),
                node.getValue(),
                node.isBold(),
                node.isItalic());

        applyOperationLocally(op, true, true, caret - 1);
    }

    private void mergeWithPreviousBlock(CaretLocation location) {
        List<Block> blocks = document.getBlockCRDT().getVisibleBlocks();
        int currentIndex = location.blockIndex();
        if (currentIndex <= 0 || currentIndex >= blocks.size()) {
            return;
        }

        Block current = blocks.get(currentIndex);
        Block previous = blocks.get(currentIndex - 1);

        List<BlockCRDT.CharacterAtom> previousSnapshot = extractBlockAtoms(previous);
        List<BlockCRDT.CharacterAtom> currentContent = extractBlockAtoms(current);

        List<Operation> forwardOps = new ArrayList<>();
        List<Operation> inverseOps = new ArrayList<>();

        if (!currentContent.isEmpty()) {
            forwardOps.add(Operation.BlockOp.modifyBlockContent(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    previous.getBlockId(),
                    currentContent,
                    true));

            inverseOps.add(Operation.BlockOp.modifyBlockContent(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    previous.getBlockId(),
                    previousSnapshot,
                    false));
        }

        forwardOps.add(Operation.BlockOp.delete(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                current.getBlockId(),
                currentIndex));

        inverseOps.add(0, Operation.BlockOp.insert(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                current.getBlockId(),
                currentIndex));

        int desiredCaret = computeGlobalCaret(blocks, currentIndex - 1, previousSnapshot.size());

        applyBulkOperationsLocally(forwardOps, false, true, desiredCaret);

        for (Operation operation : forwardOps) {
            operation.setUserId(null);
        }
        for (Operation operation : inverseOps) {
            operation.setUserId(null);
        }

        if (!inverseOps.isEmpty()) {
            undoRedoManager.recordAction(UndoRedoManager.UndoableAction.of(forwardOps, inverseOps));
        }
    }

    private void deleteAtCaret() {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        Block block = location.block();
        CharacterCRDT tree = block.getCharTree();

        int caret = ui.getTextPane().getCaretPosition();
        Node node = tree.getVisibleNodeAt(location.offsetInBlock());
        if (node == null) {
            return;
        }

        DeleteOp op = new DeleteOp(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                block.getBlockId(),
                node.getId(),
                normalizeParent(node.getParentId()),
                node.getValue(),
                node.isBold(),
                node.isItalic());

        applyOperationLocally(op, true, true, caret);
    }

    private void splitBlockAtCaret() {
        int caret = ui.getTextPane().getCaretPosition();
        CaretLocation location = resolveCaretLocation(caret);
        Block primary = location.block();
        int splitIndex = location.offsetInBlock();

        String newBlockId = "block-" + System.currentTimeMillis();
        Operation.BlockOp split = Operation.BlockOp.split(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                primary.getBlockId(),
                splitIndex,
                newBlockId);

        applyOperationLocally(split, true, true, caret + 1);
    }

    private void copyCurrentBlock() {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        copiedBlockId = location.block().getBlockId();
        copiedBlockContent = extractBlockAtoms(location.block());
        ui.setStatus("Copied block " + copiedBlockId + " (Ctrl+Shift+V to paste)");
    }

    private void pasteCopiedBlockAfterCurrent() {
        if (copiedBlockId == null || copiedBlockContent.isEmpty()) {
            ui.setStatus("No copied block available");
            return;
        }

        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        int targetIndex = location.blockIndex() + 1;

        String newBlockId = "block-copy-" + System.currentTimeMillis() + "-" + localSequence.incrementAndGet();
        List<Operation> operations = new ArrayList<>();
        operations.add(Operation.BlockOp.copyBlock(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                copiedBlockId,
                newBlockId,
                targetIndex));

        // Ensure deterministic replicated content even if source block changes after copy.
        operations.add(Operation.BlockOp.modifyBlockContent(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                newBlockId,
                remapAtoms(copiedBlockContent),
                false));

        applyBulkOperationsLocally(operations, true, true, ui.getTextPane().getCaretPosition());
    }

    private void copyCurrentBlockContent() {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        copiedBlockId = location.block().getBlockId();
        copiedBlockContent = extractBlockAtoms(location.block());
        ui.setStatus("Copied block content from " + location.block().getBlockId() + " (Ctrl+Alt+V to paste)");
    }

    private void pasteCopiedContentIntoCurrentBlock() {
        if (copiedBlockContent.isEmpty()) {
            ui.setStatus("No copied block content available");
            return;
        }

        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        Operation.BlockOp op = Operation.BlockOp.copyBlockContent(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                copiedBlockId,
                location.block().getBlockId(),
                remapAtoms(copiedBlockContent),
                true);

        applyOperationLocally(op, true, true, ui.getTextPane().getCaretPosition());
    }

    private void moveCurrentBlockBy(int delta) {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        int currentIndex = location.blockIndex();
        int targetIndex = Math.max(0, currentIndex + delta);

        if (targetIndex == currentIndex) {
            return;
        }

        Operation.BlockOp move = Operation.BlockOp.move(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                location.block().getBlockId(),
                targetIndex,
                currentIndex);

        applyOperationLocally(move, true, true, ui.getTextPane().getCaretPosition());
    }

    private void insertEmptyBlockAfterCurrent() {
        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        String blockId = "block-" + System.currentTimeMillis() + "-" + localSequence.incrementAndGet();
        int targetIndex = location.blockIndex() + 1;

        Operation.BlockOp op = Operation.BlockOp.insert(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                blockId,
                targetIndex);

        applyOperationLocally(op, true, true, ui.getTextPane().getCaretPosition());
    }

    private void deleteCurrentBlock() {
        List<Block> blocks = document.getBlockCRDT().getVisibleBlocks();
        if (blocks.size() <= 1) {
            ui.setStatus("Cannot delete the only remaining block");
            return;
        }

        CaretLocation location = resolveCaretLocation(ui.getTextPane().getCaretPosition());
        int desiredCaret = 0;
        if (location.blockIndex() > 0) {
            Block previous = blocks.get(location.blockIndex() - 1);
            int previousLength = previous.getCharTree().getVisibleNodesInOrder().size();
            desiredCaret = computeGlobalCaret(blocks, location.blockIndex() - 1, previousLength);
        }
        Operation.BlockOp op = Operation.BlockOp.delete(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                location.block().getBlockId(),
                location.blockIndex());

        applyOperationLocally(op, true, true, desiredCaret);
    }

    private CaretLocation resolveCaretLocation(int globalCaret) {
        List<Block> blocks = document.getBlockCRDT().getVisibleBlocks();
        if (blocks.isEmpty()) {
            Block block = document.getBlockCRDT().insertBlock("block-0", 0);
            return new CaretLocation(block, 0, 0);
        }

        int caret = Math.max(0, globalCaret);
        int position = 0;

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int blockLength = block.getCharTree().getVisibleNodesInOrder().size();
            int blockEnd = position + blockLength;

            if (caret <= blockEnd) {
                return new CaretLocation(block, i, Math.max(0, caret - position));
            }

            position = blockEnd;
            if (i < blocks.size() - 1) {
                int newlinePos = position + 1;
                if (caret <= newlinePos) {
                    return new CaretLocation(blocks.get(i + 1), i + 1, 0);
                }
                position = newlinePos;
            }
        }

        Block last = blocks.get(blocks.size() - 1);
        int lastOffset = last.getCharTree().getVisibleNodesInOrder().size();
        return new CaretLocation(last, blocks.size() - 1, lastOffset);
    }

    private int computeGlobalCaret(List<Block> blocks, int blockIndex, int offsetInBlock) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }

        int position = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int blockLength = block.getCharTree().getVisibleNodesInOrder().size();

            if (i == blockIndex) {
                int safeOffset = Math.max(0, Math.min(offsetInBlock, blockLength));
                return position + safeOffset;
            }

            position += blockLength;
            if (i < blocks.size() - 1) {
                position += 1;
            }
        }

        return position;
    }

    private List<BlockCRDT.CharacterAtom> extractBlockAtoms(Block block) {
        List<BlockCRDT.CharacterAtom> atoms = new ArrayList<>();
        for (Node node : block.getCharTree().getVisibleNodesInOrder()) {
            atoms.add(new BlockCRDT.CharacterAtom(node.getId(), node.getValue(), node.isBold(), node.isItalic()));
        }
        return atoms;
    }

    private List<BlockCRDT.CharacterAtom> remapAtoms(List<BlockCRDT.CharacterAtom> source) {
        long baseTimestamp = System.currentTimeMillis();
        List<BlockCRDT.CharacterAtom> remapped = new ArrayList<>();

        for (BlockCRDT.CharacterAtom atom : source) {
            remapped.add(new BlockCRDT.CharacterAtom(
                    Node.buildId(currentUserId, baseTimestamp, localSequence.incrementAndGet()),
                    atom.value(),
                    atom.bold(),
                    atom.italic()));
        }

        return remapped;
    }

    private void applyFormat(boolean bold, boolean italic) {
        if (!editorMode) {
            return;
        }

        int selectionStart = ui.getTextPane().getSelectionStart();
        int selectionEnd = ui.getTextPane().getSelectionEnd();

        if (selectionEnd <= selectionStart) {
            return;
        }

        Operation.FormatOp op = new Operation.FormatOp(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                selectionStart,
                selectionEnd,
                bold,
                italic);

        applyOperationLocally(op, true, true, selectionEnd);
        restoreSelection(selectionStart, selectionEnd);
    }

    private void restoreSelection(int selectionStart, int selectionEnd) {
        int length = ui.getTextPane().getDocument().getLength();
        int safeStart = Math.max(0, Math.min(selectionStart, length));
        int safeEnd = Math.max(safeStart, Math.min(selectionEnd, length));

        ui.getTextPane().setSelectionStart(safeStart);
        ui.getTextPane().setSelectionEnd(safeEnd);
        ui.getTextPane().requestFocusInWindow();
    }

    private void undoLocal() {
        if (!editorMode) {
            return;
        }

        List<Operation> inverses = undoRedoManager.undo(document);
        if (inverses.isEmpty()) {
            return;
        }

        renderDocumentKeepingCaret(ui.getTextPane().getCaretPosition());
        for (Operation inverse : inverses) {
            socketClient.sendOperation(inverse);
        }
    }

    private void redoLocal() {
        if (!editorMode) {
            return;
        }

        List<Operation> ops = undoRedoManager.redo(document);
        if (ops.isEmpty()) {
            return;
        }

        renderDocumentKeepingCaret(ui.getTextPane().getCaretPosition());
        for (Operation op : ops) {
            socketClient.sendOperation(op);
        }
    }

    private void importDocument() {
        File file = ui.chooseImportFile();
        if (file == null) {
            return;
        }

        try {
            String content = readImportFileContent(file);

            boolean onlineSession = currentSessionId != null && socketClient.isConnected();
            if (onlineSession) {
                if (!editorMode) {
                    ui.showError("Only editors can import and sync documents.");
                    return;
                }

                importIntoCurrentSession(content);
                ui.showInfo("Document imported and synchronized from " + file.getName());
            } else {
                // Offline/local import still works for single-user edits.
                document.importFromTextFormat(content, currentUserId);
                renderDocumentKeepingCaret(0);
                ui.showInfo("Document imported locally from " + file.getName());
            }
        } catch (Exception e) {
            ui.showError("Import failed: " + e.getMessage());
        }
    }

    private String readImportFileContent(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());

        List<Charset> candidates = List.of(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_16,
                StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE,
                Charset.forName("windows-1252"),
                StandardCharsets.ISO_8859_1,
                Charset.defaultCharset());

        for (Charset charset : candidates) {
            try {
                CharsetDecoder decoder = charset.newDecoder();
                decoder.onMalformedInput(CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                return decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                // Try next charset.
            }
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void exportDocument() {
        File file = ui.chooseExportFile();
        if (file == null) {
            return;
        }

        try {
            // Export .txt as human-readable text rather than internal encoded format.
            String output = document.renderPlainText();

            String fileName = file.getName().toLowerCase();
            File outputFile = fileName.endsWith(".txt")
                    ? file
                    : new File(file.getParentFile(), file.getName() + ".txt");

            Files.writeString(outputFile.toPath(), output);
            ui.showInfo("Document exported to " + outputFile.getName());
        } catch (Exception e) {
            ui.showError("Export failed: " + e.getMessage());
        }
    }

    private void pasteFromClipboardAtCaret() {
        String clipboardText;
        try {
            Object value = Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            clipboardText = value == null ? "" : value.toString();
        } catch (Exception e) {
            ui.showError("Paste failed: cannot read clipboard text.");
            return;
        }

        if (clipboardText.isEmpty()) {
            return;
        }

        int caret = ui.getTextPane().getCaretPosition();
        List<Operation> forwardOps = new ArrayList<>();
        List<Operation> inverseOps = new ArrayList<>();

        for (int i = 0; i < clipboardText.length(); i++) {
            char ch = clipboardText.charAt(i);

            if (ch == '\r') {
                continue;
            }

            if (ch == '\n') {
                CaretLocation location = resolveCaretLocation(caret);
                String newBlockId = "block-" + System.currentTimeMillis() + "-" + localSequence.incrementAndGet();
                Operation.BlockOp split = Operation.BlockOp.split(
                        currentSessionId,
                        currentUserId,
                        System.currentTimeMillis(),
                        location.block().getBlockId(),
                        location.offsetInBlock(),
                        newBlockId);
                recordPasteOperation(split, forwardOps, inverseOps);
                caret += 1;
                continue;
            }

            CaretLocation location = resolveCaretLocation(caret);
            CharacterCRDT tree = location.block().getCharTree();
            String parentId = tree.parentIdForInsertAt(location.offsetInBlock());
            String nodeId = Node.buildId(currentUserId, System.currentTimeMillis(), localSequence.incrementAndGet());

            InsertOp op = new InsertOp(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    location.block().getBlockId(),
                    nodeId,
                    parentId,
                    ch,
                    false,
                    false);
            recordPasteOperation(op, forwardOps, inverseOps);
            caret += 1;
        }

        if (!inverseOps.isEmpty()) {
            UndoRedoManager.UndoableAction action = UndoRedoManager.UndoableAction.of(forwardOps, inverseOps);
            undoRedoManager.recordAction(action);
        }

        renderDocumentKeepingCaret(caret);
    }

    private void recordPasteOperation(Operation operation,
                                      List<Operation> forwardOps,
                                      List<Operation> inverseOps) {
        if (operation == null) {
            return;
        }

        List<Operation> perOpInverse = buildInverseOperations(operation);

        operation.setUserId(null);
        for (Operation inverse : perOpInverse) {
            inverse.setUserId(null);
        }

        operation.apply(document);
        socketClient.sendOperation(operation);

        forwardOps.add(operation);
        if (!perOpInverse.isEmpty()) {
            inverseOps.addAll(0, perOpInverse);
        }
    }

    private void applyOperationWithoutRender(Operation operation,
                                             boolean trackUndo,
                                             boolean sendToServer) {
        if (operation == null) {
            return;
        }

        UndoRedoManager.UndoableAction action = trackUndo ? buildUndoAction(operation) : null;
        operation.apply(document);

        if (trackUndo && action != null) {
            undoRedoManager.recordAction(action);
        }

        if (sendToServer) {
            socketClient.sendOperation(operation);
        }
    }

    private void importIntoCurrentSession(String importedContent) {
        List<Operation> operations = buildImportOperations(importedContent);
        applyBulkOperationsLocally(operations, false, true, 0);
    }

    private List<Operation> buildImportOperations(String importedContent) {
        List<Operation> operations = new ArrayList<>();

        Document importedSnapshot = new Document(document.getDocumentId(), document.getTitle());
        importedSnapshot.importFromTextFormat(importedContent, currentUserId);

        List<Block> currentBlocks = new ArrayList<>(document.getBlockCRDT().getVisibleBlocks());
        String primaryBlockId;

        if (currentBlocks.isEmpty()) {
            primaryBlockId = "block-0";
            operations.add(Operation.BlockOp.insert(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    primaryBlockId,
                    0));
        } else {
            primaryBlockId = currentBlocks.get(0).getBlockId();
        }

        // Step 1: clear all current characters using tombstones.
        for (Block block : currentBlocks) {
            List<Node> existingNodes = new ArrayList<>(block.getCharTree().getVisibleNodesInOrder());
            for (Node node : existingNodes) {
                operations.add(new DeleteOp(
                        currentSessionId,
                        currentUserId,
                        System.currentTimeMillis(),
                        block.getBlockId(),
                        node.getId(),
                        normalizeParent(node.getParentId()),
                        node.getValue(),
                        node.isBold(),
                        node.isItalic()));
            }
        }

        // Step 2: remove extra blocks, keeping the first block as the base.
        for (int i = currentBlocks.size() - 1; i >= 1; i--) {
            Block block = currentBlocks.get(i);
            operations.add(Operation.BlockOp.delete(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    block.getBlockId(),
                    i));
        }

        List<Block> importedBlocks = new ArrayList<>(importedSnapshot.getBlockCRDT().getVisibleBlocks());
        if (importedBlocks.isEmpty()) {
            return operations;
        }

        long baseTimestamp = System.currentTimeMillis();

        // Step 3: write first imported block into the existing first block.
        appendBlockCharsAsInsertOps(operations, importedBlocks.get(0), primaryBlockId, baseTimestamp);

        // Step 4: create additional blocks and populate them.
        for (int i = 1; i < importedBlocks.size(); i++) {
            String newBlockId = "block-" + baseTimestamp + "-" + i + "-" + localSequence.incrementAndGet();
            operations.add(Operation.BlockOp.insert(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    newBlockId,
                    i));

            appendBlockCharsAsInsertOps(operations, importedBlocks.get(i), newBlockId, baseTimestamp);
        }

        return operations;
    }

    private void appendBlockCharsAsInsertOps(List<Operation> output,
                                             Block sourceBlock,
                                             String targetBlockId,
                                             long baseTimestamp) {
        String parentId = null;
        List<Node> sourceNodes = new ArrayList<>(sourceBlock.getCharTree().getVisibleNodesInOrder());

        for (Node node : sourceNodes) {
            String newNodeId = Node.buildId(
                    currentUserId,
                    baseTimestamp,
                    localSequence.incrementAndGet());

            output.add(new InsertOp(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    targetBlockId,
                    newNodeId,
                    parentId,
                    node.getValue(),
                    node.isBold(),
                    node.isItalic()));

            parentId = newNodeId;
        }
    }

    private void applyOperationLocally(Operation operation,
                                       boolean trackUndo,
                                       boolean sendToServer,
                                       int desiredCaret) {
        if (operation == null) {
            return;
        }

        UndoRedoManager.UndoableAction action = trackUndo ? buildUndoAction(operation) : null;
        operation.apply(document);

        if (trackUndo && action != null) {
            undoRedoManager.recordAction(action);
        }

        renderDocumentKeepingCaret(desiredCaret);

        if (sendToServer) {
            socketClient.sendOperation(operation);
        }
    }

    private void applyBulkOperationsLocally(List<Operation> operations,
                                            boolean trackUndo,
                                            boolean sendToServer,
                                            int desiredCaret) {
        if (operations == null || operations.isEmpty()) {
            renderDocumentKeepingCaret(desiredCaret);
            return;
        }

        List<Operation> inverseOps = trackUndo ? new ArrayList<>() : List.of();

        for (Operation operation : operations) {
            List<Operation> perOpInverse = trackUndo ? buildInverseOperations(operation) : List.of();
            if (trackUndo) {
                operation.setUserId(null);
                for (Operation inverse : perOpInverse) {
                    inverse.setUserId(null);
                }
            }
            operation.apply(document);

            if (trackUndo && !perOpInverse.isEmpty()) {
                inverseOps.addAll(0, perOpInverse);
            }

            if (sendToServer) {
                socketClient.sendOperation(operation);
            }
        }

        if (trackUndo && !inverseOps.isEmpty()) {
            UndoRedoManager.UndoableAction action = UndoRedoManager.UndoableAction.of(operations, inverseOps);
            undoRedoManager.recordAction(action);
        }

        renderDocumentKeepingCaret(desiredCaret);
    }

    private UndoRedoManager.UndoableAction buildUndoAction(Operation operation) {
        if (operation == null) {
            return null;
        }

        List<Operation> inverseOps = buildInverseOperations(operation);
        if (inverseOps.isEmpty()) {
            return null;
        }

        operation.setUserId(null);
        for (Operation inverse : inverseOps) {
            inverse.setUserId(null);
        }

        return UndoRedoManager.UndoableAction.of(List.of(operation), inverseOps);
    }

    private List<Operation> buildInverseOperations(Operation operation) {
        if (operation instanceof Operation.BlockOp blockOp) {
            return buildBlockInverseOperations(blockOp);
        }

        Operation inverse = operation.getInverse();
        if (inverse == null) {
            return List.of();
        }
        return List.of(inverse);
    }

    private List<Operation> buildBlockInverseOperations(Operation.BlockOp blockOp) {
        if (blockOp == null) {
            return List.of();
        }

        switch (blockOp.getAction()) {
            case MODIFY_BLOCK_CONTENT:
            case COPY_BLOCK_CONTENT:
                Operation inverse = buildBlockContentInverse(blockOp);
                return inverse == null ? List.of() : List.of(inverse);
            case SPLIT_BLOCK:
                return buildSplitInverseOperations(blockOp);
            default:
                Operation simpleInverse = blockOp.getInverse();
                return simpleInverse == null ? List.of() : List.of(simpleInverse);
        }
    }

    private Operation buildBlockContentInverse(Operation.BlockOp blockOp) {
        String blockId = blockOp.getBlockId();
        if (blockId == null) {
            return null;
        }

        Block block = document.getBlockCRDT().getBlock(blockId);
        List<BlockCRDT.CharacterAtom> snapshot = block == null ? List.of() : extractBlockAtoms(block);

        return Operation.BlockOp.modifyBlockContent(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                blockId,
                snapshot,
                false);
    }

    private List<Operation> buildSplitInverseOperations(Operation.BlockOp blockOp) {
        String blockId = blockOp.getBlockId();
        if (blockId == null) {
            Operation inverse = blockOp.getInverse();
            return inverse == null ? List.of() : List.of(inverse);
        }

        Block block = document.getBlockCRDT().getBlock(blockId);
        if (block == null) {
            Operation inverse = blockOp.getInverse();
            return inverse == null ? List.of() : List.of(inverse);
        }

        List<Node> visible = block.getCharTree().getVisibleNodesInOrder();
        int splitIndex = blockOp.getSplitIndex();
        if (splitIndex < 0 || splitIndex >= visible.size()) {
            Operation inverse = blockOp.getInverse();
            return inverse == null ? List.of() : List.of(inverse);
        }

        List<Operation> inverses = new ArrayList<>();
        for (int i = splitIndex; i < visible.size(); i++) {
            Node node = visible.get(i);
            inverses.add(new InsertOp(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    blockId,
                    node.getId(),
                    normalizeParent(node.getParentId()),
                    node.getValue(),
                    node.isBold(),
                    node.isItalic()));
        }

        String newBlockId = blockOp.getNewBlockId();
        if (newBlockId != null) {
            inverses.add(Operation.BlockOp.delete(
                    currentSessionId,
                    currentUserId,
                    System.currentTimeMillis(),
                    newBlockId,
                    -1));
        }

        return inverses;
    }

    private void renderDocumentKeepingCaret(int desiredCaret) {
        suppressLocalInput = true;
        ui.renderDocument(document);

        int maxCaret = ui.getTextPane().getDocument().getLength();
        int safeCaret = Math.max(0, Math.min(desiredCaret, maxCaret));
        ui.getTextPane().setCaretPosition(safeCaret);

        suppressLocalInput = false;
    }

    private String normalizeParent(String parentId) {
        if (parentId == null || Node.ROOT_PARENT.equals(parentId)) {
            return null;
        }
        return parentId;
    }

    private String sanitized(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> ui.setStatus("Connected to server"));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            editorMode = false;
            currentSessionId = null;
            ui.setEditorMode(false);
            ui.setStatus("Disconnected: " + reason);
        });
    }

    @Override
    public void onSessionCreated(WebSocketClient.SessionInfo info) {
        this.currentSessionId = info.sessionId();
        this.editorMode = true;
        this.document.setDocumentId(info.documentId());

        SwingUtilities.invokeLater(() -> {
            ui.setEditorMode(true);
            ui.setShareCodes(info.editorCode(), info.viewerCode());
            ui.setStatus("Session created: " + info.sessionId());
        });
    }

    @Override
    public void onSessionJoined(WebSocketClient.SessionInfo info) {
        this.currentSessionId = info.sessionId();
        this.editorMode = "EDITOR".equalsIgnoreCase(info.role());
        this.document.setDocumentId(info.documentId());

        SwingUtilities.invokeLater(() -> {
            ui.setEditorMode(editorMode);
            ui.setShareCodes(info.editorCode(), info.viewerCode());
            ui.setStatus("Joined session: " + info.sessionId() + " as " + info.role());
        });
    }

    @Override
    public void onRemoteOperation(Operation operation) {
        if (operation == null) {
            return;
        }

        UndoRedoManager.UndoableAction action = buildUndoAction(operation);
        operation.apply(document);

        if (action != null) {
            undoRedoManager.recordAction(action);
        }

        SwingUtilities.invokeLater(() -> renderDocumentKeepingCaret(ui.getTextPane().getCaretPosition()));
    }

    @Override
    public void onRemoteCursor(String userId, int position) {
        if (userId == null || userId.equals(currentUserId)) {
            return;
        }

        remoteCursorPositions.put(userId, position);
        SwingUtilities.invokeLater(() -> ui.updateRemoteCursors(remoteCursorPositions));
    }

    @Override
    public void onActiveUsers(java.util.List<WebSocketClient.UserPresence> users) {
        SwingUtilities.invokeLater(() -> {
            ui.updateActiveUsers(users);

            remoteCursorPositions.clear();
            for (WebSocketClient.UserPresence user : users) {
                if (!user.userId().equals(currentUserId)) {
                    remoteCursorPositions.put(user.userId(), user.cursorPosition());
                }
            }
            ui.updateRemoteCursors(remoteCursorPositions);
        });
    }

    @Override
    public void onUserJoined(String userId) {
        if (userId == null || userId.equals(currentUserId)) {
            return;
        }
        SwingUtilities.invokeLater(() -> ui.setStatus(userId + " joined the session"));
    }

    @Override
    public void onUserLeft(String userId) {
        if (userId == null) {
            return;
        }
        remoteCursorPositions.remove(userId);
        SwingUtilities.invokeLater(() -> {
            ui.updateRemoteCursors(remoteCursorPositions);
            ui.setStatus(userId + " left the session");
        });
    }

    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            ui.setStatus("Error: " + message);
            ui.showError(message);
        });
    }

    private record CaretLocation(Block block, int blockIndex, int offsetInBlock) {
    }
}
