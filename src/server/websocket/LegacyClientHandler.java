package server.websocket;

import server.session.SessionManager;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/collab/{documentId}")
public class LegacyClientHandler {

    private static final SessionManager SESSION_MANAGER = WebSocketServer.sessionManager();

    @OnOpen
    public void onOpen(Session session, @PathParam("documentId") String documentId) {
        SESSION_MANAGER.onOpen(session, documentId);
    }

    @OnMessage
    public void onMessage(String message,
                          Session session,
                          @PathParam("documentId") String documentId) {
        SESSION_MANAGER.onMessage(session, message, documentId);
    }

    @OnClose
    public void onClose(Session session,
                        CloseReason closeReason,
                        @PathParam("documentId") String documentId) {
        SESSION_MANAGER.onClose(session, closeReason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        SESSION_MANAGER.onError(session, throwable);
    }
}
