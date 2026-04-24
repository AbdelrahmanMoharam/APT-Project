package client.model;

import client.crdt.BlockCRDT;
import client.crdt.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class Document {

    private static final String EXPORT_HEADER = "APT_FMT_V1";

    private final BlockCRDT blockCRDT;

    private String documentId;
    private String title;

    public Document(String documentId, String title) {
        this.documentId = documentId;
        this.title = title;
        this.blockCRDT = new BlockCRDT();
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public BlockCRDT getBlockCRDT() {
        return blockCRDT;
    }

    public synchronized String renderPlainText() {
        StringBuilder sb = new StringBuilder();
        List<Block> blocks = blockCRDT.getVisibleBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            sb.append(blocks.get(i).getCharTree().renderPlainText());
            if (i < blocks.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized List<StyledChar> flattenVisibleChars() {
        List<StyledChar> output = new ArrayList<>();
        List<Block> blocks = blockCRDT.getVisibleBlocks();

        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            List<Node> visibleNodes = block.getCharTree().getVisibleNodesInOrder();
            for (Node node : visibleNodes) {
                output.add(new StyledChar(block.getBlockId(), node));
            }

            if (b < blocks.size() - 1) {
                // A newline separator that is not a CRDT node but part of visible text.
                output.add(new StyledChar(block.getBlockId(), null));
            }
        }

        return output;
    }

    public synchronized void toggleFormatRange(int globalStart,
                                               int globalEndExclusive,
                                               boolean toggleBold,
                                               boolean toggleItalic) {
        List<StyledChar> flattened = flattenVisibleChars();
        if (flattened.isEmpty()) {
            return;
        }

        int start = Math.max(0, globalStart);
        int end = Math.min(globalEndExclusive, flattened.size());

        for (int i = start; i < end; i++) {
            StyledChar item = flattened.get(i);
            if (item.node() == null) {
                continue;
            }
            if (toggleBold) {
                item.node().setBold(!item.node().isBold());
            }
            if (toggleItalic) {
                item.node().setItalic(!item.node().isItalic());
            }
        }
    }

    public synchronized String exportToTextFormat() {
        StringBuilder out = new StringBuilder();
        out.append(EXPORT_HEADER).append('\n');

        List<Block> blocks = blockCRDT.getVisibleBlocks();
        for (Block block : blocks) {
            List<Node> nodes = block.getCharTree().getVisibleNodesInOrder();
            for (Node node : nodes) {
                out.append((int) node.getValue())
                        .append(':')
                        .append(node.isBold() ? '1' : '0')
                        .append(':')
                        .append(node.isItalic() ? '1' : '0')
                        .append('|');
            }
            out.append('\n');
        }

        return out.toString();
    }

    public synchronized void importFromTextFormat(String rawContent, String userId) {
        String safeUser = userId == null || userId.isBlank() ? "importer" : userId;
        String content = rawContent == null ? "" : rawContent;

        blockCRDT.clear();

        AtomicLong sequence = new AtomicLong(0L);
        long now = System.currentTimeMillis();

        if (content.startsWith(EXPORT_HEADER + "\n")) {
            String[] lines = content.substring((EXPORT_HEADER + "\n").length()).split("\\r?\\n", -1);
            importEncodedLines(lines, safeUser, now, sequence);
            return;
        }

        String[] lines = content.split("\\r?\\n", -1);
        importPlainLines(lines, safeUser, now, sequence);
    }

    private void importEncodedLines(String[] lines,
                                    String userId,
                                    long baseTimestamp,
                                    AtomicLong sequence) {
        blockCRDT.clear();

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String blockId = "block-" + lineIndex;
            Block block = blockCRDT.insertBlock(blockId, lineIndex);

            String line = lines[lineIndex];
            if (line.isBlank()) {
                continue;
            }

            String[] tokens = line.split("\\|");
            String parentId = null;
            for (String token : tokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }

                String[] tuple = token.split(":");
                if (tuple.length != 3) {
                    continue;
                }

                int codePoint;
                try {
                    codePoint = Integer.parseInt(tuple[0]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                boolean bold = "1".equals(tuple[1]);
                boolean italic = "1".equals(tuple[2]);

                String nodeId = Node.buildId(userId, baseTimestamp, sequence.incrementAndGet());
                block.getCharTree().insert(nodeId, parentId, (char) codePoint, bold, italic);
                parentId = nodeId;
            }
        }
    }

    private void importPlainLines(String[] lines,
                                  String userId,
                                  long baseTimestamp,
                                  AtomicLong sequence) {
        blockCRDT.clear();

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String blockId = "block-" + lineIndex;
            Block block = blockCRDT.insertBlock(blockId, lineIndex);

            String parentId = null;
            char[] chars = lines[lineIndex].toCharArray();
            for (char ch : chars) {
                String nodeId = Node.buildId(userId, baseTimestamp, sequence.incrementAndGet());
                block.getCharTree().insert(nodeId, parentId, ch, false, false);
                parentId = nodeId;
            }
        }
    }

    public record StyledChar(String blockId, Node node) {
    }
}
