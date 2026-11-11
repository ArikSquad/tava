package eu.mikart.tava;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.migration.Migrations;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import eu.mikart.tava.spi.ForwardingEntityStore;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Main entry point for working with a Tava adapter.
 *
 * <p>A {@code Tava} instance owns the adapter passed to {@link #open(Adapter)} and closes it from {@link #close()}.
 * Create separate instances when you need independent adapter lifecycles.
 */
public final class Tava implements AutoCloseable {
    private static final Executor BLOCKING_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final Adapter adapter;
    private final Executor executor;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;
    private final ThreadLocal<EntityStore> transactionStore = new ThreadLocal<>();
    private final EntityStore routedStore = new ForwardingEntityStore() {
        @Override
        protected EntityStore delegate() {
            return withLifecycle(Tava.this::currentStore);
        }

        @Override
        public EntityRecord insert(String entity, EntityRecord record) {
            return withStore(store -> store.insert(entity, record));
        }

        @Override
        public List<EntityRecord> insert(String entity, List<EntityRecord> records) {
            return withStore(store -> store.insert(entity, records));
        }

        @Override
        public Page<EntityRecord> find(String entity, Query query) {
            return withStore(store -> store.find(entity, query));
        }

        @Override
        public long update(String entity, Predicate predicate, Mutation mutation) {
            return withStore(store -> store.update(entity, predicate, mutation));
        }

        @Override
        public long delete(String entity, Predicate predicate) {
            return withStore(store -> store.delete(entity, predicate));
        }

        @Override
        public MutationResult insertResult(String entity, EntityRecord record) {
            return withStore(store -> store.insertResult(entity, record));
        }

        @Override
        public MutationResult updateResult(String entity, Predicate predicate, Mutation mutation) {
            return withStore(store -> store.updateResult(entity, predicate, mutation));
        }

        @Override
        public MutationResult deleteResult(String entity, Predicate predicate) {
            return withStore(store -> store.deleteResult(entity, predicate));
        }

        @Override
        public MutationResult upsert(String entity, Predicate identity, EntityRecord insert, Mutation update) {
            return withStore(store -> store.upsert(entity, identity, insert, update));
        }
    };
    private final SchemaManager routedSchema = new SchemaManager() {
        @Override
        public Schema inspect() {
            return withLifecycle(() -> adapter.schemas().inspect());
        }

        @Override
        public SchemaPlan plan(Schema desired) {
            return withLifecycle(() -> guardPlan(adapter.schemas().plan(desired)));
        }

        @Override
        public SchemaPlan planExact(Schema desired) {
            return withLifecycle(() -> guardPlan(adapter.schemas().planExact(desired)));
        }

        @Override
        public SchemaPlan plan(Schema desired, SchemaRenames renames) {
            return withLifecycle(() -> guardPlan(adapter.schemas().plan(desired, renames)));
        }
    };

    private Tava(final @NotNull Adapter adapter, final @NotNull Executor executor) {
        this.adapter = Objects.requireNonNull(adapter);
        Objects.requireNonNull(executor);
        this.executor = command -> {
            if (transactionStore.get() != null || adapter instanceof TransactionAdapter)
                throw new TavaException.Capability("Async operations are unavailable inside a transaction");
            executor.execute(command);
        };
    }

    /**
     * Opens a Tava facade over the given adapter, using virtual threads for asynchronous blocking I/O.
     *
     * @param adapter adapter implementation to use for schema, data, and native access
     * @return a facade that delegates all operations to {@code adapter}
     */
    public static @NotNull Tava open(final @NotNull Adapter adapter) {
        return new Tava(adapter, BLOCKING_EXECUTOR);
    }

    /**
     * Opens a Tava facade with a caller-provided executor for asynchronous operations.
     *
     * @param adapter adapter implementation to use for schema, data, and native access
     * @param executor executor for asynchronous operations
     * @return a facade that delegates all operations to {@code adapter}
     */
    public static @NotNull Tava open(final @NotNull Adapter adapter, final @NotNull Executor executor) {
        return new Tava(adapter, executor);
    }

    /**
     * Returns the adapter name used in capability metadata.
     *
     * @return adapter name
     */
    public @NotNull String adapterName() {
        return withLifecycle(adapter::name);
    }

    /**
     * Returns the adapter's declared feature support and limits.
     *
     * @return adapter capabilities
     */
    public @NotNull Capabilities capabilities() {
        return withLifecycle(adapter::capabilities);
    }

    /**
     * Returns schema inspection and planning operations.
     *
     * @return schema manager
     */
    public @NotNull SchemaManager schema() {
        if (transactionStore.get() != null)
            throw new TavaException.Capability("Schema operations are unavailable inside a transaction");
        return routedSchema;
    }

    /**
     * Builds a schema plan by comparing the desired schema with the adapter's current schema. The returned plan is
     * inert until {@link SchemaPlan#apply()} is called.
     *
     * @param desired desired schema
     * @return an inert plan
     */
    public @NotNull SchemaPlan plan(final @NotNull Schema desired) {
        return schema().plan(desired);
    }

    /**
     * Builds a plan that also reports storage omitted from the desired schema.
     *
     * @param desired complete schema for the namespace
     * @return an inert plan that reports removals from the desired schema
     * @throws TavaException.Capability if exact reconciliation is unsupported by the adapter
     * @since 1.3.0
     */
    public @NotNull SchemaPlan planExact(final @NotNull Schema desired) {
        return schema().planExact(desired);
    }

    /**
     * Plans a schema while explicitly mapping existing entity and field names to desired names.
     *
     * @param desired desired schema
     * @param renames explicit entity and field rename mappings
     * @return an inert plan
     */
    public @NotNull SchemaPlan plan(final @NotNull Schema desired, final @NotNull SchemaRenames renames) {
        return schema().plan(desired, renames);
    }

    /**
     * Returns a migration manager using the default journal entity.
     *
     * @return migration manager
     */
    public Migrations migrations() {
        return new Migrations(this);
    }

    /**
     * Returns a typed record facade for the entity inferred from the record annotations. Use {@link #records(String)}
     * when you need partial projections or dynamic field sets.
     *
     * @param <T> record type
     * @param type annotated record class
     * @return typed entity facade
     */
    public <T extends Record> @NotNull Entity<T> entity(final @NotNull Class<T> type) {
        final RecordMapper<T> mapper = RecordMapper.of(type);
        return new Entity<>(mapper.entityName(), routedStore, mapper, executor);
    }

    /**
     * Returns a typed record facade for an explicit entity name. Use {@link #records(String)} when you need partial
     * projections or dynamic field sets.
     *
     * @param <T> record type
     * @param name entity name
     * @param type record class
     * @return typed entity facade
     */
    public <T extends Record> @NotNull Entity<T> entity(final @NotNull String name, final @NotNull Class<T> type) {
        return new Entity<>(name, routedStore, RecordMapper.of(type), executor);
    }

    /**
     * Returns an untyped record facade for dynamic records, partial projections, and schema-free workflows.
     *
     * @param entity name of the entity
     * @return dynamic record facade
     */
    public @NotNull Records records(final @NotNull String entity) {
        return new Records(entity, routedStore);
    }

    /**
     * Runs the callback in one adapter transaction. Transaction-scoped facades are valid only on the callback thread
     * and until the callback returns.
     *
     * @param callback transaction work
     * @param <R> result type
     * @return the callback result after the transaction completes
     */
    @Blocking
    public <R> R transaction(final @NotNull Function<Tava, R> callback) {
        Objects.requireNonNull(callback, "callback");
        if (transactionStore.get() != null || adapter instanceof TransactionAdapter)
            throw new TavaException.Capability("Nested transactions are unsupported");
        Lock activeTransaction = lifecycle.readLock();
        activeTransaction.lock();
        try {
            if (closed) throw new IllegalStateException("Tava is closed");
            return adapter.transactions().transaction(store -> {
                var scoped = new ScopedStore(store);
                transactionStore.set(scoped);
                try {
                    return callback.apply(new Tava(new TransactionAdapter(adapter, scoped), executor));
                } finally {
                    scoped.active = false;
                    transactionStore.remove();
                }
            });
        } finally {
            activeTransaction.unlock();
        }
    }

    private static final class ScopedStore extends ForwardingEntityStore {
        private final EntityStore store;
        private final Thread owner = Thread.currentThread();
        private volatile boolean active = true;

        ScopedStore(EntityStore store) {
            this.store = store;
        }

        @Override
        protected EntityStore delegate() {
            if (!active || Thread.currentThread() != owner)
                throw new IllegalStateException("Transaction handle is no longer active on its owning thread");
            return store;
        }
    }

    private EntityStore currentStore() {
        EntityStore current = transactionStore.get();
        return current == null ? adapter.entities() : current;
    }

    private <R> R withStore(Function<EntityStore, R> operation) {
        return withLifecycle(() -> operation.apply(currentStore()));
    }

    private <R> R withLifecycle(Supplier<R> operation) {
        Lock activeOperation = lifecycle.readLock();
        activeOperation.lock();
        try {
            if (closed) throw new IllegalStateException("Tava is closed");
            return operation.get();
        } finally {
            activeOperation.unlock();
        }
    }

    private SchemaPlan guardPlan(SchemaPlan plan) {
        return new SchemaPlan(
                plan.changes(),
                () -> withLifecycle(() -> {
                    plan.application().run();
                    return null;
                }));
    }

    private record TransactionAdapter(Adapter delegate, EntityStore store) implements Adapter {
        public String name() {
            return delegate.name();
        }

        public Capabilities capabilities() {
            return delegate.capabilities();
        }

        public SchemaManager schemas() {
            throw new TavaException.Capability("Schema operations are unavailable inside a transaction");
        }

        public EntityStore entities() {
            return store;
        }

        public NativeAccess nativeAccess() {
            throw new TavaException.Capability("Native access is unavailable inside a transaction");
        }

        public void close() {}
    }

    /**
     * Returns access to the adapter's native driver API.
     *
     * @return native access facade
     */
    public @NotNull NativeAccess nativeAccess() {
        if (transactionStore.get() != null)
            throw new TavaException.Capability("Native access is unavailable inside a transaction");
        NativeAccess nativeAccess = withLifecycle(adapter::nativeAccess);
        return new NativeAccess() {
            @Override
            @SuppressWarnings("deprecation")
            public <T> T nativeHandle(Class<T> type) {
                return withLifecycle(() -> nativeAccess.nativeHandle(type));
            }

            @Override
            public <T, R> @Nullable R withNative(Class<T> type, Function<T, R> callback) {
                return withLifecycle(() -> nativeAccess.withNative(type, callback));
            }
        };
    }

    /**
     * Returns a native driver handle of the requested type.
     *
     * @param <T> native handle type
     * @param type class token for the requested handle
     * @return native handle
     * @deprecated Use {@link #nativeAccess()} and {@link NativeAccess#withNative(Class, Function)} to scope handle use
     *     to a callback.
     */
    @Deprecated(since = "1.3.0", forRemoval = false)
    @SuppressWarnings("deprecation")
    public <T> @NotNull T nativeHandle(final @NotNull Class<T> type) {
        return nativeAccess().nativeHandle(type);
    }

    @NotNull
    Adapter adapter() {
        return adapter;
    }

    /** Waits for active operations and closes the adapter. This method has no deadline. */
    @Override
    public void close() {
        checkClose();
        Lock close = lifecycle.writeLock();
        close.lock();
        try {
            closeAdapter();
        } finally {
            close.unlock();
        }
    }

    /**
     * Waits up to the timeout for active operations before closing the adapter. A timeout leaves the instance open. The
     * timeout bounds waiting for operations; configure driver timeouts separately for adapter cleanup itself.
     *
     * @param timeout non-negative maximum wait for active operations
     * @return true if closed, false if active operations did not finish before the timeout
     * @throws InterruptedException if interrupted while waiting
     * @since 1.3.0
     */
    public boolean close(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        checkClose();
        Lock close = lifecycle.writeLock();
        if (!close.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) return false;
        try {
            closeAdapter();
            return true;
        } finally {
            close.unlock();
        }
    }

    private void checkClose() {
        if (transactionStore.get() != null || lifecycle.getReadHoldCount() > 0)
            throw new TavaException.Capability("Cannot close Tava inside an active operation");
    }

    private void closeAdapter() {
        if (closed) return;
        closed = true;
        adapter.close();
    }
}
