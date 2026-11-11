package eu.mikart.tava;

import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Pages;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.QueryDiagnostics;
import eu.mikart.tava.schema.FieldDefinition;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.spi.EntityStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
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
        try (var results = stream(Query.builder().where(predicate).limit(1).build())) {
            return results.findFirst();
        }
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
        return exists(mapper.identity(id).predicate());
    }

    /**
     * Checks whether a record with the given identity exists.
     *
     * @param id identity values to match
     * @return {@code true} if a matching record exists
     */
    @Blocking
    public boolean existsById(final @NotNull Identity id) {
        return exists(id.predicate());
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
        return Async.submit(() -> findById(id), executor);
    }

    /**
     * Inserts a record on the configured executor. The returned future completes exceptionally if the insert fails.
     *
     * @param record record to insert
     * @return a future containing the stored record
     */
    public @NotNull CompletableFuture<T> insertAsync(final @NotNull T record) {
        return Async.submit(() -> insert(record), executor);
    }

    /**
     * Inserts or updates a record on the configured executor. The returned future completes exceptionally if the upsert
     * fails.
     *
     * @param record record to insert or use as the update source
     * @return a future containing the detailed outcome
     */
    public @NotNull CompletableFuture<MutationResult> upsertAsync(final @NotNull T record) {
        return Async.submit(() -> upsert(record), executor);
    }
    /**
     * Counts matching records without materializing entity objects.
     *
     * @param predicate matching condition
     * @return matching count
     * @since 1.3.0
     */
    @Blocking
    public long count(Predicate predicate) {
        return store.count(name, mapper.write(predicate));
    }

    /**
     * Checks whether any record matches.
     *
     * @param predicate matching condition
     * @return whether a match exists
     * @since 1.3.0
     */
    @Blocking
    public boolean exists(Predicate predicate) {
        return store.exists(name, mapper.write(predicate));
    }

    /**
     * Lazily follows query cursors, fetching only when the stream is consumed.
     *
     * @param query initial page request
     * @return sequential stream
     * @since 1.3.0
     */
    public Stream<T> stream(Query query) {
        return Pages.stream(query, this::find);
    }

    /**
     * Describes the adapter access path and effective ordering.
     *
     * @param query query to inspect
     * @return query diagnostics
     * @since 1.3.0
     */
    @Blocking
    public QueryDiagnostics explain(Query query) {
        return store.explain(name, mapper.write(query));
    }

    /**
     * Reads selected fields into a separate projection record type. Projection fields determine the selected columns.
     *
     * @param <P> projection record type
     * @param query filtering, ordering, and paging
     * @param projectionType projection class, using the same field names and codecs as stored data
     * @return projected page
     * @since 1.3.0
     */
    @Blocking
    public <P extends Record> Page<P> find(Query query, Class<P> projectionType) {
        var fields = RecordSchemas.describe(projectionType).fields().stream()
                .map(FieldDefinition::name)
                .toList();
        Query selected = new Query(query.predicate(), fields, query.sorting(), query.limit(), query.cursor());
        var page = store.find(name, mapper.write(selected));
        var projection = RecordMapper.of(projectionType);
        return new Page<>(page.items().stream().map(projection::read).toList(), page.nextCursor());
    }

    /**
     * Runs insert on the configured executor. Cancellation interrupts the worker.
     *
     * @param records operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<List<T>> insertAsync(List<T> records) {
        return Async.submit(() -> insert(records), executor);
    }

    /**
     * Runs find on the configured executor. Cancellation interrupts the worker.
     *
     * @param query operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Page<T>> findAsync(Query query) {
        return Async.submit(() -> find(query), executor);
    }

    /**
     * Runs findOne on the configured executor. Cancellation interrupts the worker.
     *
     * @param predicate operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Optional<T>> findOneAsync(Predicate predicate) {
        return Async.submit(() -> findOne(predicate), executor);
    }

    /**
     * Runs update on the configured executor. Cancellation interrupts the worker.
     *
     * @param predicate operation argument
     * @param mutation operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Long> updateAsync(Predicate predicate, Mutation mutation) {
        return Async.submit(() -> update(predicate, mutation), executor);
    }

    /**
     * Runs delete on the configured executor. Cancellation interrupts the worker.
     *
     * @param predicate operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Long> deleteAsync(Predicate predicate) {
        return Async.submit(() -> delete(predicate), executor);
    }

    /**
     * Runs findById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Optional<T>> findByIdAsync(Identity id) {
        return Async.submit(() -> findById(id), executor);
    }

    /**
     * Runs existsById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Boolean> existsByIdAsync(Object id) {
        return Async.submit(() -> existsById(id), executor);
    }

    /**
     * Runs existsById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Boolean> existsByIdAsync(Identity id) {
        return Async.submit(() -> existsById(id), executor);
    }

    /**
     * Runs updateById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @param mutation operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> updateByIdAsync(Object id, Mutation mutation) {
        return Async.submit(() -> updateById(id, mutation), executor);
    }

    /**
     * Runs updateById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @param mutation operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> updateByIdAsync(Identity id, Mutation mutation) {
        return Async.submit(() -> updateById(id, mutation), executor);
    }

    /**
     * Runs deleteById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> deleteByIdAsync(Object id) {
        return Async.submit(() -> deleteById(id), executor);
    }

    /**
     * Runs deleteById on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> deleteByIdAsync(Identity id) {
        return Async.submit(() -> deleteById(id), executor);
    }

    /**
     * Runs insertResult on the configured executor. Cancellation interrupts the worker.
     *
     * @param record operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> insertResultAsync(T record) {
        return Async.submit(() -> insertResult(record), executor);
    }

    /**
     * Runs insertIfAbsent on the configured executor. Cancellation interrupts the worker.
     *
     * @param record operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> insertIfAbsentAsync(T record) {
        return Async.submit(() -> insertIfAbsent(record), executor);
    }

    /**
     * Runs updateResult on the configured executor. Cancellation interrupts the worker.
     *
     * @param predicate operation argument
     * @param mutation operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> updateResultAsync(Predicate predicate, Mutation mutation) {
        return Async.submit(() -> updateResult(predicate, mutation), executor);
    }

    /**
     * Runs count on the configured executor.
     *
     * @param predicate matching condition
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Long> countAsync(Predicate predicate) {
        return Async.submit(() -> count(predicate), executor);
    }
    /**
     * Runs exists on the configured executor.
     *
     * @param predicate matching condition
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Boolean> existsAsync(Predicate predicate) {
        return Async.submit(() -> exists(predicate), executor);
    }
    /**
     * Reads a projection on the configured executor.
     *
     * @param <P> projection type
     * @param query page request
     * @param projectionType projection class
     * @return asynchronous projected page
     * @since 1.3.0
     */
    public <P extends Record> CompletableFuture<Page<P>> findAsync(Query query, Class<P> projectionType) {
        return Async.submit(() -> find(query, projectionType), executor);
    }
    /**
     * Describes query planning on the configured executor.
     *
     * @param query query to inspect
     * @return asynchronous diagnostics
     * @since 1.3.0
     */
    public CompletableFuture<QueryDiagnostics> explainAsync(Query query) {
        return Async.submit(() -> explain(query), executor);
    }
}
