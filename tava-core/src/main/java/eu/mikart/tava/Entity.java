package eu.mikart.tava;

import eu.mikart.tava.data.Page;
import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class Entity<T extends Record> {
    private final String name;
    private final EntityStore store;
    private final RecordMapper<T> mapper;
    private final Executor executor;

    Entity(final @NotNull String name, final @NotNull EntityStore store, final @NotNull Class<T> type, final @NotNull Executor executor) {
        this.name = name;
        this.store = store;
        this.mapper = new RecordMapper<>(type);
        this.executor = executor;
    }

    @Blocking public @NotNull T insert(final @NotNull T record) {
        return mapper.read(store.insert(name, mapper.write(record)));
    }

    @Blocking public @NotNull List<T> insert(final @NotNull List<T> records) {
        return store.insert(name, records.stream().map(mapper::write).toList()).stream().map(mapper::read).toList();
    }

    @Blocking public @NotNull Page<T> find(final @NotNull Query query) {
        final var page = store.find(name, query);
        return new Page<>(page.items().stream().map(mapper::read).toList(), page.nextCursor());
    }

    @Blocking public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(name, predicate, mutation);
    }

    @Blocking public long delete(final @NotNull Predicate predicate) {
        return store.delete(name, predicate);
    }

    @Blocking public @NotNull Optional<T> findById(final @NotNull Object id) { return findById(mapper.identity(id)); }
    @Blocking public @NotNull Optional<T> findById(final @NotNull Identity id) {
        return find(Query.builder().where(id.predicate()).limit(1).build()).items().stream().findFirst();
    }
    @Blocking public boolean existsById(final @NotNull Object id) { return findById(id).isPresent(); }
    @Blocking public boolean existsById(final @NotNull Identity id) { return findById(id).isPresent(); }
    @Blocking public @NotNull MutationResult updateById(final @NotNull Object id, final @NotNull Mutation mutation) { return updateById(mapper.identity(id), mutation); }
    @Blocking public @NotNull MutationResult updateById(final @NotNull Identity id, final @NotNull Mutation mutation) { return store.updateResult(name, id.predicate(), mutation); }
    @Blocking public @NotNull MutationResult deleteById(final @NotNull Object id) { return deleteById(mapper.identity(id)); }
    @Blocking public @NotNull MutationResult deleteById(final @NotNull Identity id) { return store.deleteResult(name, id.predicate()); }
    @Blocking public @NotNull MutationResult insertResult(final @NotNull T record) { return store.insertResult(name, mapper.write(record)); }
    @Blocking public @NotNull MutationResult updateResult(final @NotNull Predicate predicate, final @NotNull Mutation mutation) { return store.updateResult(name, predicate, mutation); }
    @Blocking public @NotNull MutationResult upsert(final @NotNull T record) {
        return store.upsert(name, mapper.identity(record).predicate(), mapper.write(record), mapper.update(record));
    }
    public @NotNull CompletableFuture<Optional<T>> findByIdAsync(final @NotNull Object id) { return CompletableFuture.supplyAsync(() -> findById(id), executor); }
    public @NotNull CompletableFuture<T> insertAsync(final @NotNull T record) { return CompletableFuture.supplyAsync(() -> insert(record), executor); }
    public @NotNull CompletableFuture<MutationResult> upsertAsync(final @NotNull T record) { return CompletableFuture.supplyAsync(() -> upsert(record), executor); }
}
