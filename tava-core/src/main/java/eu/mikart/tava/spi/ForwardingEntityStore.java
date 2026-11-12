package eu.mikart.tava.spi;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.QueryDiagnostics;
import java.util.List;

/**
 * Forwards every operation, including adapter-specific result and batch behavior.
 *
 * @since 1.3.0
 */
public abstract class ForwardingEntityStore implements EntityStore {
    /**
     * Creates a forwarding store.
     *
     * @since 1.3.0
     */
    protected ForwardingEntityStore() {}

    /**
     * Returns the delegate for the operation currently being forwarded.
     *
     * @return the current operation delegate
     * @since 1.3.0
     */
    protected abstract EntityStore delegate();

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public long count(String entity, Predicate predicate) {
        return delegate().count(entity, predicate);
    }
    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public boolean exists(String entity, Predicate predicate) {
        return delegate().exists(entity, predicate);
    }
    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public QueryDiagnostics explain(String entity, Query query) {
        return delegate().explain(entity, query);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public EntityRecord insert(String entity, EntityRecord record) {
        return delegate().insert(entity, record);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public List<EntityRecord> insert(String entity, List<EntityRecord> records) {
        return delegate().insert(entity, records);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public Page<EntityRecord> find(String entity, Query query) {
        return delegate().find(entity, query);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public long update(String entity, Predicate predicate, Mutation mutation) {
        return delegate().update(entity, predicate, mutation);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public long delete(String entity, Predicate predicate) {
        return delegate().delete(entity, predicate);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public MutationResult insertResult(String entity, EntityRecord record) {
        return delegate().insertResult(entity, record);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public MutationResult updateResult(String entity, Predicate predicate, Mutation mutation) {
        return delegate().updateResult(entity, predicate, mutation);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public MutationResult deleteResult(String entity, Predicate predicate) {
        return delegate().deleteResult(entity, predicate);
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public MutationResult upsert(String entity, Predicate identity, EntityRecord insert, Mutation update) {
        return delegate().upsert(entity, identity, insert, update);
    }
}
