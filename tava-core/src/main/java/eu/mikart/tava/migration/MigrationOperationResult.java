package eu.mikart.tava.migration;

import eu.mikart.tava.schema.plan.SchemaChange;
import java.util.List;

/**
 * Result details for one migration operation.
 *
 * @param description human-readable operation description
 * @param changes schema changes produced by the operation, if any
 * @param affectedRows number of rows affected, or zero for a schema-only or completed operation
 */
public record MigrationOperationResult(String description, List<SchemaChange> changes, long affectedRows) {
    /**
     * Creates an operation result.
     *
     * @param description human-readable operation description
     * @param changes schema changes produced by the operation, if any; {@code null} is treated as an empty list
     * @param affectedRows number of rows affected, or zero for a schema-only or completed operation
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public MigrationOperationResult {
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("operation description is required");
        changes = List.copyOf(changes == null ? List.of() : changes);
    }

    /**
     * Creates a completed operation result with no schema changes or affected rows.
     *
     * @param description human-readable operation description
     * @return completed operation result
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public static MigrationOperationResult completed(final String description) {
        return new MigrationOperationResult(description, List.of(), 0);
    }

    /**
     * Creates an operation result for schema changes.
     *
     * @param description human-readable operation description
     * @param changes schema changes produced by the operation
     * @return operation result with the supplied schema changes
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public static MigrationOperationResult schema(final String description, final List<SchemaChange> changes) {
        return new MigrationOperationResult(description, changes, 0);
    }

    /**
     * Creates an operation result for affected rows.
     *
     * @param description human-readable operation description
     * @param affectedRows number of rows affected
     * @return operation result with the supplied row count
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public static MigrationOperationResult rows(final String description, final long affectedRows) {
        return new MigrationOperationResult(description, List.of(), affectedRows);
    }
}
