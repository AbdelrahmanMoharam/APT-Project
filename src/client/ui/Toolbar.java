package client.ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;

public class Toolbar extends JPanel {

    private final JTextField userField = new JTextField("user1", 8);
    private final JTextField titleField = new JTextField("Shared Doc", 10);
    private final JTextField joinCodeField = new JTextField(10);

    private final JButton createSessionButton = new JButton("Create Session");
    private final JButton joinSessionButton = new JButton("Join Session");

    private final JButton boldButton = new JButton("Bold");
    private final JButton italicButton = new JButton("Italic");

    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");

    private final JButton importButton = new JButton("Import .txt");
    private final JButton exportButton = new JButton("Export .txt");

    public Toolbar() {
        super(new FlowLayout(FlowLayout.LEFT, 8, 8));

        add(new JLabel("User:"));
        add(userField);

        add(new JLabel("Title:"));
        add(titleField);

        add(createSessionButton);

        add(new JLabel("Join Code:"));
        add(joinCodeField);
        add(joinSessionButton);

        add(boldButton);
        add(italicButton);

        add(undoButton);
        add(redoButton);

        add(importButton);
        add(exportButton);
    }

    public JTextField getUserField() {
        return userField;
    }

    public JTextField getTitleField() {
        return titleField;
    }

    public JTextField getJoinCodeField() {
        return joinCodeField;
    }

    public JButton getCreateSessionButton() {
        return createSessionButton;
    }

    public JButton getJoinSessionButton() {
        return joinSessionButton;
    }

    public JButton getBoldButton() {
        return boldButton;
    }

    public JButton getItalicButton() {
        return italicButton;
    }

    public JButton getUndoButton() {
        return undoButton;
    }

    public JButton getRedoButton() {
        return redoButton;
    }

    public JButton getImportButton() {
        return importButton;
    }

    public JButton getExportButton() {
        return exportButton;
    }
}
