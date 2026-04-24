package server;

import server.websocket.WebSocketServer;

public class MainServer {

    public static void main(String[] args) {
        WebSocketServer server = new WebSocketServer("localhost", 8080);
        try {
            server.startBlocking();
        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.stop();
        }
    }
}
