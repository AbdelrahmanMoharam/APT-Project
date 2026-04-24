package server.sharing;

import server.session.UserSession;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CodeManager {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> codeToSession = new ConcurrentHashMap<>();
    private final Map<String, UserSession.Role> codeToRole = new ConcurrentHashMap<>();

    public String generateCode() {
        String candidate;
        do {
            candidate = randomCode();
        } while (codeToSession.containsKey(candidate));
        return candidate;
    }

    public String generateCode(String sessionId, UserSession.Role role) {
        String code = generateCode();
        registerCode(code, sessionId, role);
        return code;
    }

    public boolean validateCode(String code) {
        if (code == null) {
            return false;
        }
        return codeToSession.containsKey(code.toUpperCase(Locale.ROOT));
    }

    public String getSessionByCode(String code) {
        if (code == null) {
            return null;
        }
        return codeToSession.get(code.toUpperCase(Locale.ROOT));
    }

    public UserSession.Role getRoleByCode(String code) {
        if (code == null) {
            return null;
        }
        return codeToRole.get(code.toUpperCase(Locale.ROOT));
    }

    public void registerCode(String code, String sessionId, UserSession.Role role) {
        if (code == null || sessionId == null || role == null) {
            return;
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        codeToSession.put(normalized, sessionId);
        codeToRole.put(normalized, role);
    }

    public void removeCodesForSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        codeToSession.entrySet().removeIf(entry -> {
            boolean remove = sessionId.equals(entry.getValue());
            if (remove) {
                codeToRole.remove(entry.getKey());
            }
            return remove;
        });
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = random.nextInt(ALPHABET.length());
            builder.append(ALPHABET.charAt(index));
        }
        return builder.toString();
    }
}
