# APT Collaborative Editor

A Swing-based collaborative editor using a block + character CRDT on the client, and a WebSocket server that broadcasts operations and persists sessions to MongoDB.

## Architecture at a glance

Client (Swing UI) -> WebSocketClient -> Server (SessionManager) -> MongoDB

- The client maintains its own CRDT document and sends operations over WebSockets.
- The server validates sessions/roles, assigns sequence numbers, and broadcasts operations.
- Sessions and CRDT operation logs are persisted to MongoDB for recovery.

## Files and classes

### Client

- src/client/MainClient.java
  - MainClient: Application entry point. Sets Nimbus look-and-feel, creates EditorUI + EditorController, connects to ws://localhost:8080/ws.

- src/client/controller/EditorController.java
  - EditorController: Central coordinator for UI events, CRDT document updates, undo/redo, clipboard actions, and WebSocket messaging.

- src/client/ui/EditorUI.java
  - EditorUI: Swing UI that renders the document, hosts the toolbar, active user list, and cursor highlights.

- src/client/ui/Toolbar.java
  - Toolbar: UI controls for session actions, formatting, undo/redo, and import/export.

- src/client/crdt/CharacterCRDT.java
  - CharacterCRDT: Character-level CRDT that stores nodes by ID, handles insertion/tombstones, and renders visible text.

- src/client/crdt/BlockCRDT.java
  - BlockCRDT: Block-level CRDT that orders blocks and supports insert/delete/move/copy and content replace/append.

- src/client/crdt/Node.java
  - Node: CRDT node (single character) with unique ID, parent reference, delete flag, and formatting (bold/italic).

- src/client/model/Document.java
  - Document: Client document model with BlockCRDT, metadata, and helper methods for rendering and range formatting.

- src/client/model/Block.java
  - Block: Client block container holding a CharacterCRDT plus metadata.

- src/client/operations/Operation.java
  - Operation: Base type for all operations with JSON serialization and parsing.
  - Operation.FormatOp: Toggles bold/italic over a global character range.
  - Operation.BlockOp: Handles block insert/delete/split/move/copy and block-content edits.
  - Operation.CursorOp: Cursor updates sent to other clients.

- src/client/operations/InsertOp.java
  - InsertOp: Inserts a character node into a block CRDT (with formatting metadata).

- src/client/operations/DeleteOp.java
  - DeleteOp: Tombstones a character node (logical deletion).

- src/client/network/WebSocketClient.java
  - WebSocketClient: JSR-356 client (Tyrus) for connecting, sending operations, and receiving updates.

- src/client/undo/UndoRedoManager.java
  - UndoRedoManager: Tracks local undo/redo stacks and applies inverse operations.

### Server

- src/server/MainServer.java
  - MainServer: Server entry point; starts WebSocketServer on localhost:8080.

- src/server/ServerLauncher.java
  - ServerLauncher: Convenience wrapper that calls MainServer.main().

- src/server/Server.java
  - Server: Deprecated placeholder to keep legacy references compiling.

- src/server/websocket/WebSocketServer.java
  - WebSocketServer: Tyrus server bootstrapper; registers modern and legacy endpoints and owns the SessionManager singleton.

- src/server/websocket/ClientHandler.java
  - ClientHandler: /ws endpoint that forwards WebSocket events to SessionManager.

- src/server/websocket/LegacyClientHandler.java
  - LegacyClientHandler: /collab/{documentId} endpoint for backward compatibility.

- src/server/session/SessionManager.java
  - SessionManager: Central server coordinator. Creates/joins sessions, validates roles, broadcasts operations, and persists to MongoDB.

- src/server/session/Session.java
  - Session: Represents a collaborative session, operation log, and connected users; assigns operation sequences.

- src/server/session/UserSession.java
  - UserSession: Tracks a single user connection, role, cursor position, and reconnect window metadata.

- src/server/persistence/DatabaseManager.java
  - DatabaseManager: MongoDB persistence layer for sessions and CRDT operation logs; loads config from environment or .env.

- src/server/sharing/CodeManager.java
  - CodeManager: Generates and validates short join codes mapping to session IDs and roles.

- src/server/protocol/Message.java
  - Message: DTO for WebSocket message fields and payload.

- src/server/protocol/MessageType.java
  - MessageType: Enum with parsing rules for supported message types.

## How the pieces connect

1. MainClient bootstraps EditorUI + EditorController.
2. EditorController applies local CRDT changes and sends operations via WebSocketClient.
3. WebSocketClient sends JSON operations to WebSocketServer (/ws).
4. SessionManager assigns a sequence number, persists, and broadcasts to other users.
5. Remote clients apply the operation and re-render their UI.

## Running the project

### Prerequisites

- JDK 17+ (project targets Java 17).
- Maven installed.
- MongoDB Atlas (or a compatible MongoDB server) reachable via connection string.

### Configure MongoDB

Set environment variables or create a .env file in the project root:

- MONGODB_URI (required)
- MONGODB_DATABASE (optional, default: collab_editor)

Example .env:

MONGODB_URI=mongodb+srv://<user>:<pass>@<cluster>/<db>?retryWrites=true&w=majority
MONGODB_DATABASE=collab_editor

### Build

mvn clean compile

### Run the server

mvn -q exec:java -Dexec.mainClass=server.MainServer

- WebSocket endpoint: ws://localhost:8080/ws
- Legacy endpoint: ws://localhost:8080/collab/{documentId}

### Run the client

mvn -q exec:java -Dexec.mainClass=client.MainClient

## Notes

- Session data is persisted in MongoDB; local data/sessions JSON files are not used and were removed.
