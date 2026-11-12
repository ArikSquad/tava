package eu.mikart.tava.query;

import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A portable expression used to filter records. */
public sealed interface Predicate permits Predicate.All, Predicate.Comparison, Predicate.Junction {
    /** Predicate that matches every record. */
    record All() implements Predicate {}

    /**
     * Comparison between a field and a value.
     *
     * @param field field name
     * @param operator comparison operation
     * @param value comparison value, which may be {@code null}
     */
    record Comparison(
            @NotNull String field,
            @NotNull Operator operator,
            @Nullable Object value) implements Predicate {
        /**
         * Creates a field comparison.
         *
         * @param field field name
         * @param operator comparison operation
         * @param value comparison value, which may be {@code null}
         * @throws IllegalArgumentException if the field is blank or the operator is {@code null}
         */
        public Comparison {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
            if (operator == null) throw new IllegalArgumentException("operator is required");
        }
    }

    /**
     * Combines child predicates with a conjunction or disjunction.
     *
     * @param and {@code true} to require every child, or {@code false} to require any child
     * @param predicates child predicates; at least one is required
     */
    record Junction(boolean and, @NotNull List<Predicate> predicates) implements Predicate {
        /**
         * Creates a conjunction or disjunction.
         *
         * @param and {@code true} to require every child, or {@code false} to require any child
         * @param predicates child predicates; at least one is required
         * @throws IllegalArgumentException if the predicate list is {@code null} or empty
         * @throws NullPointerException if a predicate is {@code null}
         */
        public Junction {
            if (predicates == null || predicates.isEmpty()) {
                throw new IllegalArgumentException("junction requires at least one predicate");
            }
            predicates = List.copyOf(predicates);
        }
    }

    /** Comparison operations supported by portable predicates. */
    enum Operator {
        /** Field equals the comparison value. */
        EQ,
        /** Field does not equal the comparison value. */
        NE,
        /** Field is less than the comparison value. */
        LT,
        /** Field is less than or equal to the comparison value. */
        LTE,
        /** Field is greater than the comparison value. */
        GT,
        /** Field is greater than or equal to the comparison value. */
        GTE,
        /** Field matches one of the supplied values. */
        IN,
        /** Field contains a value or substring according to the adapter's semantics. */
        CONTAINS,
        /** String field begins with the supplied value. */
        STARTS_WITH,
        /** Field is {@code null} or absent. */
        IS_NULL,
        /** Field is non-null and present. */
        IS_NOT_NULL
    }

    /**
     * Creates a predicate that matches every record.
     *
     * @return match-all predicate
     */
    static @NotNull Predicate all() {
        return new All();
    }

