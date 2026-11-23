package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Aggregation;
import eu.mikart.tava.query.FieldRef;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Pages;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.QueryDiagnostics;
import eu.mikart.tava.query.TypedMutation;
import eu.mikart.tava.query.TypedQuery;
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

    /**
     * Increments a counter atomically and returns the value from that write.
     *
     * @param <N> numeric field type
     * @param key identity protected by a unique constraint
     * @param field counter field
     * @param amount amount to add
     * @param create whether to insert a missing row with just the identity and counter
     * @return new value, or empty if the row is missing and creation is disabled
     */
    @Blocking
    public <N extends Number> Optional<N> incrementReturning(
            Identity key, FieldRef<T, N> field, N amount, boolean create) {
        var decode = mapper.keyReader(field.name(), field.type());
        return store.incrementReturning(name, mapper.write(key), field.name(), amount, create)
                .map(decode);
    }

    /**
     * Computes grouped results using this entity's predicate value mappings.
     *
     * @param aggregation grouping and metric request
     * @return group keys and computed metrics
     */
    @Blocking
    public List<EntityRecord> aggregate(Aggregation aggregation) {
        return store.aggregate(name, aggregation.withPredicate(mapper.write(aggregation.predicate())));
    }

    /**
     * Creates a mutation builder bound to this record type.
     *
     * @return typed mutation builder
     */
    public TypedMutation.Builder<T> mutation() {
        return TypedMutation.builder(mapper.type());
    }

    /**
     * Updates records matching a typed filter.
     *
     * @param filter condition selecting records
     * @param mutation changes to apply
     * @return number of updated records
     */
    @Blocking
    public long update(TypedQuery.Filter<T> filter, TypedMutation<T> mutation) {
        return update(filter.predicate(), mutation.untyped());
    }

    /**
     * Counts records matching a typed filter.
     *
     * @param filter condition selecting records
     * @return number of matching records
     */
    @Blocking
    public long count(TypedQuery.Filter<T> filter) {
        return count(filter.predicate());
    }

    /**
     * Checks whether any record matches a typed filter.
     *
     * @param filter condition selecting records
     * @return whether a match exists
     */
    @Blocking
    public boolean exists(TypedQuery.Filter<T> filter) {
        return exists(filter.predicate());
    }

    /**
     * Deletes records matching a typed filter.
     *
     * @param filter condition selecting records
     * @return number of deleted records
     */
    @Blocking
    public long delete(TypedQuery.Filter<T> filter) {
        return delete(filter.predicate());
    }

    /**
     * Applies a typed mutation to the record with the given identity.
     *
     * @param id record identity
     * @param mutation changes to apply
     * @return update outcome
     */
    @Blocking
    public MutationResult updateById(Object id, TypedMutation<T> mutation) {
        return updateById(id, mutation.untyped());
    }

    /**
     * Applies a typed mutation to the record with the given identity.
     *
     * @param id record identity
     * @param mutation changes to apply
     * @return update outcome
     */
    @Blocking
    public MutationResult updateById(Identity id, TypedMutation<T> mutation) {
        return updateById(id, mutation.untyped());
    }

    /**
     * Creates a query builder bound to this record type.
     *
     * @return typed query builder
     */
    public TypedQuery.Builder<T> query() {
        return TypedQuery.builder(mapper.type());
    }

    /**
     * Reads one page for a typed query.
     *
     * @param query page request
     * @return page of matching records
     */
    @Blocking
    public Page<T> find(TypedQuery<T> query) {
        return find(query.untyped());
    }

    /**
     * Reads one projected page for a typed query.
     *
     * @param <P> projection record type
     * @param query page request
     * @param projectionType class of the projected record
     * @return page of projected records
     */
    @Blocking
    public <P extends Record> Page<P> find(TypedQuery<T> query, Class<P> projectionType) {
        return find(query.untyped(), projectionType);
    }

    /**
     * Reads one record matching a typed filter.
     *
     * @param filter condition selecting records
     * @return matching record, if present
     */
    @Blocking
    public Optional<T> findFirst(TypedQuery.Filter<T> filter) {
        return findOne(filter.predicate());
    }

    /**
     * Lazily follows a typed query across pages.
     *
     * @param query initial page request
     * @return sequential stream of records
     */
    public Stream<T> stream(TypedQuery<T> query) {
        return stream(query.untyped());
    }

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
     * Inserts a record and returns the value of a single database-generated key column. The JDBC driver may support
     * only the table's auto-increment or identity column.
     *
     * @param <K> key type
     * @param record record to insert, with the generated field omitted by its mapping
     * @param keyColumn mapped name of the generated column
     * @param keyType boxed type of the returned key
     * @return generated key
     */
    @Blocking
    public <K> K insertReturningKey(T record, String keyColumn, Class<K> keyType) {
        var readKey = mapper.keyReader(keyColumn, keyType);
        return readKey.apply(store.insertReturningKey(name, mapper.write(record), keyColumn));
    }

    /**
     * Upserts by a database-enforced unique key and returns one non-null column from the affected row.
     *
     * @param <K> returned key type
     * @param key unique values that must also be present in the insert
     * @param insert record to insert
     * @param update changes for a match, leaving the match columns intact
     * @param returnColumn mapped name of the column to return
     * @param keyType boxed type of the returned key
     * @return column value from the inserted or matched row
     */
    @Blocking
    public <K> K upsertReturningKey(Identity key, T insert, Mutation update, String returnColumn, Class<K> keyType) {
        var readKey = mapper.keyReader(returnColumn, keyType);
        return readKey.apply(store.upsertReturningKey(
                name, mapper.write(key), mapper.write(insert), mapper.write(update), returnColumn));
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
        final var page = store.find(name, mapper.write(query), mapper.type());
        return new Page<>(page.items().stream().map(mapper::read).toList(), page.nextCursor());
    }

    /**
     * Finds at most one record matching a predicate.
     *
     * @param predicate condition to match
     * @return a matching record, or empty if none matches
     */
    @Blocking
    public @NotNull Optional<T> findOne(final @NotNull Predicate predicate) {
        return store.findOne(name, mapper.write(predicate == null ? Predicate.all() : predicate))
                .map(mapper::read);
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
     * Creates a lazy, single-use traversal of pages bound to this entity and query.
     *
     * @param query initial query
     * @return page iterator, including empty pages
     */
    public Pages.Cursor<T> pages(Query query) {
        return Pages.cursor(query, this::find);
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
        var page = store.find(name, mapper.write(selected), mapper.type());
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
    /**
     * Finds exactly one page. Follow the returned cursor, or use {@link #stream(Query)} for all results.
     *
     * @param query page request
     * @return one page of matching records
     * @since 1.3.1
     */
    @Blocking
    public Page<T> findPage(Query query) {
        return find(query);
    }

    /**
     * Finds exactly one projected page.
     *
     * @param <P> projection record type
     * @param query page request
     * @param projectionType projection class
     * @return one projected page
     * @since 1.3.1
     */
    @Blocking
    public <P extends Record> Page<P> findPage(Query query, Class<P> projectionType) {
        return find(query, projectionType);
    }

    /**
     * Traverses all projected results lazily, following continuation cursors.
     *
     * @param <P> projection record type
     * @param query initial page request
     * @param projectionType projection class
     * @return sequential projected stream
     * @since 1.3.1
     */
    public <P extends Record> Stream<P> stream(Query query, Class<P> projectionType) {
        return Pages.stream(query, page -> findPage(page, projectionType));
    }

    /**
     * Traverses projected pages lazily, including empty pages.
     *
     * @param <P> projection record type
     * @param query initial page request
     * @param projectionType projection class
     * @return single-use page cursor
     * @since 1.3.1
     */
    public <P extends Record> Pages.Cursor<P> pages(Query query, Class<P> projectionType) {
        return Pages.cursor(query, page -> findPage(page, projectionType));
    }

    /**
     * Finds one page on the configured executor.
     *
     * @param query page request
     * @return asynchronous page
     * @since 1.3.1
     */
    public CompletableFuture<Page<T>> findPageAsync(Query query) {
        return Async.submit(() -> findPage(query), executor);
    }

    /**
     * Finds one projected page on the configured executor.
     *
     * @param <P> projection record type
     * @param query page request
     * @param projectionType projection class
     * @return asynchronous projected page
     * @since 1.3.1
     */
    public <P extends Record> CompletableFuture<Page<P>> findPageAsync(Query query, Class<P> projectionType) {
        return Async.submit(() -> findPage(query, projectionType), executor);
    }

    /**
     * Attempts inserts in input order. Conflicts are reported per record; other failures stop the batch. Earlier writes
     * may remain committed if a later operation fails.
     *
     * @param records records to insert
     * @return outcomes in input order
     * @since 1.3.1
     */
    @Blocking
    public List<MutationResult> insertIfAbsent(List<T> records) {
        return records.stream().map(this::insertIfAbsent).toList();
    }

    /**
     * Attempts a batch of inserts on the configured executor.
     *
     * @param records records to insert
     * @return outcomes in input order
     * @since 1.3.1
     */
    public CompletableFuture<List<MutationResult>> insertIfAbsentAsync(List<T> records) {
        return Async.submit(() -> insertIfAbsent(records), executor);
    }

    /**
     * Upserts records in input order. Each upsert is atomic; the batch is not atomic unless enclosed in a transaction.
     * Earlier writes may remain committed if a later operation fails.
     *
     * @param records records to insert or update
     * @return outcomes in input order
     * @since 1.3.1
     */
    @Blocking
    public List<MutationResult> upsert(List<T> records) {
        return records.stream().map(this::upsert).toList();
    }

    /**
     * Upserts a batch on the configured executor.
     *
     * @param records records to insert or update
     * @return outcomes in input order
     * @since 1.3.1
     */
    public CompletableFuture<List<MutationResult>> upsertAsync(List<T> records) {
        return Async.submit(() -> upsert(records), executor);
    }
}
