package CRDT;

import java.util.Objects;

public class PositionID implements Comparable<PositionID> {
    private int clock;
    private String siteId;
    public PositionID(int clock, String siteId) {
        this.clock = clock;
        this.siteId = siteId;
    }
    public PositionID(String value) {
        try {
            // The value looks like "[1-Server]"
            // We remove the brackets and split by the dash
            String clean = value.replace("[", "").replace("]", "");
            String[] parts = clean.split("-");

            this.clock = Integer.parseInt(parts[0]);
            this.siteId = parts[1];
        } catch (Exception e) {
            // Fallback for unexpected formats
            this.clock = 0;
            this.siteId = value;
        }
    }
    public PositionID() {}
    @Override
    public int compareTo(PositionID other) {

        if (this.clock != other.clock) {
            return Integer.compare(this.clock, other.clock);
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