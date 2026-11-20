package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/** Provides dynamic reads and writes for one named entity. */
public final class Records {
    private final String entity;
    private final EntityStore store;
    private final Executor executor;

    Records(final @NotNull String entity, final @NotNull EntityStore store) {
        this(entity, store, Runnable::run);
    }

    Records(String entity, EntityStore store, Executor executor) {
        this.executor = executor;
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
     * Inserts a record and returns the value of a single database-generated key column. The JDBC driver may support
     * only the table's auto-increment or identity column.
     *
     * @param record values to insert, with the generated column omitted
     * @param keyColumn name of the generated key column
     * @return generated key as read from the database
     */
    @Blocking
    public Object insertReturningKey(EntityRecord record, String keyColumn) {
        return store.insertReturningKey(entity, record, keyColumn);
    }

    /**
     * Upserts by a database-enforced unique key and returns one non-null column from the affected row.
     *
     * @param key unique values that must also be present in the insert
     * @param insert values to insert
     * @param update changes for a match, leaving the match columns intact
     * @param returnColumn name of the column to return
     * @return column value as read from the database
     */
    @Blocking
    public Object upsertReturningKey(Identity key, EntityRecord insert, Mutation update, String returnColumn) {
        return store.upsertReturningKey(entity, key, insert, update, returnColumn);
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
        return store.findOne(entity, predicate);
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
        return exists(id.predicate());
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
    /**
     * Counts matching records without materializing entity objects.
     *
     * @param predicate matching condition
     * @return matching count
     * @since 1.3.0
     */
    @Blocking
    public long count(Predicate predicate) {
        return store.count(entity, predicate);
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
        return store.exists(entity, predicate);
    }

    /**
     * Lazily follows query cursors, fetching only when the stream is consumed.
     *
     * @param query initial page request
     * @return sequential stream
     * @since 1.3.0
     */
    public Stream<EntityRecord> stream(Query query) {
        return Pages.stream(query, this::find);
    }

    /**
     * Creates a lazy, single-use traversal of pages bound to this entity and query.
     *
     * @param query initial query
     * @return page iterator, including empty pages
     */
    public Pages.Cursor<EntityRecord> pages(Query query) {
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
        return store.explain(entity, query);
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
        var page = store.find(entity, selected);
        var projection = RecordMapper.of(projectionType);
        return new Page<>(page.items().stream().map(projection::read).toList(), page.nextCursor());
    }

    /**
     * Runs insert on the configured executor. Cancellation interrupts the worker.
     *
     * @param record operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<EntityRecord> insertAsync(EntityRecord record) {
        return Async.submit(() -> insert(record), executor);
    }

    /**
     * Runs insert on the configured executor. Cancellation interrupts the worker.
     *
     * @param records operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<List<EntityRecord>> insertAsync(List<EntityRecord> records) {
        return Async.submit(() -> insert(records), executor);
    }

    /**
     * Runs find on the configured executor. Cancellation interrupts the worker.
     *
     * @param query operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Page<EntityRecord>> findAsync(Query query) {
        return Async.submit(() -> find(query), executor);
    }

    /**
     * Runs findOne on the configured executor. Cancellation interrupts the worker.
     *
     * @param predicate operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<Optional<EntityRecord>> findOneAsync(Predicate predicate) {
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
    public CompletableFuture<Optional<EntityRecord>> findByIdAsync(Identity id) {
        return Async.submit(() -> findById(id), executor);
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
    public CompletableFuture<MutationResult> deleteByIdAsync(Identity id) {
        return Async.submit(() -> deleteById(id), executor);
    }

    /**
     * Runs upsert on the configured executor. Cancellation interrupts the worker.
     *
     * @param id operation argument
     * @param insert operation argument
     * @param update operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> upsertAsync(Identity id, EntityRecord insert, Mutation update) {
        return Async.submit(() -> upsert(id, insert, update), executor);
    }

    /**
     * Runs insertResult on the configured executor. Cancellation interrupts the worker.
     *
     * @param record operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> insertResultAsync(EntityRecord record) {
        return Async.submit(() -> insertResult(record), executor);
    }

    /**
     * Runs insertIfAbsent on the configured executor. Cancellation interrupts the worker.
     *
     * @param record operation argument
     * @return asynchronous result
     * @since 1.3.0
     */
    public CompletableFuture<MutationResult> insertIfAbsentAsync(EntityRecord record) {
        return Async.submit(() -> insertIfAbsent(record), executor);
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
    public Page<EntityRecord> findPage(Query query) {
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
    public CompletableFuture<Page<EntityRecord>> findPageAsync(Query query) {
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
    public List<MutationResult> insertIfAbsent(List<EntityRecord> records) {
        return records.stream().map(this::insertIfAbsent).toList();
    }

    /**
     * Attempts a batch of inserts on the configured executor.
     *
     * @param records records to insert
     * @return outcomes in input order
     * @since 1.3.1
     */
    public CompletableFuture<List<MutationResult>> insertIfAbsentAsync(List<EntityRecord> records) {
        return Async.submit(() -> insertIfAbsent(records), executor);
    }
    /**
     * Upserts corresponding identities, insert values, and mutations in input order. Each upsert is atomic; earlier
     * writes may remain committed if a later operation fails. Enclose the call in a transaction when batch atomicity is
     * required.
     *
     * @param identities identities to match
     * @param inserts values for missing records
     * @param updates mutations for existing records
     * @return outcomes in input order
     * @throws IllegalArgumentException if the lists differ in size
     * @since 1.3.1
     */
    @Blocking
    public List<MutationResult> upsert(List<Identity> identities, List<EntityRecord> inserts, List<Mutation> updates) {
        if (identities.size() != inserts.size() || identities.size() != updates.size()) {
            throw new IllegalArgumentException("Bulk upsert lists must have the same size");
        }
        var results = new ArrayList<MutationResult>(identities.size());
        for (int index = 0; index < identities.size(); index++) {
            results.add(upsert(identities.get(index), inserts.get(index), updates.get(index)));
        }
        return List.copyOf(results);
    }

    /**
     * Upserts corresponding records on the configured executor.
     *
     * @param identities identities to match
     * @param inserts values for missing records
     * @param updates mutations for existing records
     * @return outcomes in input order
     * @since 1.3.1
     */
    public CompletableFuture<List<MutationResult>> upsertAsync(
            List<Identity> identities, List<EntityRecord> inserts, List<Mutation> updates) {
        return Async.submit(() -> upsert(identities, inserts, updates), executor);
    }
}
