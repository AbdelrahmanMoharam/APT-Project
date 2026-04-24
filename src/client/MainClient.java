package client;

import client.controller.EditorController;
import client.ui.EditorUI;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class MainClient {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {
            // Nimbus is optional.
        }

        SwingUtilities.invokeLater(() -> {
            EditorUI ui = new EditorUI();
            new EditorController(ui, "ws://localhost:8080/ws");
            ui.show();
        });
    }
}
