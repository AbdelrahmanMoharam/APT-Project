package server.websocket;

import server.session.SessionManager;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws")
public class ClientHandler {

    private static final SessionManager SESSION_MANAGER = WebSocketServer.sessionManager();

    @OnOpen
    public void onOpen(Session session) {
        SESSION_MANAGER.onOpen(session, null);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        SESSION_MANAGER.onMessage(session, message, null);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        SESSION_MANAGER.onClose(session, closeReason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        SESSION_MANAGER.onError(session, throwable);
    }
}
