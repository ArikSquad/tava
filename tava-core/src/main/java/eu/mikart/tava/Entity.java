package eu.mikart.tava;

import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.spi.EntityStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.NotNull;

/**
 * Provides record operations for one entity type. Methods marked {@link Blocking} wait for store I/O on the calling
 * thread; asynchronous methods submit the operation to the configured executor.
 *
 * @param <T> record type managed by this entity
 */
public final class Entity<T extends Record> {
    private final String name;
    private final EntityStore store;
    private final RecordMapper<T> mapper;
    private final Executor executor;

    Entity(
            final @NotNull String name,
            final @NotNull EntityStore store,
            final @NotNull RecordMapper<T> mapper,
            final @NotNull Executor executor) {
        this.name = name;
        this.store = store;
        this.mapper = mapper;
        this.executor = executor;
    }

    /**
     * Inserts a record and returns the stored record, including generated values.
     *
     * @param record record to insert
     * @return stored record
     */
    @Blocking
    public @NotNull T insert(final @NotNull T record) {
        return mapper.read(store.insert(name, mapper.write(record)));
    }

    /**
     * Inserts a batch of records and returns the stored records in input order.
     *
     * @param records records to insert
     * @return stored records
     */
    @Blocking
    public @NotNull List<T> insert(final @NotNull List<T> records) {
        return store.insert(name, records.stream().map(mapper::write).toList()).stream()
                .map(mapper::read)
                .toList();
    }

    /**
     * Finds a page of records matching a query.
     *
     * @param query filter, projection, ordering, and page options
     * @return records and the cursor for the next page, if present
     */
    @Blocking
    public @NotNull Page<T> find(final @NotNull Query query) {
        final var page = store.find(name, mapper.write(query));
        return new Page<>(page.items().stream().map(mapper::read).toList(), page.nextCursor());
    }

    /**
     * Finds at most one record matching a predicate.
     *
     * @param predicate condition to match
     * @return the first matching record, or empty if none matches
     */
    @Blocking
    public @NotNull Optional<T> findOne(final @NotNull Predicate predicate) {
        return find(Query.builder().where(predicate).limit(1).build()).items().stream()
                .findFirst();
    }

    /**
     * Applies a mutation to records matching a predicate.
     *
     * @param predicate condition selecting records to update
     * @param mutation changes to apply
     * @return number of records reported as updated by the store
     */
    @Blocking
    public long update(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.update(name, mapper.write(predicate), mapper.write(mutation));
    }

    /**
     * Deletes records matching a predicate.
     *
     * @param predicate condition selecting records to delete
     * @return number of records reported as deleted by the store
     */
    @Blocking
    public long delete(final @NotNull Predicate predicate) {
        return store.delete(name, mapper.write(predicate));
    }

    /**
     * Finds a record by a scalar identity value.
     *
     * @param id value of the entity's single identity field
     * @return the matching record, or empty if none exists
     */
    @Blocking
    public @NotNull Optional<T> findById(final @NotNull Object id) {
        return findById(mapper.identity(id));
    }

    /**
     * Finds a record by its identity, including composite identities.
     *
     * @param id identity values to match
     * @return the matching record, or empty if none exists
     */
    @Blocking
    public @NotNull Optional<T> findById(final @NotNull Identity id) {
        return findOne(id.predicate());
    }

    /**
     * Checks whether a record with a scalar identity value exists.
     *
     * @param id value of the entity's single identity field
     * @return {@code true} if a matching record exists
     */
    @Blocking
    public boolean existsById(final @NotNull Object id) {
        return findById(id).isPresent();
    }

    /**
     * Checks whether a record with the given identity exists.
     *
     * @param id identity values to match
     * @return {@code true} if a matching record exists
     */
    @Blocking
    public boolean existsById(final @NotNull Identity id) {
        return findById(id).isPresent();
    }

