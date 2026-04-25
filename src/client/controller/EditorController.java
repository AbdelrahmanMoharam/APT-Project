package client.controller;

import client.crdt.CharacterCRDT;
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
        Block block = document.getBlockCRDT().getPrimaryBlock();
        CharacterCRDT tree = block.getCharTree();

        int caret = ui.getTextPane().getCaretPosition();
        String parentId = tree.parentIdForInsertAt(caret);
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
        Block block = document.getBlockCRDT().getPrimaryBlock();
        CharacterCRDT tree = block.getCharTree();

        int caret = ui.getTextPane().getCaretPosition();
        if (caret <= 0) {
            return;
        }

        Node node = tree.getVisibleNodeAt(caret - 1);
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

    private void deleteAtCaret() {
        Block block = document.getBlockCRDT().getPrimaryBlock();
        CharacterCRDT tree = block.getCharTree();

        int caret = ui.getTextPane().getCaretPosition();
        Node node = tree.getVisibleNodeAt(caret);
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
        Block primary = document.getBlockCRDT().getPrimaryBlock();
        int splitIndex = ui.getTextPane().getCaretPosition();

        String newBlockId = "block-" + System.currentTimeMillis();
        Operation.BlockOp split = Operation.BlockOp.split(
                currentSessionId,
                currentUserId,
                System.currentTimeMillis(),
                primary.getBlockId(),
                splitIndex,
                newBlockId);

        applyOperationLocally(split, true, true, splitIndex);
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
    }

    private void undoLocal() {
        if (!editorMode) {
            return;
        }

        Operation inverse = undoRedoManager.undo(document);
        if (inverse == null) {
            return;
        }

        renderDocumentKeepingCaret(ui.getTextPane().getCaretPosition());
        socketClient.sendOperation(inverse);
    }

    private void redoLocal() {
        if (!editorMode) {
            return;
        }

        Operation op = undoRedoManager.redo(document);
        if (op == null) {
            return;
        }

        renderDocumentKeepingCaret(ui.getTextPane().getCaretPosition());
        socketClient.sendOperation(op);
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
            String output = document.exportToTextFormat();

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

        operation.apply(document);

        if (trackUndo) {
            undoRedoManager.recordLocalOperation(operation);
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

        for (Operation operation : operations) {
            operation.apply(document);
            if (trackUndo) {
                undoRedoManager.recordLocalOperation(operation);
            }
            if (sendToServer) {
                socketClient.sendOperation(operation);
            }
        }

        renderDocumentKeepingCaret(desiredCaret);
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

        operation.apply(document);

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
}
