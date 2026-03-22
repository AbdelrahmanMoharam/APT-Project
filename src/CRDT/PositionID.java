package CRDT;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PositionID implements Comparable<PositionID> {
    // Using List<Integer> to represent the fractional path shown in the UML
    private List<Integer> fractionalPath;
    private String siteId;

    public PositionID(List<Integer> fractionalPath, String siteId) {
        this.fractionalPath = new ArrayList<>(fractionalPath);
        this.siteId = siteId;
    }

    /**
     * This method is crucial. It dictates the order of characters in the TreeMap.
     */
    @Override
    public int compareTo(PositionID other) {
        int minLength = Math.min(this.fractionalPath.size(), other.fractionalPath.size());

        // 1. Compare the paths index by index
        for (int i = 0; i < minLength; i++) {
            int cmp = Integer.compare(this.fractionalPath.get(i), other.fractionalPath.get(i));
            if (cmp != 0) {
                return cmp; // We found a difference at this level
            }
        }

        // 2. If paths are identical up to the minimum length, the shorter path comes first
        if (this.fractionalPath.size() != other.fractionalPath.size()) {
            return Integer.compare(this.fractionalPath.size(), other.fractionalPath.size());
        }

        // 3. If paths are exactly identical, tie-break using the siteId (User ID)
        return this.siteId.compareTo(other.siteId);
    }

    // --- Getters ---
    public List<Integer> getFractionalPath() {
        return fractionalPath;
    }

    public String getSiteId() {
        return siteId;
    }

    // --- Equals and HashCode (Required for TreeMaps and comparisons) ---
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PositionID that = (PositionID) o;
        return Objects.equals(fractionalPath, that.fractionalPath) &&
                Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fractionalPath, siteId);
    }

    @Override
    public String toString() {
        return fractionalPath.toString() + "-" + siteId;
    }
}
