package eu.mikart.tava.query;

import java.util.Arrays;
import java.util.Objects;

/**
 * A query whose field expressions belong to one record type.
 *
 * @param <R> owning record type
 */
public final class TypedQuery<R extends Record> {
    private final Query query;

    private TypedQuery(Query query) {
        this.query = query;
    }

    /**
     * Drops record ownership at a dynamic API boundary.
     *
     * @return portable query
     */
    public Query untyped() {
        return query;
    }

    /**
     * Continues the same query from a cursor.
     *
     * @param cursor next cursor or null to restart
     * @return continued query
     */
    public TypedQuery<R> withCursor(String cursor) {
        return new TypedQuery<>(query.withCursor(cursor));
    }

    /**
     * Creates a builder for the supplied record type.
     *
     * @param <R> owning record type
     * @param owner record class
     * @return typed builder
     */
    public static <R extends Record> Builder<R> builder(Class<R> owner) {
        Objects.requireNonNull(owner, "owner");
        return new Builder<>();
    }

    /**
     * A predicate bound to its owning record type.
     *
     * @param <R> owning record type
     * @param predicate portable filter
     */
    public record Filter<R extends Record>(Predicate predicate) {
        /**
         * Combines this filter with another from the same record type.
         *
         * @param other other condition
         * @return combined filter
         */
        public Filter<R> and(Filter<R> other) {
            return new Filter<>(Predicate.and(predicate, other.predicate));
        }

        /**
         * Combines this filter with another from the same record type.
         *
         * @param other other condition
         * @return combined filter
         */
        public Filter<R> or(Filter<R> other) {
            return new Filter<>(Predicate.or(predicate, other.predicate));
        }
    }

    /**
     * A sort expression bound to its owning record type.
     *
     * @param <R> owning record type
     * @param sort portable sort expression
     */
    public record Order<R extends Record>(Sort sort) {}

    /**
     * Collects expressions for a single record type.
     *
     * @param <R> owning record type
     */
    public static final class Builder<R extends Record> {
        private final Query.Builder builder = Query.builder();

        private Builder() {}

        /**
         * Sets the query filter.
         *
         * @param filter condition selecting records
         * @return this builder
         */
        public Builder<R> where(Filter<R> filter) {
            builder.where(filter.predicate());
            return this;
        }

        /**
         * Selects the fields to return.
         *
         * @param fields projected fields
         * @return this builder
         */
        @SafeVarargs
        public final Builder<R> project(FieldRef<R, ?>... fields) {
            builder.project(Arrays.stream(fields).map(FieldRef::name).toArray(String[]::new));
            return this;
        }

        /**
         * Appends sort expressions.
         *
         * @param order sort expressions in priority order
         * @return this builder
         */
        @SafeVarargs
        public final Builder<R> sort(Order<R>... order) {
            builder.sort(Arrays.stream(order).map(Order::sort).toArray(Sort[]::new));
            return this;
        }

        /**
         * Sets the maximum number of records per page.
         *
         * @param size page size, or zero for the adapter default
         * @return this builder
         */
        public Builder<R> limit(int size) {
            builder.limit(size);
            return this;
        }

        /**
         * Builds an immutable request.
         *
         * @return typed query
         */
        public TypedQuery<R> build() {
            return new TypedQuery<>(builder.build());
        }
    }
}
