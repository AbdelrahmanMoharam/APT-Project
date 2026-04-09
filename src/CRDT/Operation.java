package CRDT;
public class Operation
{
    public enum OpType {
        INSERT_BLOCK,
        DELETE_BLOCK,
        SPLIT_BLOCK,
        MOVE_BLOCK,
        INSERT_CHAR,
        DELETE_CHAR,
        FORMAT_CHAR  // For Bold/Italic
    }

    private final OpType type;
    private final PositionID targetBlockId;
    private final PositionID targetCharId;
    private final String value;
    private final FormatType format;
    private Object inverseData;

    public Operation(OpType type, PositionID targetBlockId, PositionID targetCharId,
                     String value, FormatType format) {
        this.type = type;
        this.targetBlockId = targetBlockId;
        this.targetCharId = targetCharId;
        this.value = value;
        this.format = format;
    }


    public static Operation createInsertChar(PositionID blockId, PositionID charId, char val) {
        return new Operation(OpType.INSERT_CHAR, blockId, charId, String.valueOf(val), null);
    }

    public static Operation createDeleteChar(PositionID blockId, PositionID charId) {
        return new Operation(OpType.DELETE_CHAR, blockId, charId, null, null);
    }

    public static Operation createFormatChar(PositionID blockId, PositionID charId, FormatType format) {
        return new Operation(OpType.FORMAT_CHAR, blockId, charId, null, format);
    }

    public static Operation createInsertBlock(PositionID blockId) {
        return new Operation(OpType.INSERT_BLOCK, blockId, null, null, null);
    }


    public OpType getType() { return type; }
    public PositionID getTargetBlockId() { return targetBlockId; }
    public PositionID getTargetCharId() { return targetCharId; }
    public String getValue() { return value; }
    public FormatType getFormat() { return format; }

    public Object getInverseData() { return inverseData; }
    public void setInverseData(Object inverseData) { this.inverseData = inverseData; }

    @Override
    public String toString()
    {
        return String.format("Op[%s] Block:%s Char:%s Val:%s",
                type, targetBlockId, targetCharId, value);
    }
}