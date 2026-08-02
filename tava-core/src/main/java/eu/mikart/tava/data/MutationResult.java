package eu.mikart.tava.data;

import java.util.List;

/** Detailed outcome of an insert, update, delete, or upsert. Unknown counts are -1. */
public record MutationResult(long matchedRows, long changedRows, long conflicts, Outcome outcome,
                             List<ConflictDetail> conflictDetails) {
    public enum Outcome { INSERTED, UPDATED, DELETED, UNCHANGED, CONFLICT }
    public MutationResult {
        conflictDetails = conflictDetails == null ? List.of() : List.copyOf(conflictDetails);
    }
    public MutationResult(long matchedRows, long changedRows, long conflicts, Outcome outcome) {
        this(matchedRows, changedRows, conflicts, outcome, List.of());
    }
    public boolean inserted() { return outcome == Outcome.INSERTED; }
    public boolean updated() { return outcome == Outcome.UPDATED; }
    public boolean conflicted() { return outcome == Outcome.CONFLICT || conflicts > 0; }
    public static MutationResult changed(long rows, Outcome outcome) { return new MutationResult(rows, rows, 0, outcome, List.of()); }
}
