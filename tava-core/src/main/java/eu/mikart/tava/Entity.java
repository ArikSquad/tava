package eu.mikart.tava;

import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;

import java.util.List;

public final class Entity<T extends Record> {
    private final String name;
    private final EntityStore store;
    private final RecordMapper<T> mapper;

    Entity(String name, EntityStore store, Class<T> type) {
        this.name = name;
        this.store = store;
        this.mapper = new RecordMapper<>(type);
    }

    public T insert(T record) {
        return mapper.read(store.insert(name, mapper.write(record)));
    }

    public List<T> insert(List<T> records) {
        return store.insert(name, records.stream().map(mapper::write).toList()).stream().map(mapper::read).toList();
    }

    public Page<T> find(Query query) {
        var page = store.find(name, query);
        return new Page<>(page.items().stream().map(mapper::read).toList(), page.nextCursor());
    }

    public long update(Predicate predicate, Mutation mutation) {
        return store.update(name, predicate, mutation);
    }

    public long delete(Predicate predicate) {
        return store.delete(name, predicate);
    }
}
