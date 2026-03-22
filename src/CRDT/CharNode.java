package CRDT;
import java.util.HashSet;
import java.util.Set;
public class CharNode {
    // Variables mapped exactly from your UML
    private PositionID charId;
    private char value;
    private boolean isDeleted;
    private Set<FormatType> formatting;

    /**
     * Constructor for a new character node.
     */
    public CharNode(PositionID charId, char value) {
        this.charId = charId;
        this.value = value;

        // When a character is first typed, it is never deleted by default[cite: 294].
        this.isDeleted = false;

        // Initialize an empty set for formatting. It starts as plain text.
        this.formatting = new HashSet<>();
    }

    /**
     * Applies the tombstone concept. We NEVER physically remove the character from memory.
     * We just mark it as deleted so the UI knows to hide it[cite: 490, 491, 494].
     */
    public void markDeleted() {
        this.isDeleted = true;
    }

    /**
     * Toggles a specific format on or off (e.g., turning Bold on, then turning it off).
     */
    public void toggleFormat(FormatType format) {
        if (this.formatting.contains(format)) {
            this.formatting.remove(format); // If it's already bold, make it normal
        } else {
            this.formatting.add(format); // If it's normal, make it bold
        }
    }

    // --- Getters ---
    public PositionID getCharId() {
        return charId;
    }

    public char getValue() {
        return value;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public Set<FormatType> getFormatting() {
        return formatting;
    }

    // --- Helper for Testing ---
    @Override
    public String toString() {
        // We skip deleted characters on display.
        if (isDeleted) {
            return "";
        }
        return String.valueOf(value);
    }

}
