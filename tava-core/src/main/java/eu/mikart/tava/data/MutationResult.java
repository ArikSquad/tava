package eu.mikart.tava.data;

/** Detailed outcome of an insert, update, delete, or upsert. Unknown counts are -1. */
public record MutationResult(long matchedRows, long changedRows, long conflicts, Outcome outcome) {
    public enum Outcome { INSERTED, UPDATED, DELETED, UNCHANGED, CONFLICT }
    public boolean inserted() { return outcome == Outcome.INSERTED; }
    public boolean updated() { return outcome == Outcome.UPDATED; }
    public static MutationResult changed(long rows, Outcome outcome) { return new MutationResult(rows, rows, 0, outcome); }
}
