package CRDT;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class EditorController {

    private final SimpMessagingTemplate messagingTemplate;
    private final DocumentService documentService;
    private final SyncService syncService;

    private final Map<String, String> sessionRoles = new ConcurrentHashMap<>();

    public EditorController(SimpMessagingTemplate messagingTemplate,
                            DocumentService documentService,
                            SyncService syncService) {
        this.messagingTemplate = messagingTemplate;
        this.documentService = documentService;
        this.syncService = syncService;
    }

    @MessageMapping("/join/{docId}")
    public void handleJoin(@DestinationVariable String docId, ClientMessage message) {
        sessionRoles.put(message.getUserId(), message.getRole());

        Document doc = documentService.getOrCreateDocument(docId, "Untitled");
        String jsonDoc = syncService.getFullDocumentJson(doc);

        messagingTemplate.convertAndSend("/topic/sync/" + message.getUserId(), jsonDoc);
    }

    @MessageMapping("/edit/{docId}")
    public void handleEdit(@DestinationVariable String docId, ClientMessage message) {
        String role = sessionRoles.getOrDefault(message.getUserId(), "VIEWER");

        if ("VIEWER".equalsIgnoreCase(role)) {
            return;
        }

        messagingTemplate.convertAndSend("/topic/document/" + docId, message);
    }

    @MessageMapping("/cursor/{docId}")
    public void handleCursor(@DestinationVariable String docId, ClientMessage message) {
        messagingTemplate.convertAndSend("/topic/cursors/" + docId, message);
    }
}