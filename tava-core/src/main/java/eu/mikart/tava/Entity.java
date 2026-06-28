package eu.mikart.tava;

import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class Entity<T extends Record> {
    private final String name;
    private final EntityStore store;
    private final RecordMapper<T> mapper;

    Entity(final @NotNull String name, final @NotNull EntityStore store, final @NotNull Class<T> type) {
        this.name = name;
        this.store = store;
        this.mapper = new RecordMapper<>(type);
    }

    public @NotNull T insert(final @NotNull T record) {
        return mapper.read(store.insert(name, mapper.write(record)));
    }

    public @NotNull List<T> insert(final @NotNull List<T> records) {
        return store.insert(name, records.stream().map(mapper::write).toList()).stream().map(mapper::read).toList();
    }

    public @NotNull Page<T> find(final @NotNull Query query) {
        final var page = store.find(name, query);
        return new Page<>(page.items().stream().map(mapper::read).toList(), page.nextCursor());
    }

    public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(name, predicate, mutation);
    }

    public long delete(final @NotNull Predicate predicate) {
        return store.delete(name, predicate);
    }
}
