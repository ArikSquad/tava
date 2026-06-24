package eu.mikart.tava;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.migration.Migrations;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;

import java.util.Objects;

public final class Tava implements AutoCloseable {
    private final Adapter adapter;

    private Tava(Adapter adapter) {
        this.adapter = Objects.requireNonNull(adapter);
    }

    public static Tava open(Adapter adapter) {
        return new Tava(adapter);
    }

    public String adapterName() {
        return adapter.name();
    }

    public Capabilities capabilities() {
        return adapter.capabilities();
    }

    public SchemaManager schema() {
        return adapter.schemas();
    }

    public SchemaPlan plan(Schema desired) {
        return schema().plan(desired);
    }

    public Migrations migrations() {
        return new Migrations(this);
    }

    public <T extends Record> Entity<T> entity(Class<T> type) {
        return new Entity<>(RecordSchemas.describe(type).name(), adapter.entities(), type);
    }

    public <T extends Record> Entity<T> entity(String name, Class<T> type) {
        return new Entity<>(name, adapter.entities(), type);
    }

    public Records records(String entity) {
        return new Records(entity, adapter.entities());
    }

    public NativeAccess nativeAccess() {
        return adapter.nativeAccess();
    }

    public <T> T nativeHandle(Class<T> type) {
        return nativeAccess().nativeHandle(type);
    }

    Adapter adapter() {
        return adapter;
    }

    @Override
    public void close() {
        adapter.close();
    }
}
