package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;

public final class Records {
    private final String entity;
    private final EntityStore store;

    Records(String entity, EntityStore store) {
        this.entity = entity;
        this.store = store;
    }

    public EntityRecord insert(EntityRecord record) {
        return store.insert(entity, record);
    }

    public Page<EntityRecord> find(Query query) {
        return store.find(entity, query);
    }

    public long update(Predicate predicate, Mutation mutation) {
        return store.update(entity, predicate, mutation);
    }

    public long delete(Predicate predicate) {
        return store.delete(entity, predicate);
    }
}
