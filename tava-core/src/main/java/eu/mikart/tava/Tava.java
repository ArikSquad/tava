package eu.mikart.tava;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class Tava implements AutoCloseable {
    private final Adapter adapter;

    private Tava(final @NotNull Adapter adapter) {
        this.adapter = Objects.requireNonNull(adapter);
    }

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

    public @NotNull SchemaPlan plan(final @NotNull Schema desired) {
        return schema().plan(desired);
    }

    public <T extends Record> @NotNull Entity<T> entity(final @NotNull Class<T> type) {
        return new Entity<>(RecordSchemas.describe(type).name(), adapter.entities(), type);
    }

    public <T extends Record> @NotNull Entity<T> entity(final @NotNull String name, final @NotNull Class<T> type) {
        return new Entity<>(name, adapter.entities(), type);
    }

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
