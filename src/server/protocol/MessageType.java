package server.protocol;

import java.util.Locale;

public enum MessageType {
    CONNECT,
    JOIN_SESSION,
    CREATE_SESSION,
    INSERT_CHAR,
    DELETE_CHAR,
    FORMAT,
    BLOCK_OPERATION,
    CURSOR_UPDATE,
    UNDO,
    REDO,
    SESSION_CREATED,
    SESSION_JOINED,
    USER_JOINED,
    USER_LEFT,
    USER_LIST,
    MISSED_OPERATIONS,
    ERROR,
    SYNC_STATE,
    LEGACY_JOIN,
    RENAME_DOCUMENT,
    DELETE_DOCUMENT,
    UNKNOWN;

    public static MessageType fromWire(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return UNKNOWN;
        }

        String normalized = rawType.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "CONNECT":
                return CONNECT;
            case "JOIN_SESSION":
                return JOIN_SESSION;
            case "CREATE_SESSION":
                return CREATE_SESSION;
            case "INSERT_CHAR":
                return INSERT_CHAR;
            case "DELETE_CHAR":
                return DELETE_CHAR;
            case "FORMAT":
            case "FORMAT_CHAR":
            case "FORMAT_RANGE":
                return FORMAT;
            case "BLOCK_OPERATION":
            case "INSERT_BLOCK":
            case "DELETE_BLOCK":
            case "SPLIT_BLOCK":
            case "MOVE_BLOCK":
                return BLOCK_OPERATION;
            case "CURSOR_UPDATE":
                return CURSOR_UPDATE;
            case "UNDO":
                return UNDO;
            case "REDO":
                return REDO;
            case "SESSION_CREATED":
                return SESSION_CREATED;
            case "SESSION_JOINED":
                return SESSION_JOINED;
            case "USER_JOINED":
                return USER_JOINED;
            case "USER_LEFT":
                return USER_LEFT;
            case "USER_LIST":
                return USER_LIST;
            case "MISSED_OPERATIONS":
                return MISSED_OPERATIONS;
            case "ERROR":
                return ERROR;
            case "SYNC_STATE":
                return SYNC_STATE;
            case "JOIN":
                return LEGACY_JOIN;
            case "RENAME_DOCUMENT":
                return RENAME_DOCUMENT;
            case "DELETE_DOCUMENT":
                return DELETE_DOCUMENT;
            default:
                return UNKNOWN;
        }
    }
}
