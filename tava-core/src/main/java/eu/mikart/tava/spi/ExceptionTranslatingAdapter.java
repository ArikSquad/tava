package eu.mikart.tava.spi;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.SchemaPlan;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Adapts driver failures at the portable API boundary while retaining native access.
 *
 * @since 1.3.0
 */
public final class ExceptionTranslatingAdapter implements Adapter {
    private final Adapter adapter;
    private final UnaryOperator<RuntimeException> translator;

    /**
     * Creates an adapter facade that translates runtime failures from portable operations.
     *
     * @param adapter underlying adapter
     * @param translator maps a driver failure to its portable exception
     * @throws NullPointerException if the adapter or translator is {@code null}
     * @since 1.3.0
     */
    public ExceptionTranslatingAdapter(Adapter adapter, UnaryOperator<RuntimeException> translator) {
        this.adapter = Objects.requireNonNull(adapter);
        this.translator = Objects.requireNonNull(translator);
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            throw translator.apply(failure);
        }
    }

    private EntityStore store(EntityStore store) {
        return new EntityStore() {
            public EntityRecord insert(String entity, EntityRecord record) {
                return call(() -> store.insert(entity, record));
            }

            public List<EntityRecord> insert(String entity, List<EntityRecord> records) {
                return call(() -> store.insert(entity, records));
            }

            public Page<EntityRecord> find(String entity, Query query) {
                return call(() -> store.find(entity, query));
            }

            public long update(String entity, Predicate predicate, Mutation mutation) {
                return call(() -> store.update(entity, predicate, mutation));
            }

            public long delete(String entity, Predicate predicate) {
                return call(() -> store.delete(entity, predicate));
            }

            public MutationResult insertResult(String entity, EntityRecord record) {
                return call(() -> store.insertResult(entity, record));
            }

            public MutationResult updateResult(String entity, Predicate predicate, Mutation mutation) {
                return call(() -> store.updateResult(entity, predicate, mutation));
            }

            public MutationResult deleteResult(String entity, Predicate predicate) {
                return call(() -> store.deleteResult(entity, predicate));
            }

            public MutationResult upsert(String entity, Predicate identity, EntityRecord insert, Mutation update) {
                return call(() -> store.upsert(entity, identity, insert, update));
            }
        };
    }

    private SchemaPlan plan(SchemaPlan plan) {
        return new SchemaPlan(
                plan.changes(),
                () -> call(() -> {
                    plan.application().run();
                    return null;
                }));
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public String name() {
        return adapter.name();
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public Capabilities capabilities() {
        return adapter.capabilities();
    }

    /**
     * Returns a store that translates runtime failures from portable operations.
     *
     * @return translating entity store
     * @since 1.3.0
     */
    @Override
    public EntityStore entities() {
        return store(adapter.entities());
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public NativeAccess nativeAccess() {
        return adapter.nativeAccess();
    }

    /**
     * Returns a schema manager that translates runtime failures from portable operations.
     *
     * @return translating schema manager
     * @since 1.3.0
     */
    @Override
    public SchemaManager schemas() {
        SchemaManager manager = adapter.schemas();
        return new SchemaManager() {
            public Schema inspect() {
                return call(manager::inspect);
            }

            public SchemaPlan plan(Schema desired) {
                return call(() -> ExceptionTranslatingAdapter.this.plan(manager.plan(desired)));
            }

            public SchemaPlan plan(Schema desired, SchemaRenames renames) {
                return call(() -> ExceptionTranslatingAdapter.this.plan(manager.plan(desired, renames)));
            }

            public SchemaPlan planExact(Schema desired) {
                return call(() -> ExceptionTranslatingAdapter.this.plan(manager.planExact(desired)));
            }
        };
    }

    /**
     * Returns a transaction manager that translates runtime failures from portable operations.
     *
     * @return translating transaction manager
     * @since 1.3.0
     */
    @Override
    public TransactionManager transactions() {
        return new TransactionManager() {
            public <R> R transaction(Function<EntityStore, R> callback) {
                return call(
                        () -> adapter.transactions().transaction(transaction -> callback.apply(store(transaction))));
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public void close() {
        call(() -> {
            adapter.close();
            return null;
        });
    }
}
