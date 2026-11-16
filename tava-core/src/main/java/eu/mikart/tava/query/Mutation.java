package eu.mikart.tava.query;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes value assignments and atomic field operations for a record mutation.
 *
 * @param values replacement values keyed by field name; a value may be {@code null}
 * @param operations atomic operations keyed by field name
 */
public record Mutation(
        @NotNull Map<String, Object> values, @NotNull Map<String, Operation> operations) {
    /** Atomic operations that can be applied to a field. */
    public enum Kind {
        /** Adds a numeric amount to a field. */
        INCREMENT,
        /** Adds a collection value only if the collection does not already contain it. */
        APPEND_IF_ABSENT,
        /**
         * Adds a collection value.
         *
         * @deprecated Use {@link #APPEND_IF_ABSENT}; its name makes the conditional behavior explicit.
         */
        @Deprecated(since = "1.3.0", forRemoval = false)
        APPEND,
        /** Removes a value from a collection. */
        REMOVE
    }

    /**
     * An atomic operation to apply to one field.
     *
     * @param kind operation to perform
     * @param value operand for the operation
     */
    public record Operation(@NotNull Kind kind, @Nullable Object value) {}

    /**
     * Creates an immutable mutation from assignments and atomic operations.
     *
     * @param values replacement values keyed by field name
     * @param operations atomic operations keyed by field name
     * @throws NullPointerException if either map is {@code null}
     */
    public Mutation {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
    }

    /**
     * Creates a mutation containing value assignments and no atomic operations.
     *
     * @param values replacement values keyed by field name
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public Mutation(@NotNull Map<String, Object> values) {
        this(values, Map.of());
    }

    /**
     * Creates a builder for a mutation.
     *
     * @return a new empty mutation builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Builds a mutation from field assignments and atomic operations. */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Creates a builder with no assignments or operations. */
        public Builder() {}

        /**
         * Sets a value for a field by name.
         *
         * @param field field name
         * @param value replacement value, which may be {@code null}
         * @return this builder
         */
        public @NotNull Builder set(final @NotNull String field, final @Nullable Object value) {
            values.put(field, value);
            return this;
        }

        /**
         * Sets a value for a typed field reference.
         *
         * @param field typed field reference
         * @param value replacement value, which may be {@code null}
         * @param <T> field value type
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public <T> @NotNull Builder set(final @NotNull FieldRef<T> field, final @Nullable T value) {
            return set(field.name(), value);
        }

        /**
         * Adds an increment operation for a numeric field.
         *
         * @param field numeric field reference
         * @param amount amount to add
         * @return this builder
         * @throws NullPointerException if {@code field} or {@code amount} is {@code null}
         */
        public @NotNull Builder increment(
                final @NotNull FieldRef<? extends Number> field, final @NotNull Number amount) {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(amount, "amount");
            operations.put(field.name(), new Operation(Kind.INCREMENT, amount));
            return this;
        }
        /**
         * Atomically adds a value only when the collection does not already contain it.
         *
         * @param field collection field reference
         * @param value value to add, which may be {@code null}
         * @param <T> collection element type
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public <T> @NotNull Builder appendIfAbsent(
                final @NotNull FieldRef<? extends Collection<T>> field, final @Nullable T value) {
            operations.put(field.name(), new Operation(Kind.APPEND_IF_ABSENT, value));
            return this;
        }
        /**
         * Alias for {@link #appendIfAbsent(FieldRef, Object)}.
         *
         * @param field collection field reference
         * @param value value to add, which may be {@code null}
         * @param <T> collection element type
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         * @deprecated Use {@link #appendIfAbsent(FieldRef, Object)} to make the conditional behavior explicit.
         */
        @Deprecated(since = "1.3.0", forRemoval = false)
        public <T> @NotNull Builder append(
                final @NotNull FieldRef<? extends Collection<T>> field, final @Nullable T value) {
            return appendIfAbsent(field, value);
        }

        /**
         * Adds a removal operation for a collection field.
         *
         * @param field collection field reference
         * @param value value to remove, which may be {@code null}
         * @param <T> collection element type
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public <T> @NotNull Builder remove(
                final @NotNull FieldRef<? extends Collection<T>> field, final @Nullable T value) {
            operations.put(field.name(), new Operation(Kind.REMOVE, value));
            return this;
        }

        private final Map<String, Operation> operations = new LinkedHashMap<>();

        /**
         * Builds an immutable snapshot of the current assignments and operations.
         *
         * @return the mutation described by this builder
         */
        public @NotNull Mutation build() {
            return new Mutation(values, operations);
        }
    }
}
