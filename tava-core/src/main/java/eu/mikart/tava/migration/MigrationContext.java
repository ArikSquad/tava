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
import java.util.function.Function;

/** Provides database operations and their results to a migration step. */
public final class MigrationContext {
    private final Tava tava;
    private final ApplyOptions options;
    private final List<MigrationOperationResult> operations;

    MigrationContext(final Tava tava, final ApplyOptions options) {
        this.tava = Objects.requireNonNull(tava, "tava is required");
        this.options = options == null ? ApplyOptions.safe() : options;
        this.operations = new ArrayList<>();
    }

    /**
     * Returns the Tava facade used by this migration.
     *
     * @return migration's Tava facade
     */
    public Tava tava() {
        return tava;
    }

    /**
     * Returns dynamic record operations for a named entity.
     *
     * @param entity entity name
     * @return operations for the named entity
     */
    public Records records(final String entity) {
        return tava.records(entity);
    }

    /**
     * Returns typed operations for the entity inferred from a record type.
     *
     * @param <T> record type
     * @param type record class
     * @return typed operations for the inferred entity
     */
    public <T extends Record> Entity<T> entity(final Class<T> type) {
        return tava.entity(type);
    }

    /**
     * Returns typed operations for a record type and explicit entity name.
     *
     * @param <T> record type
     * @param name entity name
     * @param type record class
     * @return typed operations for the named entity
     */
    public <T extends Record> Entity<T> entity(final String name, final Class<T> type) {
        return tava.entity(name, type);
    }

    /**
     * Returns access to adapter-specific native operations.
     *
     * @return native access facade
     */
    public NativeAccess nativeAccess() {
        return tava.nativeAccess();
    }

    /**
     * Returns the adapter's native handle as the requested type.
     *
     * @param <T> native handle type
     * @param type class of the requested native handle
     * @return native handle
     * @deprecated Use {@link #nativeAccess()} and {@link NativeAccess#withNative(Class, Function)} so the adapter can
     *     scope handle use to a callback.
     */
    @Deprecated(since = "1.3.0", forRemoval = false)
    @SuppressWarnings("deprecation")
    public <T> T nativeHandle(final Class<T> type) {
        return tava.nativeHandle(type);
    }

    /**
     * Applies a schema using the migration's configured schema policy.
     *
     * @param schema desired schema
     */
    public void apply(final Schema schema) {
        apply("Apply schema", schema);
    }

    /**
     * Applies a schema and records the operation under a description.
     *
     * @param description operation description
     * @param schema desired schema
     */
    public void apply(final String description, final Schema schema) {
        final var plan = tava.plan(schema);
        plan.apply(options);
        operations.add(MigrationOperationResult.schema(description, plan.changes()));
    }

    /**
     * Inserts one record into a named entity.
     *
     * @param entity entity name
     * @param record record to insert
     * @return inserted record
     */
    public EntityRecord insert(final String entity, final EntityRecord record) {
        final EntityRecord inserted = records(entity).insert(record);
        operations.add(MigrationOperationResult.rows("Insert into " + entity, 1));
        return inserted;
    }

    /**
     * Applies a mutation to records matching a predicate.
     *
     * @param entity entity name
     * @param predicate condition to match
     * @param mutation changes to apply
     * @return number of changed records
     */
    public long update(final String entity, final Predicate predicate, final Mutation mutation) {
        final long affected = records(entity).update(predicate, mutation);
        operations.add(MigrationOperationResult.rows("Update " + entity, affected));
        return affected;
    }

    /**
     * Deletes records matching a predicate.
     *
     * @param entity entity name
     * @param predicate condition to match
     * @return number of deleted records
     */
    public long delete(final String entity, final Predicate predicate) {
        final long affected = records(entity).delete(predicate);
        operations.add(MigrationOperationResult.rows("Delete from " + entity, affected));
        return affected;
    }

    /**
     * Applies an action to every record returned by a paged query.
     *
     * @param entity entity name
     * @param query query to page through, or {@code null} to query all records
     * @param action action invoked for each returned record
     * @return number of records passed to the action
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public long forEach(final String entity, final Query query, final Consumer<EntityRecord> action) {
        Objects.requireNonNull(action, "action is required");
        long count = 0;
        String cursor = null;
        do {
            final Query pageQuery = new Query(
                    query == null ? Predicate.all() : query.predicate(),
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

    /**
     * Runs an arbitrary action and records it if the action records no operations itself.
     *
     * @param description operation description used when recording the action
     * @param action action to run
     * @throws NullPointerException if {@code action} is {@code null}
     */
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
