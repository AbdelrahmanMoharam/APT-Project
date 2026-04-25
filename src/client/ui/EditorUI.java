package client.ui;

import client.crdt.Node;
import client.model.Block;
import client.model.Document;
import client.network.WebSocketClient;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EditorUI {

    private final JFrame frame;
    private final Toolbar toolbar;
    private final JTextPane textPane;

    private final JPanel shareCodesPanel;
    private final JLabel editorCodeLabel;
    private final JLabel viewerCodeLabel;
    private final JButton copyEditorCodeButton;
    private final JButton copyViewerCodeButton;

    private String editorCodeValue = "";
    private String viewerCodeValue = "";

    private final DefaultListModel<String> activeUsersModel;
    private final JList<String> activeUsersList;

    private final JLabel statusLabel;

    private final Map<String, Highlighter.Highlight> cursorHighlights = new LinkedHashMap<>();
    private final Map<String, Color> cursorColors = new LinkedHashMap<>();

    private final Color[] palette = new Color[]{
            new Color(255, 99, 71, 130),
            new Color(30, 144, 255, 130),
            new Color(60, 179, 113, 130),
            new Color(255, 165, 0, 130)
    };

    public EditorUI() {
        this.frame = new JFrame("APT Collaborative Client");
        this.toolbar = new Toolbar();
        this.textPane = new JTextPane();

        this.shareCodesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        this.editorCodeLabel = new JLabel("Editor Code: -");
        this.viewerCodeLabel = new JLabel("Viewer Code: -");
        this.copyEditorCodeButton = new JButton("Copy Editor Code");
        this.copyViewerCodeButton = new JButton("Copy Viewer Code");

        this.activeUsersModel = new DefaultListModel<>();
        this.activeUsersList = new JList<>(activeUsersModel);

        this.statusLabel = new JLabel("Disconnected");

        configureLayout();
    }

    private void configureLayout() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 760);
        frame.setLayout(new BorderLayout());

        shareCodesPanel.add(editorCodeLabel);
        shareCodesPanel.add(copyEditorCodeButton);
        shareCodesPanel.add(viewerCodeLabel);
        shareCodesPanel.add(copyViewerCodeButton);

        copyEditorCodeButton.addActionListener(event -> copyToClipboard(editorCodeValue, "Editor code"));
        copyViewerCodeButton.addActionListener(event -> copyToClipboard(viewerCodeValue, "Viewer code"));

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar, BorderLayout.CENTER);
        top.add(shareCodesPanel, BorderLayout.SOUTH);

        textPane.setFont(textPane.getFont().deriveFont(16f));
        JScrollPane editorScroll = new JScrollPane(textPane);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(240, 0));
        rightPanel.add(new JLabel("Active Users"), BorderLayout.NORTH);
        rightPanel.add(new JScrollPane(activeUsersList), BorderLayout.CENTER);

        frame.add(top, BorderLayout.NORTH);
        frame.add(editorScroll, BorderLayout.CENTER);
        frame.add(rightPanel, BorderLayout.EAST);
        frame.add(statusLabel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    public Toolbar getToolbar() {
        return toolbar;
    }

    public JTextPane getTextPane() {
        return textPane;
    }

    public void setEditorMode(boolean editor) {
        textPane.setEditable(editor);
        shareCodesPanel.setVisible(editor);
    }

    public void setShareCodes(String editorCode, String viewerCode) {
        editorCodeValue = editorCode == null ? "" : editorCode;
        viewerCodeValue = viewerCode == null ? "" : viewerCode;

        editorCodeLabel.setText("Editor Code: " + (editorCodeValue.isBlank() ? "-" : editorCodeValue));
        viewerCodeLabel.setText("Viewer Code: " + (viewerCodeValue.isBlank() ? "-" : viewerCodeValue));

        copyEditorCodeButton.setEnabled(!editorCodeValue.isBlank());
        copyViewerCodeButton.setEnabled(!viewerCodeValue.isBlank());
    }

    public void setStatus(String status) {
        statusLabel.setText(status == null ? "" : status);
    }

    public void updateActiveUsers(List<WebSocketClient.UserPresence> users) {
        activeUsersModel.clear();
        if (users == null) {
            return;
        }

        for (WebSocketClient.UserPresence user : users) {
            activeUsersModel.addElement(user.userId() + " (" + user.role() + ")");
        }
    }

    public void updateRemoteCursors(Map<String, Integer> positions) {
        Highlighter highlighter = textPane.getHighlighter();
        highlighter.removeAllHighlights();
        cursorHighlights.clear();

        if (positions == null || positions.isEmpty()) {
            return;
        }

        String text = textPane.getText();
        if (text == null || text.isEmpty()) {
            return;
        }

        int count = 0;
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            if (count >= 4) {
                break;
            }

            String user = entry.getKey();
            int position = entry.getValue() == null ? -1 : entry.getValue();
            if (position < 0) {
                continue;
            }

            Color color = cursorColors.computeIfAbsent(user, key -> palette[cursorColors.size() % palette.length]);
            int start = Math.max(0, Math.min(position, text.length() - 1));
            int end = Math.min(start + 1, text.length());

            try {
                Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(color);
                highlighter.addHighlight(start, end, painter);
            } catch (BadLocationException ignored) {
                // Ignore out-of-range highlights caused by concurrent edits.
            }

            count++;
        }
    }

    public void renderDocument(Document document) {
        if (document == null) {
            return;
        }

        int caret = textPane.getCaretPosition();
        StyledDocument styled = textPane.getStyledDocument();

        try {
            styled.remove(0, styled.getLength());

            List<Block> blocks = document.getBlockCRDT().getVisibleBlocks();
            for (int b = 0; b < blocks.size(); b++) {
                List<Node> nodes = blocks.get(b).getCharTree().getVisibleNodesInOrder();
                for (Node node : nodes) {
                    SimpleAttributeSet attrs = new SimpleAttributeSet();
                    StyleConstants.setBold(attrs, node.isBold());
                    StyleConstants.setItalic(attrs, node.isItalic());
                    styled.insertString(styled.getLength(), String.valueOf(node.getValue()), attrs);
                }
                if (b < blocks.size() - 1) {
                    styled.insertString(styled.getLength(), "\n", null);
                }
            }

            int maxCaret = Math.max(0, styled.getLength());
            textPane.setCaretPosition(Math.min(caret, maxCaret));
        } catch (BadLocationException e) {
            showError("Failed to render document: " + e.getMessage());
        }
    }

    public File chooseImportFile() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    public File chooseExportFile() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    public void showInfo(String message) {
        JOptionPane.showMessageDialog(frame, message, "Info", JOptionPane.INFORMATION_MESSAGE);
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void copyToClipboard(String value, String label) {
        if (value == null || value.isBlank()) {
            showError(label + " is not available yet.");
            return;
        }

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(value), null);

        setStatus(label + " copied to clipboard");
    }

    public Map<String, Integer> collectKnownCursorPositions(List<WebSocketClient.UserPresence> users) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (users == null) {
            return positions;
        }
        for (WebSocketClient.UserPresence user : users) {
            positions.put(user.userId(), user.cursorPosition());
        }
        return positions;
    }

    public List<String> getDisplayedUsers() {
        List<String> users = new ArrayList<>();
        for (int i = 0; i < activeUsersModel.size(); i++) {
            users.add(activeUsersModel.get(i));
        }
        return users;
    }
}
