package eu.mikart.tava.data;

import java.util.List;

/**
 * Detailed outcome of an insert, update, delete, or upsert. Unknown counts are {@code -1}.
 *
 * @param matchedRows number of records matched by the mutation, or {@code -1} when unknown
 * @param changedRows number of records changed by the mutation, or {@code -1} when unknown
 * @param conflicts number of conflicts, or {@code -1} when unknown
 * @param outcome overall mutation outcome
 * @param conflictDetails portable details for reported conflicts
 */
public record MutationResult(
        long matchedRows, long changedRows, long conflicts, Outcome outcome, List<ConflictDetail> conflictDetails) {
    /** Overall outcome reported for a mutation. */
    public enum Outcome {
        /** A new record was inserted. */
        INSERTED,
        /** One or more existing records were updated. */
        UPDATED,
        /** One or more records were deleted. */
        DELETED,
        /** No record values changed. */
        UNCHANGED,
        /** The mutation was rejected because of a conflict. */
        CONFLICT
    }

    /**
     * Creates a mutation result with portable conflict details.
     *
     * @param matchedRows number of records matched by the mutation, or {@code -1} when unknown
     * @param changedRows number of records changed by the mutation, or {@code -1} when unknown
     * @param conflicts number of conflicts, or {@code -1} when unknown
     * @param outcome overall mutation outcome
     * @param conflictDetails portable details for reported conflicts; {@code null} is treated as an empty list
     */
    public MutationResult {
        conflictDetails = conflictDetails == null ? List.of() : List.copyOf(conflictDetails);
    }

    /**
     * Creates a mutation result without portable conflict details.
     *
     * @param matchedRows number of records matched by the mutation, or {@code -1} when unknown
     * @param changedRows number of records changed by the mutation, or {@code -1} when unknown
     * @param conflicts number of conflicts, or {@code -1} when unknown
     * @param outcome overall mutation outcome
     */
    public MutationResult(long matchedRows, long changedRows, long conflicts, Outcome outcome) {
        this(matchedRows, changedRows, conflicts, outcome, List.of());
    }

    /**
     * Reports whether the mutation inserted a record.
     *
     * @return {@code true} if the outcome is {@link Outcome#INSERTED}
     */
    public boolean inserted() {
        return outcome == Outcome.INSERTED;
    }

    /**
     * Reports whether the mutation updated existing records.
     *
     * @return {@code true} if the outcome is {@link Outcome#UPDATED}
     */
    public boolean updated() {
        return outcome == Outcome.UPDATED;
    }

    /**
     * Reports whether the outcome or conflict count indicates a conflict.
     *
     * @return {@code true} if the outcome is {@link Outcome#CONFLICT} or the conflict count is positive
     */
    public boolean conflicted() {
        return outcome == Outcome.CONFLICT || conflicts > 0;
    }

    /**
     * Creates a result whose matched and changed row counts both equal {@code rows}.
     *
     * @param rows number of matched and changed rows
     * @param outcome overall mutation outcome
     * @return mutation result with no conflicts
     */
    public static MutationResult changed(long rows, Outcome outcome) {
        return new MutationResult(rows, rows, 0, outcome, List.of());
    }
}
