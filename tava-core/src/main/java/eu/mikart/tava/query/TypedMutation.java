package eu.mikart.tava.query;

import java.util.Collection;
import java.util.Objects;

/**
 * A mutation whose field references belong to one record type.
 *
 * @param <R> owning record type
 */
public final class TypedMutation<R extends Record> {
    private final Mutation mutation;

    private TypedMutation(Mutation mutation) {
        this.mutation = mutation;
    }

    /**
     * Drops record ownership at a dynamic API boundary.
     *
     * @return portable mutation
     */
    public Mutation untyped() {
        return mutation;
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
     * Collects expressions for a single record type.
     *
     * @param <R> owning record type
     */
    public static final class Builder<R extends Record> {
        private final Mutation.Builder builder = Mutation.builder();

        private Builder() {}

        /**
         * Assigns a replacement value.
         *
         * @param <V> field value type
         * @param field field to assign
         * @param value replacement value
         * @return this builder
         */
        public <V> Builder<R> set(FieldRef<R, V> field, V value) {
            builder.set(field.untyped(), value);
            return this;
        }

        /**
         * Adds an atomic increment operation.
         *
         * @param field numeric field
         * @param amount amount to add
         * @return this builder
         */
        public Builder<R> increment(FieldRef<R, ? extends Number> field, Number amount) {
            builder.increment(field.untyped(), amount);
            return this;
        }

        /**
         * Adds an element only if absent.
         *
         * @param <V> collection element type
         * @param field collection field
         * @param value element to add
         * @return this builder
         */
        public <V> Builder<R> appendIfAbsent(FieldRef<R, ? extends Collection<V>> field, V value) {
            builder.appendIfAbsent(field.untyped(), value);
            return this;
        }

        /**
         * Removes an element from a collection.
         *
         * @param <V> collection element type
         * @param field collection field
         * @param value element to remove
         * @return this builder
         */
        public <V> Builder<R> remove(FieldRef<R, ? extends Collection<V>> field, V value) {
            builder.remove(field.untyped(), value);
            return this;
        }

        /**
         * Builds an immutable request.
         *
         * @return typed mutation
         */
        public TypedMutation<R> build() {
            return new TypedMutation<>(builder.build());
        }
    }
}
