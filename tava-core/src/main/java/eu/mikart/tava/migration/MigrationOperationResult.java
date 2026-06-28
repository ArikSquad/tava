package eu.mikart.tava.migration;

import eu.mikart.tava.schema.plan.SchemaChange;

import java.util.List;

public record MigrationOperationResult(String description, List<SchemaChange> changes, long affectedRows) {
    public MigrationOperationResult {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("operation description is required");
        changes = List.copyOf(changes == null ? List.of() : changes);
    }

    public static MigrationOperationResult completed(final String description) {
        return new MigrationOperationResult(description, List.of(), 0);
    }

    public static MigrationOperationResult schema(final String description, final List<SchemaChange> changes) {
        return new MigrationOperationResult(description, changes, 0);
    }

    public static MigrationOperationResult rows(final String description, final long affectedRows) {
        return new MigrationOperationResult(description, List.of(), affectedRows);
    }
}