    /**
     * Updates a record identified by a scalar identity value.
     *
     * @param id value of the entity's single identity field
     * @param mutation changes to apply
     * @return detailed update outcome
     */
    @Blocking
    public @NotNull MutationResult updateById(final @NotNull Object id, final @NotNull Mutation mutation) {
        return updateById(mapper.identity(id), mutation);
    }

    /**
     * Updates a record identified by an identity, including a composite identity.
     *
     * @param id identity values to match
     * @param mutation changes to apply
     * @return detailed update outcome
     */
    @Blocking
    public @NotNull MutationResult updateById(final @NotNull Identity id, final @NotNull Mutation mutation) {
        return store.updateResult(name, mapper.write(id.predicate()), mapper.write(mutation));
    }

    /**
     * Deletes a record identified by a scalar identity value.
     *
     * @param id value of the entity's single identity field
     * @return detailed delete outcome
     */
    @Blocking
    public @NotNull MutationResult deleteById(final @NotNull Object id) {
        return deleteById(mapper.identity(id));
    }

    /**
     * Deletes a record identified by an identity, including a composite identity.
     *
     * @param id identity values to match
     * @return detailed delete outcome
     */
    @Blocking
    public @NotNull MutationResult deleteById(final @NotNull Identity id) {
        return store.deleteResult(name, mapper.write(id.predicate()));
    }

    /**
     * Attempts to insert a record and returns the detailed outcome.
     *
     * @param record record to insert
     * @return insert outcome, including any reported conflicts
     */
    @Blocking
    public @NotNull MutationResult insertResult(final @NotNull T record) {
        return store.insertResult(name, mapper.write(record));
    }

    /**
     * Attempts to insert a record and reports a conflict for an existing unique key.
     *
     * @param record record to insert
     * @return insert outcome, including any reported conflicts
     */
    @Blocking
    public @NotNull MutationResult insertIfAbsent(final @NotNull T record) {
        return insertResult(record);
    }

    /**
     * Applies a mutation and returns the detailed outcome.
     *
     * @param predicate condition selecting records to update
     * @param mutation changes to apply
     * @return detailed update outcome
     */
    @Blocking
    public @NotNull MutationResult updateResult(final @NotNull Predicate predicate, final @NotNull Mutation mutation) {
        return store.updateResult(name, mapper.write(predicate), mapper.write(mutation));
    }

    /**
     * Inserts a record or updates the existing record with the same identity. Requires store support for upserts.
     *
     * @param record record to insert or use as the update source
     * @return detailed insert or update outcome
     */
    @Blocking
    public @NotNull MutationResult upsert(final @NotNull T record) {
        return store.upsert(
                name, mapper.write(mapper.identity(record).predicate()), mapper.write(record), mapper.update(record));
    }

    /**
     * Finds a record by a scalar identity value on the configured executor. The returned future completes exceptionally
     * if the lookup fails.
     *
     * @param id value of the entity's single identity field
     * @return a future containing the matching record, or empty if none exists
     */
    public @NotNull CompletableFuture<Optional<T>> findByIdAsync(final @NotNull Object id) {
        return CompletableFuture.supplyAsync(() -> findById(id), executor);
    }

    /**
     * Inserts a record on the configured executor. The returned future completes exceptionally if the insert fails.
     *
     * @param record record to insert
     * @return a future containing the stored record
     */
    public @NotNull CompletableFuture<T> insertAsync(final @NotNull T record) {
        return CompletableFuture.supplyAsync(() -> insert(record), executor);
    }

    /**
     * Inserts or updates a record on the configured executor. The returned future completes exceptionally if the upsert
     * fails.
     *
     * @param record record to insert or use as the update source
     * @return a future containing the detailed outcome
     */
    public @NotNull CompletableFuture<MutationResult> upsertAsync(final @NotNull T record) {
        return CompletableFuture.supplyAsync(() -> upsert(record), executor);
    }
}
