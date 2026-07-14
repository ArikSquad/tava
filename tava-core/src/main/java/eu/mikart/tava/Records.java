package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import org.jetbrains.annotations.NotNull;
import java.util.Optional;

public final class Records {
    private final String entity;
    private final EntityStore store;

    Records(final @NotNull String entity, final @NotNull EntityStore store) {
        this.entity = entity;
        this.store = store;
    }

    @Blocking public @NotNull EntityRecord insert(final @NotNull EntityRecord record) {
        return store.insert(entity, record);
    }

    @Blocking public @NotNull Page<EntityRecord> find(final @NotNull Query query) {
        return store.find(entity, query);
    }

    @Blocking public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(entity, predicate, mutation);
    }

    @Blocking public long delete(final @NotNull Predicate predicate) {
        return store.delete(entity, predicate);
    }

    @Blocking public @NotNull Optional<EntityRecord> findById(final @NotNull Identity id) { return find(Query.builder().where(id.predicate()).limit(1).build()).items().stream().findFirst(); }
    @Blocking public boolean existsById(final @NotNull Identity id) { return findById(id).isPresent(); }
    @Blocking public @NotNull MutationResult updateById(final @NotNull Identity id, final @NotNull Mutation mutation) { return store.updateResult(entity, id.predicate(), mutation); }
    @Blocking public @NotNull MutationResult deleteById(final @NotNull Identity id) { return store.deleteResult(entity, id.predicate()); }
    @Blocking public @NotNull MutationResult upsert(final @NotNull Identity id, final @NotNull EntityRecord insert, final @NotNull Mutation update) { return store.upsert(entity, id.predicate(), insert, update); }
}
