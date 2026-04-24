package server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import server.model.Document;
import server.session.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DatabaseManager {

    private final ObjectMapper mapper;
    private final Path sessionsDirectory;

    public DatabaseManager() {
        this(Paths.get("data"));
    }

    public DatabaseManager(Path rootPath) {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.sessionsDirectory = rootPath.resolve("sessions");

        try {
            Files.createDirectories(sessionsDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize persistence directory", e);
        }
    }

    public synchronized void saveSession(Session session) {
        if (session == null) {
            return;
        }

        PersistedSession persisted = new PersistedSession();
        persisted.setSessionId(session.getSessionId());
        persisted.setDocumentId(session.getDocumentId());
        persisted.setEditorCode(session.getEditorCode());
        persisted.setViewerCode(session.getViewerCode());
        persisted.setDocument(session.getDocument());
        persisted.setOperations(session.getOperationLogSnapshot());
        persisted.setUpdatedAt(System.currentTimeMillis());

        Path outputFile = sessionsDirectory.resolve(session.getSessionId() + ".json");
        try {
            mapper.writeValue(outputFile.toFile(), persisted);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist session " + session.getSessionId(), e);
        }
    }

    public synchronized List<PersistedSession> loadSessions() {
        List<PersistedSession> restored = new ArrayList<>();

        if (!Files.exists(sessionsDirectory)) {
            return restored;
        }

        try (Stream<Path> files = Files.list(sessionsDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            PersistedSession snapshot = mapper.readValue(path.toFile(), PersistedSession.class);
                            if (snapshot.getSessionId() != null && snapshot.getDocumentId() != null) {
                                restored.add(snapshot);
                            }
                        } catch (IOException e) {
                            System.err.println("Skipping unreadable session file: " + path);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read persisted sessions", e);
        }

        return restored;
    }

    public static class PersistedSession {
        private String sessionId;
        private String documentId;
        private String editorCode;
        private String viewerCode;
        private Document document;
        private List<Session.StoredOperation> operations = new ArrayList<>();
        private long updatedAt;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getEditorCode() {
            return editorCode;
        }

        public void setEditorCode(String editorCode) {
            this.editorCode = editorCode;
        }

        public String getViewerCode() {
            return viewerCode;
        }

        public void setViewerCode(String viewerCode) {
            this.viewerCode = viewerCode;
        }

        public Document getDocument() {
            return document;
        }

        public void setDocument(Document document) {
            this.document = document;
        }

        public List<Session.StoredOperation> getOperations() {
            return operations;
        }

        public void setOperations(List<Session.StoredOperation> operations) {
            this.operations = operations == null ? new ArrayList<>() : operations;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
