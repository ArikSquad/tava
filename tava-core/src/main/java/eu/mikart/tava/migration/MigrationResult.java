package eu.mikart.tava.migration;

import eu.mikart.tava.schema.plan.SchemaChange;
import java.util.List;

/**
 * Result of applying, rolling back, or resuming one migration.
 *
 * @param id migration identifier
 * @param applied whether the requested migration operation ran
 * @param changes schema changes reported by the operation
 * @param operations ordered operation results
 */
public record MigrationResult(
        String id, boolean applied, List<SchemaChange> changes, List<MigrationOperationResult> operations) {
    /**
     * Creates a migration result with operation details.
     *
     * @param id migration identifier
     * @param applied whether the requested migration operation ran
     * @param changes schema changes reported by the operation; {@code null} is treated as an empty list
     * @param operations ordered operation results; {@code null} is treated as an empty list
     */
    public MigrationResult {
        changes = List.copyOf(changes == null ? List.of() : changes);
        operations = List.copyOf(operations == null ? List.of() : operations);
    }

    /**
     * Creates a migration result with one schema operation per change.
     *
     * @param id migration identifier
     * @param applied whether the requested migration operation ran
     * @param changes schema changes reported by the operation
     * @throws NullPointerException if {@code changes} is {@code null}
     */
    public MigrationResult(final String id, final boolean applied, final List<SchemaChange> changes) {
        this(
                id,
                applied,
                changes,
                changes.stream()
                        .map(change -> MigrationOperationResult.schema(change.description(), List.of(change)))
                        .toList());
    }
}
