package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Provides dynamic reads and writes for one named entity. */
public final class Records {
    private final String entity;
    private final EntityStore store;

    Records(final @NotNull String entity, final @NotNull EntityStore store) {
        this.entity = entity;
        this.store = store;
    }

    /**
     * Inserts one record.
     *
     * @param record record to insert
     * @return inserted record
     */
    @Blocking
    public @NotNull EntityRecord insert(final @NotNull EntityRecord record) {
        return store.insert(entity, record);
    }

    /**
     * Inserts a batch of records.
     *
     * @param records records to insert
     * @return the inserted records, in input order
     * @since 1.3.0
     */
    @Blocking
    public @NotNull List<EntityRecord> insert(final @NotNull List<EntityRecord> records) {
        return store.insert(entity, records);
    }

    /**
     * Finds a page of records matching a query.
     *
     * @param query filters, ordering, and page options
     * @return matching records and an optional continuation cursor
     */
    @Blocking
    public @NotNull Page<EntityRecord> find(final @NotNull Query query) {
        return store.find(entity, query);
    }

    /**
     * Returns the first record matching a predicate.
     *
     * @param predicate condition to match
     * @return matching record, if any
     */
    @Blocking
    public @NotNull Optional<EntityRecord> findOne(final @NotNull Predicate predicate) {
        return find(Query.builder().where(predicate).limit(1).build()).items().stream()
                .findFirst();
    }

    /**
     * Applies a mutation to records matching a predicate.
     *
     * @param predicate condition to match
     * @param mutation changes to apply
     * @return number of changed records
     */
    @Blocking
    public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(entity, predicate, mutation);
    }

    /**
     * Deletes records matching a predicate.
     *
     * @param predicate condition to match
     * @return number of deleted records
     */
    @Blocking
    public long delete(final @NotNull Predicate predicate) {
        return store.delete(entity, predicate);
    }

    /**
     * Finds a record by its identity.
     *
     * @param id record identity
     * @return matching record, if any
     */
    @Blocking
    public @NotNull Optional<EntityRecord> findById(final @NotNull Identity id) {
        return findOne(id.predicate());
    }

    /**
     * Checks whether a record with the identity exists.
     *
     * @param id record identity
     * @return {@code true} if a record exists
     */
    @Blocking
    public boolean existsById(final @NotNull Identity id) {
        return findById(id).isPresent();
    }

    /**
     * Applies a mutation to the record with the identity.
     *
     * @param id record identity
     * @param mutation changes to apply
     * @return mutation outcome
     */
    @Blocking
    public @NotNull MutationResult updateById(final @NotNull Identity id, final @NotNull Mutation mutation) {
        return store.updateResult(entity, id.predicate(), mutation);
    }

    /**
     * Deletes the record with the identity.
     *
     * @param id record identity
     * @return mutation outcome
     */
    @Blocking
    public @NotNull MutationResult deleteById(final @NotNull Identity id) {
        return store.deleteResult(entity, id.predicate());
    }

    /**
     * Inserts or updates the record matching an identity.
     *
     * @param id record identity
     * @param insert values used when no record matches
     * @param update mutations applied to a matching record
     * @return mutation outcome
     */
    @Blocking
    public @NotNull MutationResult upsert(
            final @NotNull Identity id, final @NotNull EntityRecord insert, final @NotNull Mutation update) {
        return store.upsert(entity, id.predicate(), insert, update);
    }

    /**
     * Inserts one record and reports its outcome.
     *
     * @param record record to insert
     * @return insertion outcome, including a unique-key conflict
     */
    @Blocking
    public @NotNull MutationResult insertResult(final @NotNull EntityRecord record) {
        return store.insertResult(entity, record);
    }

    /**
     * Attempts one insert and reports a unique-key conflict instead of throwing.
     *
     * @param record record to insert
     * @return insertion outcome
     */
    @Blocking
    public @NotNull MutationResult insertIfAbsent(final @NotNull EntityRecord record) {
        return insertResult(record);
    }
}
