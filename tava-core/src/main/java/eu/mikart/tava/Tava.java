package eu.mikart.tava;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.migration.Migrations;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Main entry point for working with a Tava adapter.
 * <p>
 * A {@code Tava} instance owns the adapter passed to {@link #open(Adapter)} and closes it from
 * {@link #close()}. Create separate instances when you need independent adapter lifecycles.
 */
public final class Tava implements AutoCloseable {
    private final Adapter adapter;

    private Tava(final @NotNull Adapter adapter) {
        this.adapter = Objects.requireNonNull(adapter);
    }

    /**
     * Opens a Tava facade over the given adapter.
     *
     * @param adapter adapter implementation to use for schema, data, and native access
     * @return a facade that delegates all operations to {@code adapter}
     */
    public static @NotNull Tava open(final @NotNull Adapter adapter) {
        return new Tava(adapter);
    }

    public @NotNull String adapterName() {
        return adapter.name();
    }

    public @NotNull Capabilities capabilities() {
        return adapter.capabilities();
    }

    public @NotNull SchemaManager schema() {
        return adapter.schemas();
    }

    /**
     * Builds a schema plan by comparing the desired schema with the adapter's current schema.
     * The returned plan is inert until {@link SchemaPlan#apply()} is called.
     */
    public @NotNull SchemaPlan plan(final @NotNull Schema desired) {
        return schema().plan(desired);
    }

    public Migrations migrations() {
        return new Migrations(this);
    }

    /**
     * Returns a typed record facade for the entity inferred from the record annotations.
     * Use {@link #records(String)} when you need partial projections or dynamic field sets.
     */
    public <T extends Record> @NotNull Entity<T> entity(final @NotNull Class<T> type) {
        return new Entity<>(RecordSchemas.describe(type).name(), adapter.entities(), type);
    }

    /**
     * Returns a typed record facade for an explicit entity name.
     * Use {@link #records(String)} when you need partial projections or dynamic field sets.
     */
    public <T extends Record> @NotNull Entity<T> entity(final @NotNull String name, final @NotNull Class<T> type) {
        return new Entity<>(name, adapter.entities(), type);
    }

    /**
     * Returns an untyped record facade for dynamic records, partial projections, and schema-free workflows.
     *
     * @param entity name of the entity
     */
    public @NotNull Records records(final @NotNull String entity) {
        return new Records(entity, adapter.entities());
    }

    public @NotNull NativeAccess nativeAccess() {
        return adapter.nativeAccess();
    }

    public <T> @NotNull T nativeHandle(final @NotNull Class<T> type) {
        return nativeAccess().nativeHandle(type);
    }

    @NotNull Adapter adapter() {
        return adapter;
    }

    @Override
    public void close() {
        adapter.close();
    }
}
