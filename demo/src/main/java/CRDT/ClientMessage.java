package CRDT;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ClientMessage {
    private String type;       // e.g., "JOIN", "INSERT_CHAR", "CURSOR_UPDATE"
    private String userId;
    private String documentId;
    private String role;       // "EDITOR" or "VIEWER"
    private int position;      // Used for cursor position
    private Operation op;      // The actual CRDT Operation payload
}
