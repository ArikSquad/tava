package eu.mikart.tava.migration;

import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.LogicalType;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.schema.plan.SchemaChange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Migrations {
    public static final String DEFAULT_ENTITY = "_tava_migrations";

    private final Tava tava;
    private final String entity;
    private final ApplyOptions options;

    public Migrations(final Tava tava) {
        this(tava, DEFAULT_ENTITY, ApplyOptions.safe());
    }

    public Migrations(final Tava tava, final String entity, final ApplyOptions options) {
        if (entity == null || entity.isBlank()) throw new IllegalArgumentException("migration entity is required");
        this.tava = Objects.requireNonNull(tava, "tava is required");
        this.entity = entity;
        this.options = options == null ? ApplyOptions.safe() : options;
    }

    public List<MigrationResult> apply(final Migration... migrations) {
        return apply(Arrays.asList(migrations));
    }

    public List<MigrationResult> apply(final List<Migration> migrations) {
        ensureStore();
        final Set<String> applied = appliedIds();
        final List<MigrationResult> results = new ArrayList<>();
        for (final Migration migration : migrations) {
            if (applied.contains(migration.id())) {
                results.add(new MigrationResult(migration.id(), false, List.of(), List.of()));
                continue;
            }
            final List<MigrationOperationResult> operations = run(migration.up());
            records().insert(EntityRecord.builder()
                    .set("id", migration.id())
                    .set("appliedAt", Instant.now())
                    .build());
            applied.add(migration.id());
            results.add(result(migration.id(), true, operations));
        }
        return results;
    }

    public MigrationResult rollback(final Migration migration) {
        if (!migration.reversible()) {
            throw new IllegalArgumentException("Migration " + migration.id() + " does not define a down schema");
        }
        ensureStore();
        if (!appliedIds().contains(migration.id())) {
            return new MigrationResult(migration.id(), false, List.of(), List.of());
        }
        final List<MigrationOperationResult> operations = run(migration.down());
        records().delete(Predicate.eq("id", migration.id()));
        return result(migration.id(), true, operations);
    }

    public List<String> applied() {
        ensureStore();
        return List.copyOf(appliedIds());
    }

    public List<Migration> pending(final List<Migration> migrations) {
        ensureStore();
        final Set<String> applied = appliedIds();
        return migrations.stream().filter(migration -> !applied.contains(migration.id())).toList();
    }

    private List<MigrationOperationResult> run(final List<MigrationStep> steps) {
        final MigrationContext context = new MigrationContext(tava, options);
        for (final MigrationStep step : steps) step.run(context);
        return context.operations();
    }

    private static MigrationResult result(
            final String id,
            final boolean applied,
            final List<MigrationOperationResult> operations
    ) {
        final List<SchemaChange> changes = operations.stream()
                .flatMap(operation -> operation.changes().stream())
                .toList();
        return new MigrationResult(id, applied, changes, operations);
    }

    private void ensureStore() {
        tava.plan(metadataSchema()).apply(options);
    }

    private Set<String> appliedIds() {
        final Set<String> ids = new LinkedHashSet<>();
        String cursor = null;
        do {
            final var page = records().find(Query.builder().project("id").sort(Sort.asc("id")).cursor(cursor).build());
            page.items().forEach(record -> ids.add(String.valueOf(record.get("id"))));
            cursor = page.nextCursor();
        } while (cursor != null);
        return ids;
    }

    private Records records() {
        return tava.records(entity);
    }

    private Schema metadataSchema() {
        return Schema.builder().entity(metadataEntity()).build();
    }

    private eu.mikart.tava.schema.EntityDefinition metadataEntity() {
        return Schema.builder().entity(entity, value -> {
            value.string("id").identity().field(255);
            value.field("appliedAt", eu.mikart.tava.schema.FieldType.of(LogicalType.INSTANT))
                    .notNull().generated(GeneratedValue.NOW);
        }).build().entity(entity);
    }
}
