package eu.mikart.tava.data;

import org.jetbrains.annotations.Nullable;

/**
 * Portable details about a rejected conditional or constraint write.
 *
 * @param kind broad category of the conflict
 * @param constraint reported constraint name, if available
 * @param code adapter-specific error code, if available
 * @param message diagnostic message, if available
 */
public record ConflictDetail(
        Kind kind,
        @Nullable String constraint,
        @Nullable String code,
        @Nullable String message) {
    /** Broad category of a rejected write. */
    public enum Kind {
        /** A unique constraint rejected the write. */
        UNIQUE,
        /** A foreign key constraint rejected the write. */
        FOREIGN_KEY,
        /** A check constraint rejected the write. */
        CHECK,
        /** A conditional write predicate was not satisfied. */
        CONDITIONAL,
        /** The adapter did not identify a more specific conflict category. */
        UNKNOWN
    }
}
