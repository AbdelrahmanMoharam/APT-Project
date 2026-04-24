package server.websocket;

import org.glassfish.tyrus.server.Server;
import server.session.SessionManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class WebSocketServer {

    private static final SessionManager SESSION_MANAGER = new SessionManager();

    private final String host;
    private final int port;

    private Server tyrusServer;

    public WebSocketServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static SessionManager sessionManager() {
        return SESSION_MANAGER;
    }

    public void start() throws Exception {
        tyrusServer = new Server(
                host,
                port,
                "",
                null,
                ClientHandler.class,
                LegacyClientHandler.class);

        tyrusServer.start();
        System.out.println("--- Collaboration Server Started ---");
        System.out.println("Modern endpoint: ws://" + host + ":" + port + "/ws");
        System.out.println("Legacy endpoint: ws://" + host + ":" + port + "/collab/{documentId}");
    }

    public void startBlocking() throws Exception {
        start();
        System.out.println("Press Enter to stop the server...");
        new BufferedReader(new InputStreamReader(System.in)).readLine();
    }

    public void stop() {
        if (tyrusServer != null) {
            tyrusServer.stop();
        }
        SESSION_MANAGER.shutdown();
        System.out.println("Server stopped.");
    }
}
