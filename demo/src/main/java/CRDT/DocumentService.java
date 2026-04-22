package CRDT;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {
    private final Map<String, Document> activeDocuments = new ConcurrentHashMap<>();

    public Document getOrCreateDocument(String documentId, String title) {
        return activeDocuments.computeIfAbsent(documentId,
                id -> new Document("SERVER", id, title));
    }
}