package CRDT;

import org.springframework.stereotype.Service;

@Service
public class SyncService {

    // This uses your custom serializer to convert the Document state
    private final MessageSerializer serializer = new MessageSerializer();

    public String getFullDocumentJson(Document doc) {
        // This converts your Document tree into a JSON string
        // that the client can understand when they first join.
        return serializer.serializeOperation(doc);
    }
}
