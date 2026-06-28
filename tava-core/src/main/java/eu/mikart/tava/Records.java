package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import org.jetbrains.annotations.NotNull;

public final class Records {
    private final String entity;
    private final EntityStore store;

    Records(final @NotNull String entity, final @NotNull EntityStore store) {
        this.entity = entity;
        this.store = store;
    }

    public @NotNull EntityRecord insert(final @NotNull EntityRecord record) {
        return store.insert(entity, record);
    }

    public @NotNull Page<EntityRecord> find(final @NotNull Query query) {
        return store.find(entity, query);
    }

    public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(entity, predicate, mutation);
    }

    public long delete(final @NotNull Predicate predicate) {
        return store.delete(entity, predicate);
    }
}