    /**
     * Creates a predicate that tests whether a field equals a value.
     *
     * @param field field name
     * @param value comparison value, which may be {@code null}
     * @return equality predicate
     */
    static @NotNull Predicate eq(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.EQ, value);
    }

    /**
     * Creates a typed equality predicate.
     *
     * @param <T> value type associated with the field
     * @param field typed field reference
     * @param value comparison value, which may be {@code null}
     * @return equality predicate
     */
    static <T> @NotNull Predicate eq(final @NotNull FieldRef<T> field, final @Nullable T value) {
        return eq(field.name(), value);
    }

    /**
     * Creates a predicate that tests whether a field differs from a value.
     *
     * @param field field name
     * @param value comparison value, which may be {@code null}
     * @return inequality predicate
     */
    static @NotNull Predicate ne(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.NE, value);
    }

    /**
     * Creates a predicate that tests whether a field is null or absent.
     *
     * @param field field name
     * @return null-check predicate
     */
    static @NotNull Predicate isNull(final @NotNull String field) {
        return new Comparison(field, Operator.IS_NULL, null);
    }

    /**
     * Creates a predicate that tests whether a field is present and non-null.
     *
     * @param field field name
     * @return non-null-check predicate
     */
    static @NotNull Predicate isNotNull(final @NotNull String field) {
        return new Comparison(field, Operator.IS_NOT_NULL, null);
    }

    /**
     * Creates a predicate that tests whether a field is less than a value.
     *
     * @param field field name
     * @param value comparison value
     * @return less-than predicate
     */
    static @NotNull Predicate lt(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.LT, value);
    }

    /**
     * Creates a predicate that tests whether a field is less than or equal to a value.
     *
     * @param field field name
     * @param value comparison value
     * @return less-than-or-equal predicate
     */
    static @NotNull Predicate lte(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.LTE, value);
    }

    /**
     * Creates a predicate that tests whether a field is greater than a value.
     *
     * @param field field name
     * @param value comparison value
     * @return greater-than predicate
     */
    static @NotNull Predicate gt(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.GT, value);
    }

    /**
     * Creates a predicate that tests whether a field is greater than or equal to a value.
     *
     * @param field field name
     * @param value comparison value
     * @return greater-than-or-equal predicate
     */
    static @NotNull Predicate gte(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.GTE, value);
    }

    /**
     * Creates a predicate that tests whether a field matches any supplied value.
     *
     * @param field field name
     * @param values values to match
     * @return membership predicate
     * @throws IllegalArgumentException if {@code values} is {@code null} or empty
     * @throws NullPointerException if a value is {@code null}
     */
    static @NotNull Predicate in(final @NotNull String field, final @NotNull Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN requires at least one value");
        }
        return new Comparison(field, Operator.IN, List.copyOf(values));
    }

    /**
     * Creates a predicate that tests whether a field contains a value or substring according to the adapter's
     * semantics.
     *
     * @param field field name
     * @param value contained value, which may be {@code null}
     * @return contains predicate
     */
    static @NotNull Predicate contains(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.CONTAINS, value);
    }

    /**
     * Creates a predicate that tests whether a string field begins with a value.
     *
     * @param field field name
     * @param value prefix to match
     * @return prefix predicate
     */
    static @NotNull Predicate startsWith(final @NotNull String field, final @NotNull String value) {
        return new Comparison(field, Operator.STARTS_WITH, value);
    }

    /**
     * Creates a predicate that requires all supplied predicates to match.
     *
     * @param predicates predicates to combine
     * @return conjunction
     * @throws IllegalArgumentException if no predicates are supplied
     * @throws NullPointerException if a predicate is {@code null}
     */
    static @NotNull Predicate and(final @NotNull Predicate... predicates) {
        return new Junction(true, List.of(predicates));
    }

    /**
     * Creates a predicate that requires at least one supplied predicate to match.
     *
     * @param predicates predicates to combine
     * @return disjunction
     * @throws IllegalArgumentException if no predicates are supplied
     * @throws NullPointerException if a predicate is {@code null}
     */
    static @NotNull Predicate or(final @NotNull Predicate... predicates) {
        return new Junction(false, List.of(predicates));
    }
    /**
     * Creates a typed comparison using the adapter's field semantics.
     *
     * @param <T> field value type
     * @param field field reference
     * @param value comparison value
     * @return comparison predicate
     */
    static <T> Predicate ne(FieldRef<T> field, T value) {
        return ne(field.name(), value);
    }

    /**
     * Creates a typed comparison using the adapter's field semantics.
     *
     * @param <T> field value type
     * @param field field reference
     * @param value comparison value
     * @return comparison predicate
     */
    static <T> Predicate lt(FieldRef<T> field, T value) {
        return lt(field.name(), value);
    }

    /**
     * Creates a typed comparison using the adapter's field semantics.
     *
     * @param <T> field value type
     * @param field field reference
     * @param value comparison value
     * @return comparison predicate
     */
    static <T> Predicate lte(FieldRef<T> field, T value) {
        return lte(field.name(), value);
    }

    /**
     * Creates a typed comparison using the adapter's field semantics.
     *
     * @param <T> field value type
     * @param field field reference
     * @param value comparison value
     * @return comparison predicate
     */
    static <T> Predicate gt(FieldRef<T> field, T value) {
        return gt(field.name(), value);
    }

    /**
     * Creates a typed comparison using the adapter's field semantics.
     *
     * @param <T> field value type
     * @param field field reference
     * @param value comparison value
     * @return comparison predicate
     */
    static <T> Predicate gte(FieldRef<T> field, T value) {
        return gte(field.name(), value);
    }

    /**
     * Creates a typed null check.
     *
     * @param field field reference
     * @return null-check predicate
     */
    static Predicate isNull(FieldRef<?> field) {
        return isNull(field.name());
    }

    /**
     * Creates a typed null check.
     *
     * @param field field reference
     * @return null-check predicate
     */
    static Predicate isNotNull(FieldRef<?> field) {
        return isNotNull(field.name());
    }

    /**
     * Creates a typed membership predicate.
     *
     * @param <T> field value type
     * @param field field reference
     * @param values values to match
     * @return membership predicate
     */
    static <T> Predicate in(FieldRef<T> field, Collection<? extends T> values) {
        return in(field.name(), values);
    }

    /**
     * Creates a string-prefix predicate.
     *
     * @param field string field reference
     * @param value prefix
     * @return prefix predicate
     */
    static Predicate startsWith(FieldRef<String> field, String value) {
        return startsWith(field.name(), value);
    }

    /**
     * Creates a substring predicate.
     *
     * @param field string field reference
     * @param value substring
     * @return contains predicate
     */
    static Predicate contains(FieldRef<String> field, String value) {
        return contains(field.name(), value);
    }

    /**
     * Creates a collection containment predicate.
     *
     * @param <E> collection element type
     * @param field collection field reference
     * @param value element to find
     * @return contains predicate
     */
    static <E> Predicate contains(FieldRef<? extends Collection<E>> field, E value) {
        return contains(field.name(), value);
    }
}
