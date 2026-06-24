package eu.mikart.tava.migration;

import eu.mikart.tava.Entity;
import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.spi.NativeAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class MigrationContext {
    private final Tava tava;
    private final ApplyOptions options;
    private final List<MigrationOperationResult> operations;

    MigrationContext(final Tava tava, final ApplyOptions options) {
        this.tava = Objects.requireNonNull(tava, "tava is required");
        this.options = options == null ? ApplyOptions.safe() : options;
        this.operations = new ArrayList<>();
    }

    public Tava tava() {
        return tava;
    }

    public Records records(final String entity) {
        return tava.records(entity);
    }

    public <T extends Record> Entity<T> entity(final Class<T> type) {
        return tava.entity(type);
    }

    public <T extends Record> Entity<T> entity(final String name, final Class<T> type) {
        return tava.entity(name, type);
    }

    public NativeAccess nativeAccess() {
        return tava.nativeAccess();
    }

    public <T> T nativeHandle(final Class<T> type) {
        return tava.nativeHandle(type);
    }

    public void apply(final Schema schema) {
        apply("Apply schema", schema);
    }

    public void apply(final String description, final Schema schema) {
        final var plan = tava.plan(schema);
        plan.apply(options);
        operations.add(MigrationOperationResult.schema(description, plan.changes()));
    }

    public EntityRecord insert(final String entity, final EntityRecord record) {
        final EntityRecord inserted = records(entity).insert(record);
        operations.add(MigrationOperationResult.rows("Insert into " + entity, 1));
        return inserted;
    }

    public long update(final String entity, final Predicate predicate, final Mutation mutation) {
        final long affected = records(entity).update(predicate, mutation);
        operations.add(MigrationOperationResult.rows("Update " + entity, affected));
        return affected;
    }

    public long delete(final String entity, final Predicate predicate) {
        final long affected = records(entity).delete(predicate);
        operations.add(MigrationOperationResult.rows("Delete from " + entity, affected));
        return affected;
    }

    public long forEach(final String entity, final Query query, final Consumer<EntityRecord> action) {
        Objects.requireNonNull(action, "action is required");
        long count = 0;
        String cursor = null;
        do {
            final Query pageQuery = new Query(query == null ? Predicate.all() : query.predicate(),
                    query == null ? List.of() : query.projection(),
                    query == null ? List.of() : query.sorting(),
                    query == null || query.limit() == 0 ? 500 : query.limit(),
                    cursor);
            final var page = records(entity).find(pageQuery);
            for (final EntityRecord record : page.items()) {
                action.accept(record);
                count++;
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        operations.add(MigrationOperationResult.rows("Iterate " + entity, count));
        return count;
    }

    public void operation(final String description, final Runnable action) {
        Objects.requireNonNull(action, "action is required");
        final int before = operations.size();
        action.run();
        if (operations.size() == before) operations.add(MigrationOperationResult.completed(description));
    }

    List<MigrationOperationResult> operations() {
        return List.copyOf(operations);
    }
}
