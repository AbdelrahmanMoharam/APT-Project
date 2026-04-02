package CRDT;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PositionID implements Comparable<PositionID> {
    private List<Integer> fractionalPath;
    private String siteId;

    public PositionID(List<Integer> fractionalPath, String siteId) {
        this.fractionalPath = new ArrayList<>(fractionalPath);
        this.siteId = siteId;
    }

    @Override
    public int compareTo(PositionID other) {
        int minLength = Math.min(this.fractionalPath.size(), other.fractionalPath.size());


        for (int i = 0; i < minLength; i++) {
            int cmp = Integer.compare(this.fractionalPath.get(i), other.fractionalPath.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }

        if (this.fractionalPath.size() != other.fractionalPath.size()) {
            return Integer.compare(this.fractionalPath.size(), other.fractionalPath.size());
        }

        return this.siteId.compareTo(other.siteId);
    }

    public List<Integer> getFractionalPath() {
        return fractionalPath;
    }

    public String getSiteId() {
        return siteId;
    }


    public static PositionID computePosition(String siteid,PositionID lower, PositionID upper) {

        List<Integer> lowerPath = (lower == null)
                ? new ArrayList<>() : lower.getFractionalPath();
        List<Integer> upperPath = (upper == null)
                ? null : upper.getFractionalPath();

        List<Integer> freshPath = new ArrayList<>();
        int depth = 0;
        while (true) {
            int lo = (depth < lowerPath.size())
                    ? lowerPath.get(depth) : 0;
            int hi = (upperPath != null && depth < upperPath.size())
                    ? upperPath.get(depth) : Integer.MAX_VALUE;

            if (hi - lo > 1) {
                freshPath.add((lo + hi) / 2);
                break;
            } else {
                freshPath.add(lo);
                depth++;
            }
        }

        return new PositionID(freshPath, siteid);
    }


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
