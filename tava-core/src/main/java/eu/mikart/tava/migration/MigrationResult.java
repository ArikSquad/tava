package eu.mikart.tava.migration;

import eu.mikart.tava.schema.plan.SchemaChange;

import java.util.List;

public record MigrationResult(
        String id,
        boolean applied,
        List<SchemaChange> changes,
        List<MigrationOperationResult> operations
) {
    public MigrationResult {
        changes = List.copyOf(changes == null ? List.of() : changes);
        operations = List.copyOf(operations == null ? List.of() : operations);
    }

    public MigrationResult(final String id, final boolean applied, final List<SchemaChange> changes) {
        this(id, applied, changes, changes.stream()
                .map(change -> MigrationOperationResult.schema(change.description(), List.of(change)))
                .toList());
    }
}
