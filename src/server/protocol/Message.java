package server.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    private String type;
    private String sessionId;
    private String userId;
    private String documentId;
    private String role;
    private String code;
    private String newName;
    private Integer position;
    private JsonNode payload;
    private JsonNode op;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public JsonNode getOp() {
        return op;
    }

    public void setOp(JsonNode op) {
        this.op = op;
    }

    @JsonIgnore
    public MessageType getMessageType() {
        return MessageType.fromWire(type);
    }

    @JsonIgnore
    public String resolveSessionId() {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        if (documentId != null && !documentId.isBlank()) {
            return documentId;
        }
        return null;
    }

    @JsonIgnore
    public String resolveCode() {
        if (code != null && !code.isBlank()) {
            return code;
        }
        if (payload != null && payload.hasNonNull("code")) {
            return payload.get("code").asText();
        }
        return null;
    }

    @JsonIgnore
    public String resolveRole() {
        if (role != null && !role.isBlank()) {
            return role;
        }
        if (payload != null && payload.hasNonNull("role")) {
            return payload.get("role").asText();
        }
        return null;
    }
}
