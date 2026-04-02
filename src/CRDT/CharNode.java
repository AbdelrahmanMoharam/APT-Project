package CRDT;
import java.util.HashSet;
import java.util.Set;
public class CharNode {

    private PositionID charId;
    private char value;
    private boolean isDeleted;
    private Set<FormatType> formatting;


    public CharNode(PositionID charId, char value) {
        this.charId = charId;
        this.value = value;

        this.isDeleted = false;

        this.formatting = new HashSet<>();
    }
    public void markDeleted() {
        this.isDeleted = true;
    }



    public void toggleFormat(FormatType format) {
        if (this.formatting.contains(format)) {
            this.formatting.remove(format);
        } else {
            this.formatting.add(format);
        }
    }


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


    @Override
    public String toString() {
        if (isDeleted) {
            return "";
        }
        return String.valueOf(value);
    }

}
