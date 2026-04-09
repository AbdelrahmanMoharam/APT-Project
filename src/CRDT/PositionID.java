package CRDT;

import java.util.Objects;

public class PositionID implements Comparable<PositionID> {
    private int clock;
    private String siteId;

    public PositionID(int clock, String siteId) {
        this.clock = clock;
        this.siteId = siteId;
    }


    @Override
    public int compareTo(PositionID other) {

        if (this.clock != other.clock) {
            return Integer.compare(other.clock, this.clock);
        }

        return this.siteId.compareTo(other.siteId);
    }

    public int getClock() { return clock; }
    public String getSiteId() { return siteId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PositionID that = (PositionID) o;
        return clock == that.clock && Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clock, siteId);
    }

    @Override
    public String toString() {
        return "[" + clock + "-" + siteId + "]";
    }
}