package server;

import org.glassfish.tyrus.server.Server;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ServerLauncher {

    public static void main(String[] args) {
        // 1. Define the server configuration
        // Host: localhost
        // Port: 8080
        // Root Path: /
        // Endpoint Class: The Server.class you wrote
        // Inside ServerLauncher.java
        Server tyrusServer = new Server(
                "localhost",
                8080,
                "",   // Change this to an empty string!
                null,
                server.Server.class
        );
        try {
            // 2. Start the server
            tyrusServer.start();
            System.out.println("--- Collaboration Server Started ---");
            System.out.println("Endpoint: ws://localhost:8080/collab/{documentId}");
            System.out.println("Press 'Enter' to stop the server...");

            // 3. Keep the server running until you press Enter
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            tyrusServer.stop();
            System.out.println("Server stopped.");
        }
    }
}
