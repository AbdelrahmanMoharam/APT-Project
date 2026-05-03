package server.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import server.model.Document;
import server.session.Session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DatabaseManager {

    private static final String ENV_MONGODB_URI = "MONGODB_URI";
    private static final String ENV_MONGODB_DATABASE = "MONGODB_DATABASE";
    private static final String DOTENV_FILE = ".env";
    private static final String DEFAULT_DATABASE_NAME = "collab_editor";
    private static final String DOCUMENTS_COLLECTION = "documents";
    private static final String SESSIONS_COLLECTION = "sessions";
    private static final String ROOT_PARENT = "__ROOT__";

        private static final Comparator<MutableCharacter> CHARACTER_ORDER = Comparator
            .comparingLong((MutableCharacter character) -> parseIdTimestamp(character.id))
            .thenComparing(character -> parseIdUser(character.id))
            .thenComparing(character -> character.id, Comparator.nullsLast(String::compareTo));

    private final ObjectMapper mapper;
    private final Gson gson;
    private final MongoClient mongoClient;
    private final MongoCollection<org.bson.Document> documentsCollection;
    private final MongoCollection<org.bson.Document> sessionsCollection;
    private final ReplaceOptions upsertOptions = new ReplaceOptions().upsert(true);

    public DatabaseManager() {
        this(resolveConfig());
    }

    private DatabaseManager(Config config) {
        this(config.connectionString(), config.databaseName());
    }

    public DatabaseManager(String connectionString, String databaseName) {
        this.mapper = new ObjectMapper();
        this.gson = new GsonBuilder().serializeNulls().create();

        String safeConnection = requireNonBlank(connectionString, "connectionString");
        String safeDatabase = requireNonBlank(databaseName, "databaseName");

        MongoClient client = null;
        try {
            client = MongoClients.create(safeConnection);
            MongoDatabase database = client.getDatabase(safeDatabase);
            database.runCommand(new org.bson.Document("ping", 1));

            this.mongoClient = client;
            this.documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            this.sessionsCollection = database.getCollection(SESSIONS_COLLECTION);
        } catch (MongoException e) {
            if (client != null) {
                client.close();
            }
            throw new IllegalStateException("Failed to connect to MongoDB Atlas", e);
        }
    }

    private static Config resolveConfig() {
        Map<String, String> dotenv = loadDotEnv();

        String connectionString = firstNonBlank(
                System.getenv(ENV_MONGODB_URI),
                dotenv.get(ENV_MONGODB_URI),
                "");
        String databaseName = firstNonBlank(
                System.getenv(ENV_MONGODB_DATABASE),
                dotenv.get(ENV_MONGODB_DATABASE),
                DEFAULT_DATABASE_NAME);

        if (isBlank(connectionString)) {
            throw new IllegalStateException("Missing MongoDB URI. Set MONGODB_URI in your environment or .env file.");
        }

        return new Config(connectionString, databaseName);
    }

    private static Map<String, String> loadDotEnv() {
        Path envPath = Paths.get(DOTENV_FILE);
        if (!Files.exists(envPath)) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        try {
            for (String rawLine : Files.readAllLines(envPath)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }

                int equalsIndex = line.indexOf('=');
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local .env file", e);
        }

        return values;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public synchronized void createDocument(Document doc) {
        Document safeDoc = Objects.requireNonNull(doc, "doc must not be null");
        CrdtDocumentSnapshot snapshot = toSnapshotFromDocument(safeDoc);

        try {
            documentsCollection.insertOne(toBson(snapshot, snapshot.documentId));
        } catch (MongoException e) {
            throw databaseFailure("createDocument", e);
        }
    }

    public synchronized Document getDocumentById(String id) {
        String documentId = requireNonBlank(id, "id");

        try {
            org.bson.Document raw = documentsCollection.find(Filters.eq("_id", documentId)).first();
            if (raw == null) {
                return null;
            }

            CrdtDocumentSnapshot snapshot = fromBson(raw, CrdtDocumentSnapshot.class);
            if (snapshot == null || snapshot.documentId == null) {
                return null;
            }

            return toServerDocument(snapshot);
        } catch (MongoException e) {
            throw databaseFailure("getDocumentById", e);
        }
    }

    public synchronized void updateDocument(Document doc) {
        Document safeDoc = Objects.requireNonNull(doc, "doc must not be null");
        CrdtDocumentSnapshot snapshot = toSnapshotFromDocument(safeDoc);

        try {
            documentsCollection.replaceOne(
                    Filters.eq("_id", snapshot.documentId),
                    toBson(snapshot, snapshot.documentId),
                    upsertOptions);
        } catch (MongoException e) {
            throw databaseFailure("updateDocument", e);
        }
    }

    public synchronized void deleteDocument(String id) {
        String documentId = requireNonBlank(id, "id");

        try {
            documentsCollection.deleteOne(Filters.eq("_id", documentId));
        } catch (MongoException e) {
            throw databaseFailure("deleteDocument", e);
        }
    }

    public synchronized void createSession(Session session) {
        SessionRecord record = toSessionRecord(session);

        try {
            persistDocumentForSession(session);
            sessionsCollection.insertOne(toBson(record, record.sessionId));
        } catch (MongoException e) {
            throw databaseFailure("createSession", e);
        }
    }

    public synchronized Session getSessionByCode(String code) {
        String normalizedCode = requireNonBlank(code, "code").toUpperCase(Locale.ROOT);

        try {
            org.bson.Document raw = sessionsCollection.find(Filters.or(
                    Filters.eq("editorCode", normalizedCode),
                    Filters.eq("viewerCode", normalizedCode)
            )).first();

            if (raw == null) {
                return null;
            }

            SessionRecord record = fromBson(raw, SessionRecord.class);
            if (record == null) {
                return null;
            }

            return hydrateSession(record);
        } catch (MongoException e) {
            throw databaseFailure("getSessionByCode", e);
        }
    }

    public synchronized void updateSession(Session session) {
        if (session == null || session.isDeleted()) {
            return;
        }
        SessionRecord record = toSessionRecord(session);

        try {
            persistDocumentForSession(session);
            sessionsCollection.replaceOne(
                    Filters.eq("_id", record.sessionId),
                    toBson(record, record.sessionId),
                    upsertOptions);
        } catch (MongoException e) {
            throw databaseFailure("updateSession", e);
        }
    }

    public synchronized void deleteSession(String sessionId) {
        String safeSessionId = requireNonBlank(sessionId, "sessionId");

        try {
            sessionsCollection.deleteOne(Filters.eq("_id", safeSessionId));
        } catch (MongoException e) {
            throw databaseFailure("deleteSession", e);
        }
    }

    public synchronized String exportDocumentText(String documentId) {
        String safeDocumentId = requireNonBlank(documentId, "documentId");

        try {
            org.bson.Document raw = documentsCollection.find(Filters.eq("_id", safeDocumentId)).first();
            if (raw == null) {
                return "";
            }

            CrdtDocumentSnapshot snapshot = fromBson(raw, CrdtDocumentSnapshot.class);
            if (snapshot == null) {
                return "";
            }

            return renderVisibleText(snapshot);
        } catch (MongoException e) {
            throw databaseFailure("exportDocumentText", e);
        }
    }

    // Compatibility wrappers for the existing SessionManager code path.
    public synchronized void saveSession(Session session) {
        if (session == null || session.isDeleted()) {
            return;
        }
        updateSession(session);
    }

    public synchronized List<PersistedSession> loadSessions() {
        List<PersistedSession> restored = new ArrayList<>();

        try {
            for (org.bson.Document raw : sessionsCollection.find()) {
                SessionRecord record = fromBson(raw, SessionRecord.class);
                if (record == null || isBlank(record.sessionId) || isBlank(record.documentId)) {
                    continue;
                }

                PersistedSession persisted = new PersistedSession();
                persisted.setSessionId(record.sessionId);
                persisted.setDocumentId(record.documentId);
                persisted.setEditorCode(record.editorCode);
                persisted.setViewerCode(record.viewerCode);
                persisted.setDocument(loadOrRecoverDocument(record.documentId));
                persisted.setOperations(toStoredOperations(record.operations));
                persisted.setUpdatedAt(record.updatedAt);
                restored.add(persisted);
            }
        } catch (MongoException e) {
            throw databaseFailure("loadSessions", e);
        }

        return restored;
    }

    private Session hydrateSession(SessionRecord record) {
        if (record == null || isBlank(record.sessionId) || isBlank(record.documentId)) {
            return null;
        }

        Document document = loadOrRecoverDocument(record.documentId);
        String editorCode = isBlank(record.editorCode) ? "EDITOR" : record.editorCode;
        String viewerCode = isBlank(record.viewerCode) ? "VIEWER" : record.viewerCode;

        Session session = new Session(
                record.sessionId,
                record.documentId,
                editorCode,
                viewerCode,
                document);

        session.restoreOperations(toStoredOperations(record.operations));
        return session;
    }

    private Document loadOrRecoverDocument(String documentId) {
        Document document = getDocumentById(documentId);
        if (document != null) {
            return document;
        }

        Document fallback = new Document(documentId, "Recovered Document");
        fallback.setBlocks(new HashMap<>());
        fallback.setOperationLog(new ArrayList<>());
        fallback.setUpdatedAt(System.currentTimeMillis());
        return fallback;
    }

    private void persistDocumentForSession(Session session) {
        Session safeSession = Objects.requireNonNull(session, "session must not be null");
        String documentId = requireNonBlank(safeSession.getDocumentId(), "session.documentId");

        String name = "Untitled Document";
        long updatedAt = System.currentTimeMillis();
        if (safeSession.getDocument() != null) {
            if (!isBlank(safeSession.getDocument().getName())) {
                name = safeSession.getDocument().getName();
            }
            if (safeSession.getDocument().getUpdatedAt() > 0) {
                updatedAt = safeSession.getDocument().getUpdatedAt();
            }
        }

        CrdtDocumentSnapshot snapshot = rebuildSnapshotFromStoredOperations(
                documentId,
                name,
                safeSession.getOperationLogSnapshot(),
                updatedAt);

        documentsCollection.replaceOne(
                Filters.eq("_id", documentId),
                toBson(snapshot, documentId),
                upsertOptions);
    }

    private SessionRecord toSessionRecord(Session session) {
        Session safeSession = Objects.requireNonNull(session, "session must not be null");

        SessionRecord record = new SessionRecord();
        record.sessionId = requireNonBlank(safeSession.getSessionId(), "session.sessionId");
        record.documentId = requireNonBlank(safeSession.getDocumentId(), "session.documentId");
        record.editorCode = requireNonBlank(safeSession.getEditorCode(), "session.editorCode");
        record.viewerCode = requireNonBlank(safeSession.getViewerCode(), "session.viewerCode");
        record.updatedAt = System.currentTimeMillis();

        for (server.session.UserSession user : safeSession.getUsers()) {
            if (user == null || !user.isConnected()) {
                continue;
            }

            ActiveUserRecord activeUser = new ActiveUserRecord();
            activeUser.userId = user.getUserId();
            activeUser.role = user.getRole() == null ? null : user.getRole().name();
            record.activeUsers.add(activeUser);
        }

        List<Session.StoredOperation> operations = safeSession.getOperationLogSnapshot();
        if (operations != null) {
            for (Session.StoredOperation operation : operations) {
                if (operation == null) {
                    continue;
                }
                record.operations.add(toStoredOperationRecord(operation));
            }
        }

        return record;
    }

    private StoredOperationRecord toStoredOperationRecord(Session.StoredOperation operation) {
        StoredOperationRecord record = new StoredOperationRecord();
        record.sequence = operation.getSequence();
        record.type = operation.getType();
        record.senderUserId = operation.getSenderUserId();
        record.timestamp = operation.getTimestamp();
        record.message = toJsonObject(operation.getMessage());
        return record;
    }

    private List<Session.StoredOperation> toStoredOperations(List<StoredOperationRecord> records) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }

        List<StoredOperationRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator
                .comparingLong((StoredOperationRecord record) -> record.sequence)
                .thenComparingLong(record -> record.timestamp));

        List<Session.StoredOperation> operations = new ArrayList<>(sorted.size());
        for (StoredOperationRecord record : sorted) {
            if (record == null) {
                continue;
            }

            Session.StoredOperation operation = new Session.StoredOperation();
            operation.setSequence(record.sequence);
            operation.setType(record.type);
            operation.setSenderUserId(record.senderUserId);
            operation.setTimestamp(record.timestamp);
            operation.setMessage(toJsonNode(record.message));
            operations.add(operation);
        }

        return operations;
    }

    private CrdtDocumentSnapshot toSnapshotFromDocument(Document document) {
        String documentId = requireNonBlank(document.getDocumentId(), "document.documentId");
        String name = isBlank(document.getName()) ? "Untitled Document" : document.getName();
        long updatedAt = document.getUpdatedAt() > 0 ? document.getUpdatedAt() : System.currentTimeMillis();

        return rebuildSnapshotFromJsonMessages(
                documentId,
            name,
                document.getOperationLog(),
                updatedAt);
    }

    private CrdtDocumentSnapshot rebuildSnapshotFromStoredOperations(String documentId,
                                         String name,
                                                                     List<Session.StoredOperation> operations,
                                                                     long updatedAt) {
        MutableCrdtDocument state = createInitialState(documentId, name, updatedAt);

        if (operations == null || operations.isEmpty()) {
            return toSnapshot(state);
        }

        List<Session.StoredOperation> ordered = new ArrayList<>(operations);
        ordered.sort(Comparator
                .comparingLong(Session.StoredOperation::getSequence)
                .thenComparingLong(Session.StoredOperation::getTimestamp));

        for (Session.StoredOperation operation : ordered) {
            if (operation == null) {
                continue;
            }
            applyOperation(state, operation.getMessage());
        }

        return toSnapshot(state);
    }

    private CrdtDocumentSnapshot rebuildSnapshotFromJsonMessages(String documentId,
                                                                 String name,
                                                                 List<JsonNode> messages,
                                                                 long updatedAt) {
        MutableCrdtDocument state = createInitialState(documentId, name, updatedAt);

        if (messages != null) {
            for (JsonNode message : messages) {
                applyOperation(state, message);
            }
        }

        return toSnapshot(state);
    }

    private MutableCrdtDocument createInitialState(String documentId, String name, long updatedAt) {
        MutableCrdtDocument state = new MutableCrdtDocument();
        state.documentId = documentId;
        state.name = name;
        state.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();

        MutableBlock root = new MutableBlock();
        root.blockId = "block-0";
        root.deleted = false;
        state.blocksById.put(root.blockId, root);
        state.blockOrder.add(root.blockId);
        return state;
    }

    private void applyOperation(MutableCrdtDocument state, JsonNode message) {
        if (state == null || message == null || message.isNull()) {
            return;
        }

        String type = normalizeType(readText(message, "type"));
        if (type == null) {
            return;
        }

        JsonNode payload = resolvePayload(message);
        JsonNode operationData = payload == null ? message : payload;

        switch (type) {
            case "INSERT_CHAR":
                applyInsertChar(state, operationData);
                break;
            case "DELETE_CHAR":
                applyDeleteChar(state, operationData);
                break;
            case "FORMAT":
            case "FORMAT_CHAR":
            case "FORMAT_RANGE":
                applyFormat(state, operationData);
                break;
            case "INSERT_BLOCK":
                applyInsertBlock(state, operationData);
                break;
            case "DELETE_BLOCK":
                applyDeleteBlock(state, operationData);
                break;
            case "SPLIT_BLOCK":
                applySplitBlock(state, operationData);
                break;
            case "MOVE_BLOCK":
                applyMoveBlock(state, operationData);
                break;
            default:
                break;
        }

        state.updatedAt = System.currentTimeMillis();
    }

    private void applyInsertChar(MutableCrdtDocument state, JsonNode data) {
        String blockId = normalizeBlockId(readText(data, "targetBlockId", "blockId"));
        String characterId = readText(data, "targetCharId", "charId", "nodeId", "id");
        if (isBlank(characterId)) {
            return;
        }

        String parentId = readText(data, "parentCharId", "parentId");
        String value = normalizeCharValue(readText(data, "value"));
        boolean bold = readBoolean(data, false, "bold");
        boolean italic = readBoolean(data, false, "italic");

        MutableBlock block = ensureBlock(state, blockId);
        MutableCharacter character = block.charactersById.get(characterId);
        if (character == null) {
            character = new MutableCharacter();
            character.id = characterId;
            block.charactersById.put(characterId, character);
        }

        character.parentId = parentId;
        character.value = value;
        character.deleted = false;
        character.bold = bold;
        character.italic = italic;
    }

    private void applyDeleteChar(MutableCrdtDocument state, JsonNode data) {
        String blockId = normalizeBlockId(readText(data, "targetBlockId", "blockId"));
        String characterId = readText(data, "targetCharId", "charId", "nodeId", "id");
        if (isBlank(characterId)) {
            return;
        }

        MutableBlock block = ensureBlock(state, blockId);
        MutableCharacter character = block.charactersById.get(characterId);
        if (character == null) {
            character = new MutableCharacter();
            character.id = characterId;
            character.parentId = readText(data, "parentCharId", "parentId");
            character.value = normalizeCharValue(readText(data, "value"));
            character.bold = readBoolean(data, false, "bold");
            character.italic = readBoolean(data, false, "italic");
            block.charactersById.put(characterId, character);
        }

        character.deleted = true;
    }

    private void applyFormat(MutableCrdtDocument state, JsonNode data) {
        int start = Math.max(0, readInt(data, 0, "rangeStart", "start"));
        int end = readInt(data, start, "rangeEnd", "end");
        if (end < start) {
            int swap = start;
            start = end;
            end = swap;
        }

        boolean toggleBold = readBoolean(data, false, "bold", "toggleBold");
        boolean toggleItalic = readBoolean(data, false, "italic", "toggleItalic");

        if (!toggleBold && !toggleItalic) {
            return;
        }

        List<MutableCharacter> flattened = flattenVisibleCharactersWithNewlineMarkers(state);
        if (flattened.isEmpty()) {
            return;
        }

        int upperBound = Math.min(end, flattened.size());
        for (int i = start; i < upperBound; i++) {
            MutableCharacter character = flattened.get(i);
            if (character == null) {
                continue;
            }

            if (toggleBold) {
                character.bold = !character.bold;
            }
            if (toggleItalic) {
                character.italic = !character.italic;
            }
        }
    }

    private void applyInsertBlock(MutableCrdtDocument state, JsonNode data) {
        String blockId = readText(data, "targetBlockId", "blockId");
        if (isBlank(blockId)) {
            return;
        }

        int targetIndex = readInt(data, Integer.MAX_VALUE, "targetIndex", "index");
        insertBlockAt(state, blockId, targetIndex);

        MutableBlock block = ensureBlock(state, blockId);
        block.deleted = false;
    }

    private void applyDeleteBlock(MutableCrdtDocument state, JsonNode data) {
        String blockId = readText(data, "targetBlockId", "blockId");
        if (isBlank(blockId)) {
            return;
        }

        MutableBlock block = state.blocksById.get(blockId);
        if (block == null) {
            return;
        }

        block.deleted = true;
        state.blockOrder.remove(blockId);

        if (state.blockOrder.isEmpty()) {
            insertBlockAt(state, "block-0", 0);
            ensureBlock(state, "block-0").deleted = false;
        }
    }

    private void applyMoveBlock(MutableCrdtDocument state, JsonNode data) {
        String blockId = readText(data, "targetBlockId", "blockId");
        if (isBlank(blockId)) {
            return;
        }

        int targetIndex = readInt(data, 0, "targetIndex", "index");
        if (!state.blockOrder.remove(blockId)) {
            return;
        }

        int clamped = clampIndex(targetIndex, state.blockOrder.size());
        state.blockOrder.add(clamped, blockId);
    }

    private void applySplitBlock(MutableCrdtDocument state, JsonNode data) {
        String sourceBlockId = readText(data, "targetBlockId", "blockId");
        String newBlockId = readText(data, "newBlockId");
        if (isBlank(sourceBlockId) || isBlank(newBlockId)) {
            return;
        }

        MutableBlock source = state.blocksById.get(sourceBlockId);
        if (source == null || source.deleted) {
            return;
        }

        int splitIndex = readInt(data, 0, "splitIndex", "index");
        List<MutableCharacter> visible = getVisibleCharactersInOrder(source);
        if (splitIndex < 0 || splitIndex >= visible.size()) {
            return;
        }

        int sourcePosition = state.blockOrder.indexOf(sourceBlockId);
        insertBlockAt(state, newBlockId, sourcePosition < 0 ? state.blockOrder.size() : sourcePosition + 1);

        MutableBlock target = ensureBlock(state, newBlockId);
        target.deleted = false;

        String parentId = null;
        for (int i = splitIndex; i < visible.size(); i++) {
            MutableCharacter sourceCharacter = visible.get(i);
            sourceCharacter.deleted = true;

            MutableCharacter targetCharacter = target.charactersById.get(sourceCharacter.id);
            if (targetCharacter == null) {
                targetCharacter = new MutableCharacter();
                targetCharacter.id = sourceCharacter.id;
                target.charactersById.put(targetCharacter.id, targetCharacter);
            }

            targetCharacter.parentId = parentId;
            targetCharacter.value = sourceCharacter.value;
            targetCharacter.deleted = false;
            targetCharacter.bold = sourceCharacter.bold;
            targetCharacter.italic = sourceCharacter.italic;

            parentId = targetCharacter.id;
        }
    }

    private MutableBlock ensureBlock(MutableCrdtDocument state, String blockId) {
        String normalized = normalizeBlockId(blockId);
        MutableBlock block = state.blocksById.get(normalized);
        if (block != null) {
            return block;
        }

        block = new MutableBlock();
        block.blockId = normalized;
        block.deleted = false;
        state.blocksById.put(normalized, block);

        if (!state.blockOrder.contains(normalized)) {
            state.blockOrder.add(normalized);
        }

        return block;
    }

    private void insertBlockAt(MutableCrdtDocument state, String blockId, int requestedIndex) {
        MutableBlock block = ensureBlock(state, blockId);
        block.deleted = false;

        state.blockOrder.remove(blockId);
        int clamped = requestedIndex == Integer.MAX_VALUE
                ? state.blockOrder.size()
                : clampIndex(requestedIndex, state.blockOrder.size());

        state.blockOrder.add(clamped, blockId);
    }

    private CrdtDocumentSnapshot toSnapshot(MutableCrdtDocument state) {
        CrdtDocumentSnapshot snapshot = new CrdtDocumentSnapshot();
        snapshot.documentId = state.documentId;
        snapshot.name = state.name;
        snapshot.updatedAt = state.updatedAt > 0 ? state.updatedAt : System.currentTimeMillis();

        List<String> orderedBlockIds = new ArrayList<>(state.blockOrder);
        for (String blockId : state.blocksById.keySet()) {
            if (!orderedBlockIds.contains(blockId)) {
                orderedBlockIds.add(blockId);
            }
        }

        for (String blockId : orderedBlockIds) {
            MutableBlock block = state.blocksById.get(blockId);
            if (block == null) {
                continue;
            }

            CrdtBlockSnapshot blockSnapshot = new CrdtBlockSnapshot();
            blockSnapshot.blockId = block.blockId;
            blockSnapshot.order = state.blockOrder.indexOf(block.blockId);
            blockSnapshot.deleted = block.deleted;
            blockSnapshot.characters = toCharacterSnapshots(block);

            snapshot.blocks.add(blockSnapshot);
        }

        return snapshot;
    }

    private List<CrdtCharacterSnapshot> toCharacterSnapshots(MutableBlock block) {
        List<MutableCharacter> ordered = getAllCharactersInCrdtOrder(block);
        List<CrdtCharacterSnapshot> snapshots = new ArrayList<>(ordered.size());

        for (MutableCharacter character : ordered) {
            CrdtCharacterSnapshot item = new CrdtCharacterSnapshot();
            item.id = character.id;
            item.parentId = character.parentId;
            item.value = normalizeCharValue(character.value);
            item.deleted = character.deleted;
            item.bold = character.bold;
            item.italic = character.italic;
            snapshots.add(item);
        }

        return snapshots;
    }

    private List<MutableCharacter> getVisibleCharactersInOrder(MutableBlock block) {
        List<MutableCharacter> ordered = depthFirstOrder(block, false);
        return ordered;
    }

    private List<MutableCharacter> getAllCharactersInCrdtOrder(MutableBlock block) {
        return depthFirstOrder(block, true);
    }

    private List<MutableCharacter> depthFirstOrder(MutableBlock block, boolean includeDeleted) {
        if (block == null || block.charactersById.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, List<MutableCharacter>> childrenByParent = new HashMap<>();
        for (MutableCharacter character : block.charactersById.values()) {
            String parent = normalizeParentId(character.parentId);
            childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(character);
        }

        for (List<MutableCharacter> children : childrenByParent.values()) {
            children.sort(CHARACTER_ORDER);
        }

        List<MutableCharacter> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        traverseCharacters(ROOT_PARENT, childrenByParent, ordered, visited, includeDeleted);

        List<MutableCharacter> orphaned = new ArrayList<>();
        for (MutableCharacter character : block.charactersById.values()) {
            if (!visited.contains(character.id)) {
                orphaned.add(character);
            }
        }
        orphaned.sort(CHARACTER_ORDER);

        for (MutableCharacter character : orphaned) {
            if (includeDeleted || !character.deleted) {
                ordered.add(character);
            }
        }

        return ordered;
    }

    private void traverseCharacters(String parentId,
                                    Map<String, List<MutableCharacter>> childrenByParent,
                                    List<MutableCharacter> output,
                                    Set<String> visited,
                                    boolean includeDeleted) {
        List<MutableCharacter> children = childrenByParent.get(parentId);
        if (children == null) {
            return;
        }

        for (MutableCharacter child : children) {
            visited.add(child.id);

            if (includeDeleted || !child.deleted) {
                output.add(child);
            }

            traverseCharacters(child.id, childrenByParent, output, visited, includeDeleted);
        }
    }

    private List<MutableCharacter> flattenVisibleCharactersWithNewlineMarkers(MutableCrdtDocument state) {
        List<MutableCharacter> flattened = new ArrayList<>();
        List<MutableBlock> visibleBlocks = getVisibleBlocksInOrder(state);

        for (int i = 0; i < visibleBlocks.size(); i++) {
            MutableBlock block = visibleBlocks.get(i);
            flattened.addAll(getVisibleCharactersInOrder(block));

            if (i < visibleBlocks.size() - 1) {
                // Newline separator between visible blocks; null mirrors client formatting behavior.
                flattened.add(null);
            }
        }

        return flattened;
    }

    private List<MutableBlock> getVisibleBlocksInOrder(MutableCrdtDocument state) {
        List<MutableBlock> visible = new ArrayList<>();
        for (String blockId : state.blockOrder) {
            MutableBlock block = state.blocksById.get(blockId);
            if (block != null && !block.deleted) {
                visible.add(block);
            }
        }

        return visible;
    }

    private String renderVisibleText(CrdtDocumentSnapshot snapshot) {
        if (snapshot == null || snapshot.blocks == null || snapshot.blocks.isEmpty()) {
            return "";
        }

        List<CrdtBlockSnapshot> blocks = new ArrayList<>(snapshot.blocks);
        blocks.sort(Comparator
                .comparingInt((CrdtBlockSnapshot block) -> block.order < 0 ? Integer.MAX_VALUE : block.order)
                .thenComparing(block -> block.blockId == null ? "" : block.blockId));

        StringBuilder out = new StringBuilder();
        boolean firstBlock = true;

        for (CrdtBlockSnapshot block : blocks) {
            if (block == null || block.deleted) {
                continue;
            }

            if (!firstBlock) {
                out.append('\n');
            }

            List<CrdtCharacterSnapshot> visibleCharacters = getVisibleCharactersInOrder(block);
            for (CrdtCharacterSnapshot character : visibleCharacters) {
                if (character.value != null && !character.value.isEmpty()) {
                    out.append(character.value.charAt(0));
                }
            }

            firstBlock = false;
        }

        return out.toString();
    }

    private List<CrdtCharacterSnapshot> getVisibleCharactersInOrder(CrdtBlockSnapshot block) {
        if (block == null || block.characters == null || block.characters.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, CrdtCharacterSnapshot> byId = new HashMap<>();
        Map<String, List<CrdtCharacterSnapshot>> childrenByParent = new HashMap<>();

        for (CrdtCharacterSnapshot character : block.characters) {
            if (character == null || isBlank(character.id)) {
                continue;
            }

            byId.put(character.id, character);

            String parent = normalizeParentId(character.parentId);
            childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(character);
        }

        Comparator<CrdtCharacterSnapshot> order = Comparator
            .comparingLong((CrdtCharacterSnapshot character) -> parseIdTimestamp(character.id))
                .thenComparing(character -> parseIdUser(character.id))
                .thenComparing(character -> character.id, Comparator.nullsLast(String::compareTo));

        for (List<CrdtCharacterSnapshot> children : childrenByParent.values()) {
            children.sort(order);
        }

        List<CrdtCharacterSnapshot> visible = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        traverseVisibleSnapshotCharacters(ROOT_PARENT, childrenByParent, visible, visited);

        List<CrdtCharacterSnapshot> orphaned = new ArrayList<>();
        for (CrdtCharacterSnapshot character : byId.values()) {
            if (!visited.contains(character.id) && !character.deleted) {
                orphaned.add(character);
            }
        }
        orphaned.sort(order);
        visible.addAll(orphaned);

        return visible;
    }

    private void traverseVisibleSnapshotCharacters(String parent,
                                                   Map<String, List<CrdtCharacterSnapshot>> childrenByParent,
                                                   List<CrdtCharacterSnapshot> output,
                                                   Set<String> visited) {
        List<CrdtCharacterSnapshot> children = childrenByParent.get(parent);
        if (children == null) {
            return;
        }

        for (CrdtCharacterSnapshot child : children) {
            visited.add(child.id);

            if (!child.deleted) {
                output.add(child);
            }

            traverseVisibleSnapshotCharacters(child.id, childrenByParent, output, visited);
        }
    }

    private org.bson.Document toBson(Object value, String id) {
        org.bson.Document bson = org.bson.Document.parse(gson.toJson(value));
        bson.put("_id", id);
        return bson;
    }

    private <T> T fromBson(org.bson.Document value, Class<T> type) {
        return gson.fromJson(value.toJson(), type);
    }

    private JsonObject toJsonObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        JsonElement element = JsonParser.parseString(node.toString());
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private JsonNode toJsonNode(JsonObject value) {
        if (value == null) {
            return null;
        }

        try {
            return mapper.readTree(gson.toJson(value));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode resolvePayload(JsonNode message) {
        if (message == null) {
            return null;
        }

        JsonNode op = message.get("op");
        if (op != null && op.isObject()) {
            return op;
        }

        JsonNode payload = message.get("payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }

        return null;
    }

    private static String normalizeType(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBlockId(String value) {
        if (isBlank(value)) {
            return "block-0";
        }
        return value;
    }

    private static String normalizeParentId(String value) {
        if (isBlank(value) || ROOT_PARENT.equals(value)) {
            return ROOT_PARENT;
        }
        return value;
    }

    private static String normalizeCharValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return String.valueOf(value.charAt(0));
    }

    private static int clampIndex(int value, int upperBound) {
        if (value < 0) {
            return 0;
        }
        if (value > upperBound) {
            return upperBound;
        }
        return value;
    }

    private static String readText(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (node.hasNonNull(key)) {
                JsonNode value = node.get(key);
                if (value.isTextual()) {
                    return value.asText();
                }
                return value.toString();
            }
        }

        return null;
    }

    private static int readInt(JsonNode node, int fallback, String... keys) {
        if (node == null || keys == null) {
            return fallback;
        }

        for (String key : keys) {
            if (node.has(key)) {
                return node.get(key).asInt(fallback);
            }
        }

        return fallback;
    }

    private static boolean readBoolean(JsonNode node, boolean fallback, String... keys) {
        if (node == null || keys == null) {
            return fallback;
        }

        for (String key : keys) {
            if (!node.has(key)) {
                continue;
            }

            JsonNode value = node.get(key);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isInt()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String text = value.asText();
                return "1".equals(text) || "true".equalsIgnoreCase(text);
            }
        }

        return fallback;
    }

    private static long parseIdTimestamp(String id) {
        if (isBlank(id)) {
            return 0L;
        }

        String[] parts = id.split(":", 3);
        if (parts.length < 2) {
            return 0L;
        }

        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String parseIdUser(String id) {
        if (isBlank(id)) {
            return "";
        }

        String[] parts = id.split(":", 3);
        return parts.length == 0 ? "" : parts[0];
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Config(String connectionString, String databaseName) {
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private IllegalStateException databaseFailure(String operation, MongoException cause) {
        return new IllegalStateException("MongoDB operation failed (" + operation + ")", cause);
    }

    private Document toServerDocument(CrdtDocumentSnapshot snapshot) {
        String documentId = isBlank(snapshot.documentId) ? "unknown-document" : snapshot.documentId;
        String name = isBlank(snapshot.name) ? "Untitled Document" : snapshot.name;

        Document document = new Document(documentId, name);
        document.setBlocks(new HashMap<>());
        document.setOperationLog(new ArrayList<>());
        document.setUpdatedAt(snapshot.updatedAt > 0 ? snapshot.updatedAt : System.currentTimeMillis());
        return document;
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

    private static final class SessionRecord {
        private String sessionId;
        private String documentId;
        private String editorCode;
        private String viewerCode;
        private List<ActiveUserRecord> activeUsers = new ArrayList<>();
        private List<StoredOperationRecord> operations = new ArrayList<>();
        private long updatedAt;
    }

    private static final class ActiveUserRecord {
        private String userId;
        private String role;
    }

    private static final class StoredOperationRecord {
        private long sequence;
        private String type;
        private String senderUserId;
        private JsonObject message;
        private long timestamp;
    }

    private static final class CrdtDocumentSnapshot {
        private String documentId;
        private String name;
        private long updatedAt;
        private List<CrdtBlockSnapshot> blocks = new ArrayList<>();
    }

    private static final class CrdtBlockSnapshot {
        private String blockId;
        private int order;
        private boolean deleted;
        private List<CrdtCharacterSnapshot> characters = new ArrayList<>();
    }

    private static final class CrdtCharacterSnapshot {
        private String id;
        private String parentId;
        private String value;
        private boolean deleted;
        private boolean bold;
        private boolean italic;
    }

    private static final class MutableCrdtDocument {
        private String documentId;
        private String name;
        private long updatedAt;
        private Map<String, MutableBlock> blocksById = new LinkedHashMap<>();
        private List<String> blockOrder = new ArrayList<>();
    }

    private static final class MutableBlock {
        private String blockId;
        private boolean deleted;
        private Map<String, MutableCharacter> charactersById = new LinkedHashMap<>();
    }

    private static final class MutableCharacter {
        private String id;
        private String parentId;
        private String value;
        private boolean deleted;
        private boolean bold;
        private boolean italic;
    }
}
