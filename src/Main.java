import ui.EditorUI;
import Client.SessionManager;      // Added this
import Client.MessageSerializer; // Added this
import javax.swing.*;

public class Main {
    public static void main(String[] args) {

        // 1. Keep your styling
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Start the components in order
        SwingUtilities.invokeLater(() -> {
            // A. Create the Translator (for JSON)
            MessageSerializer serializer = new MessageSerializer();

            // B. Create the UI
            EditorUI ui = new EditorUI();

            // C. Create the SessionManager (The networking logic)
            // It needs the UI and the Serializer to work.
            SessionManager sessionManager = new SessionManager(ui, serializer);

            // D. IMPORTANT: Tell the UI about the SessionManager
            // so the "Join" button actually does something.
            ui.setSessionManager(sessionManager);

            System.out.println("Application launched. Server-side connection ready.");
        });
    }
}