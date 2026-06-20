package eu.mikart.tava.spi;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;

import java.util.List;

public interface EntityStore {
    EntityRecord insert(String entity, EntityRecord record);

    default List<EntityRecord> insert(String entity, List<EntityRecord> records) {
        return records.stream().map(record -> insert(entity, record)).toList();
    }

    Page<EntityRecord> find(String entity, Query query);

    long update(String entity, Predicate predicate, Mutation mutation);

    long delete(String entity, Predicate predicate);
}
