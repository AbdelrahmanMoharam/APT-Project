import CRDT.*;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        System.out.println("=== Starting CRDT Conflict Resolution Test ===");

        Document aliceDoc = new Document("Alice", "Doc123");
        Document bobDoc = new Document("Bob", "Doc123");

        PositionID sharedBlockId = aliceDoc.addparagraph(0, "").getTargetBlockId();


        bobDoc.applyRemoteOperation(new Operation(Operation.OpType.INSERT_BLOCK, sharedBlockId, null, null, null));

        System.out.println("--- Simultaneous Typing Test ---");


        Operation aliceOp = aliceDoc.typeCharacter(sharedBlockId, 0, 'A');


        Operation bobOp = bobDoc.typeCharacter(sharedBlockId, 0, 'B');

        System.out.println("Before Sync - Alice sees: " + aliceDoc.renderText().trim());
        System.out.println("Before Sync - Bob sees:   " + bobDoc.renderText().trim());


        aliceDoc.applyRemoteOperation(bobOp);

        bobDoc.applyRemoteOperation(aliceOp);

        System.out.println("\n--- After Synchronization ---");
        String aliceFinal = aliceDoc.renderText().trim();
        String bobFinal = bobDoc.renderText().trim();

        System.out.println("Final Alice: " + aliceFinal);
        System.out.println("Final Bob:   " + bobFinal);


        if (aliceFinal.equals(bobFinal)) {
            System.out.println("\nSUCCESS: Documents have converged to the same state!");
            System.out.println("The CRDT resolved the conflict deterministically.");
        } else {
            System.out.println("\nFAILURE: Documents are different. Check PositionID.compareTo logic.");
        }

    }
}