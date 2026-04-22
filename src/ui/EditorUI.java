package ui;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import CRDT.Document;
import CRDT.PositionID;
import javax.swing.text.*;
import javax.swing.*;
import java.awt.*;
import Client.SessionManager;

public class EditorUI {
    private DefaultListModel<String> userListModel;
    private JFrame frame;
    private JTextPane textArea;
    private SessionManager sessionManager;
    private Document document;

    // UI Components
    private JLabel shareCodeLabel;
    private JTextField joinField;
    private JButton joinButton;
    private JButton boldButton;
    private JButton italicButton;
    private JList<String> userList;
    public void setSessionManager(SessionManager sm) {
        this.sessionManager = sm;
    }
    public EditorUI() {
        document = new Document("user1", "doc1", "MyDoc");
        initializeUI();
    }
    private void styleButton(JButton button) {
        button.setFocusPainted(false);
        button.setBackground(new Color(230, 233, 240));
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }
    private void toggleStyle(Object style) {

        StyledDocument doc = textArea.getStyledDocument();
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();

        if (start == end) return; // nothing selected

        Element element = doc.getCharacterElement(start);
        AttributeSet as = element.getAttributes();

        boolean isOn;

        if (style == StyleConstants.Bold) {
            isOn = StyleConstants.isBold(as);
        } else {
            isOn = StyleConstants.isItalic(as);
        }

        SimpleAttributeSet sas = new SimpleAttributeSet();

        if (style == StyleConstants.Bold) {
            StyleConstants.setBold(sas, !isOn);
        } else {
            StyleConstants.setItalic(sas, !isOn);
        }

        doc.setCharacterAttributes(start, end - start, sas, false);
    }
    private void initializeUI() {

        frame = new JFrame("Collaborative Editor");
        frame.setSize(1100, 700);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // MAIN BACKGROUND
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(new Color(245, 247, 250));

        // ================= TOP BAR =================
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        // LEFT (Share Code)
        shareCodeLabel = new JLabel("Code: DOC123");
        shareCodeLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));

        // RIGHT (Join)
        JPanel joinPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        joinPanel.setOpaque(false);

        joinField = new JTextField(10);
        joinField.setPreferredSize(new Dimension(120, 30));

        joinButton = new JButton("Join");
        styleButton(joinButton);

        joinPanel.add(new JLabel("Join:"));
        joinPanel.add(joinField);
        joinPanel.add(joinButton);

        topBar.add(shareCodeLabel, BorderLayout.WEST);
        topBar.add(joinPanel, BorderLayout.EAST);

        // ================= TEXT AREA =================
        textArea = new JTextPane();
        textArea.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        textArea.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        // ================= RIGHT PANEL =================
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(220, 0));
        rightPanel.setBackground(Color.WHITE);
        rightPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel usersLabel = new JLabel("Active Users");
        usersLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));

        userListModel = new DefaultListModel<>();
        userListModel.addElement("User1");

        userList = new JList<>(userListModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createEmptyBorder());

        rightPanel.add(usersLabel, BorderLayout.NORTH);
        rightPanel.add(userScroll, BorderLayout.CENTER);

        // ================= TOOLBAR =================
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));

        boldButton = new JButton("B");
        italicButton = new JButton("I");

        styleButton(boldButton);
        styleButton(italicButton);

        toolbar.add(boldButton);
        toolbar.add(italicButton);

        // ================= MENU =================
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem importItem = new JMenuItem("Import");
        JMenuItem exportItem = new JMenuItem("Export");

        fileMenu.add(importItem);
        fileMenu.add(exportItem);
        menuBar.add(fileMenu);

        frame.setJMenuBar(menuBar);

        // ================= LAYOUT =================
        mainPanel.add(topBar, BorderLayout.NORTH);
        mainPanel.add(toolbar, BorderLayout.SOUTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(rightPanel, BorderLayout.EAST);

        frame.add(mainPanel);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        setupActions();
    }
    // ================= ACTIONS =================
    private void setupActions() {

        // Join button
        joinButton.addActionListener(e -> {
            String code = joinField.getText().trim();
            if (!code.isEmpty()) {
                // Create a random name for testing
                String testUser = "User" + (int)(Math.random() * 100);
                sessionManager.joinSession(code, testUser, "editor");
            }
        });

        // Bold button
        boldButton.addActionListener(e -> {
            toggleStyle(StyleConstants.Bold);
        });

        // Italic button
        italicButton.addActionListener(e -> {
            toggleStyle(StyleConstants.Italic);
        });
    }

    // ================= PERMISSION HANDLING =================
    public void setViewerMode(boolean isViewer) {
        textArea.setEditable(!isViewer);
        shareCodeLabel.setVisible(!isViewer);
    }
    public void onDocumentChanged(String newContent) {
        // Preserve the caret position so the cursor doesn't jump to position 0
        int caretPos = textArea.getCaretPosition();
        textArea.setText(newContent);
        // Restore caret, clamped to the new content length
        textArea.setCaretPosition(Math.min(caretPos, newContent.length()));
    }

    /**
     * Redraws remote-user cursor indicators inside the editor.
     * Called whenever any other user's cursor moves.
     *
     * @param cursorPositions  map of userId → caret position in the document
     */
    public void onCursorsUpdated(Map<String, Integer> cursorPositions) {
        // Each entry is a remote user's cursor position.
        // Highlight or draw a colored caret overlay for each one.
        // This is a placeholder — replace with your actual cursor-highlight logic.
        // Example: overlay a colored label/caret at each mapped position.
        textArea.repaint();   // trigger repaint so a custom painter can draw the cursors
    }

    /**
     * Refreshes the "Active Users" sidebar with the current participant list.
     * Called when any user joins or leaves the session.
     *
     * @param activeUsers  up-to-date set of user IDs in the session
     */
    public void onActiveUsersUpdated(Set<String> activeUsers) {
        userListModel.clear();
        for (String user : activeUsers) {
            userListModel.addElement(user);
        }
    }

    /**
     * Shows an error banner or dialog to the user.
     * Called on connection failures or protocol errors.
     *
     * @param errorMessage  human-readable description of the problem
     */
    public void onError(String errorMessage) {
        JOptionPane.showMessageDialog(
                frame,
                errorMessage,
                "Connection Error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    /**
     * Shows a "session ended" notification and locks the editor.
     * Called when the WebSocket closes (gracefully or due to a network drop).
     *
     * @param reason  the close reason from the server
     */
    public void onSessionEnded(String reason) {
        textArea.setEditable(false);
        JOptionPane.showMessageDialog(
                frame,
                "Session ended: " + reason,
                "Session Closed",
                JOptionPane.INFORMATION_MESSAGE
        );
    }
}