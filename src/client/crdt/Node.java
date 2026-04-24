package client.crdt;

import java.util.Objects;

public class Node implements Comparable<Node> {

    public static final String ROOT_PARENT = "__ROOT__";

    private final String id;
    private final char value;
    private final String parentId;
    private boolean deleted;
    private boolean bold;
    private boolean italic;

    private final long timestamp;
    private final String userId;

    public Node(String id, char value, String parentId, boolean deleted, boolean bold, boolean italic) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.value = value;
        this.parentId = parentId;
        this.deleted = deleted;
        this.bold = bold;
        this.italic = italic;

        ParsedId parsed = parseId(id);
        this.timestamp = parsed.timestamp();
        this.userId = parsed.userId();
    }

    public Node(String id, char value, String parentId) {
        this(id, value, parentId, false, false, false);
    }

    public String getId() {
        return id;
    }

    public char getValue() {
        return value;
    }

    public String getParentId() {
        return parentId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public boolean isItalic() {
        return italic;
    }

    public void setItalic(boolean italic) {
        this.italic = italic;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getUserId() {
        return userId;
    }

    @Override
    public int compareTo(Node other) {
        if (other == null) {
            return 1;
        }

        int byTimestamp = Long.compare(this.timestamp, other.timestamp);
        if (byTimestamp != 0) {
            return byTimestamp;
        }

        int byUser = this.userId.compareTo(other.userId);
        if (byUser != 0) {
            return byUser;
        }

        return this.id.compareTo(other.id);
    }

    public static String buildId(String userId, long timestamp, long sequence) {
        return userId + ":" + timestamp + ":" + sequence;
    }

    public static ParsedId parseId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return new ParsedId("unknown", 0L);
        }

        String[] parts = nodeId.split(":", 3);
        if (parts.length < 2) {
            return new ParsedId(parts[0], 0L);
        }

        long parsedTimestamp;
        try {
            parsedTimestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            parsedTimestamp = 0L;
        }

        return new ParsedId(parts[0], parsedTimestamp);
    }

    public record ParsedId(String userId, long timestamp) {
    }
}
