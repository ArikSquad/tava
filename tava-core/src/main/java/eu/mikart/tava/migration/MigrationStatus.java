package eu.mikart.tava.migration;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

/**
 * Typed view of a durable migration journal entry.
 *
 * @param id migration identifier
 * @param state current journal state
 * @param direction migration direction being applied
 * @param completedSteps number of completed steps in the current direction, or zero for a legacy entry
 * @param checksum optional immutable content digest
 * @since 1.3.0
 */
public record MigrationStatus(
        @NotNull String id,
        @NotNull State state,
        @NotNull Direction direction,
        @Range(from = 0, to = Integer.MAX_VALUE) int completedSteps,
        @Nullable String checksum) {
    /**
     * Creates a typed view of one migration journal entry.
     *
     * @param id migration identifier
     * @param state current journal state
     * @param direction migration direction being applied
     * @param completedSteps number of completed steps in the current direction
     * @param checksum optional immutable content digest
     * @throws IllegalArgumentException if the identifier is blank or the completed step count is negative
     * @throws NullPointerException if {@code state} or {@code direction} is {@code null}
     * @since 1.3.0
     */
    public MigrationStatus {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("migration id is required");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(direction, "direction");
        if (completedSteps < 0) throw new IllegalArgumentException("completedSteps must not be negative");
    }

    /**
     * Current state recorded for a migration.
     *
     * @since 1.3.0
     */
    public enum State {
        /**
         * The migration or rollback has not completed.
         *
         * @since 1.3.0
         */
        RUNNING,
        /**
         * A step failed and the migration can be resumed.
         *
         * @since 1.3.0
         */
        FAILED,
        /**
         * The migration completed successfully.
         *
         * @since 1.3.0
         */
        APPLIED
    }

    /**
     * Direction of the journaled operation.
     *
     * @since 1.3.0
     */
    public enum Direction {
        /**
         * Applying the migration.
         *
         * @since 1.3.0
         */
        UP,
        /**
         * Rolling back the migration.
         *
         * @since 1.3.0
         */
        DOWN
    }
}
