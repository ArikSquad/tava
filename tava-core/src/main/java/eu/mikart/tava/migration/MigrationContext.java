package eu.mikart.tava.migration;

import eu.mikart.tava.Entity;
import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.spi.NativeAccess;
import java.util.Objects;

/** Database access and the same chronological previous/next snapshots for either migration direction. */
public final class MigrationContext {
    private final Tava database;
    private final Schema previous;
    private final Schema next;

    MigrationContext(Tava database, Schema previous, Schema next) {
        this.database = Objects.requireNonNull(database);
        this.previous = previous;
        this.next = next;
    }

    /**
     * Returns the preceding version's schema, empty for the initial version.
     *
     * @return the preceding version's schema, empty for the initial version
     */
    public Schema previous() {
        return previous;
    }
    /**
     * Returns the newer version's schema, also when executing its down actions.
     *
     * @return the newer version's schema, also when executing its down actions
     */
    public Schema next() {
        return next;
    }
    /**
     * Returns the database being migrated.
     *
     * @return the database being migrated
     */
    public Tava database() {
        return database;
    }

    /**
     * Opens dynamic records for schema or data transformations.
     *
     * @param entity storage name
     * @return record operations
     */
    public Records records(String entity) {
        return database.records(entity);
    }

    /**
     * Opens typed records for data transformations.
     *
     * @param <T> record type
     * @param type record class
     * @return entity operations
     */
    public <T extends Record> Entity<T> entity(Class<T> type) {
        return database.entity(type);
    }

    /**
     * Opens typed records with an explicit storage name.
     *
     * @param <T> record type
     * @param name storage name
     * @param type record class
     * @return entity operations
     */
    public <T extends Record> Entity<T> entity(String name, Class<T> type) {
        return database.entity(name, type);
    }

    /**
     * Returns scoped access to the adapter's native handles.
     *
     * @return scoped access to the adapter's native handles
     */
    public NativeAccess nativeAccess() {
        return database.nativeAccess();
    }

    /**
     * Applies safe additive schema changes. Removals and unsupported changes require explicit native actions.
     *
     * @param schema desired schema, including any intermediate shape required by a backfill
     */
    public void apply(Schema schema) {
        database.schema().ensure(schema);
    }

    /**
     * Applies schema changes with named risk allowances.
     *
     * @param schema desired schema
     * @param options allowed risks
     */
    public void apply(Schema schema, ApplyOptions options) {
        database.schema().update(schema, options);
    }

    /**
     * Applies explicit entity and field renames with safe risk allowances.
     *
     * @param schema desired schema
     * @param renames explicit rename mappings
     */
    public void apply(Schema schema, SchemaRenames renames) {
        database.plan(schema, renames).apply();
    }
}
