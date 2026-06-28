package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public sealed interface Predicate permits Predicate.All, Predicate.Comparison, Predicate.Junction {
    record All() implements Predicate {
    }

    record Comparison(@NotNull String field, @NotNull Operator operator, @Nullable Object value) implements Predicate {
        public Comparison {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
            if (operator == null) throw new IllegalArgumentException("operator is required");
        }
    }

    record Junction(boolean and, @NotNull List<Predicate> predicates) implements Predicate {
        public Junction {
            predicates = List.copyOf(predicates);
        }
    }

    enum Operator {EQ, NE, LT, LTE, GT, GTE, IN, CONTAINS, STARTS_WITH, IS_NULL, IS_NOT_NULL}

    static @NotNull Predicate all() {
        return new All();
    }

    static @NotNull Predicate eq(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.EQ, value);
    }

    static @NotNull Predicate ne(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.NE, value);
    }

    static @NotNull Predicate isNull(final @NotNull String field) {
        return new Comparison(field, Operator.IS_NULL, null);
    }

    static @NotNull Predicate isNotNull(final @NotNull String field) {
        return new Comparison(field, Operator.IS_NOT_NULL, null);
    }

    static @NotNull Predicate lt(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.LT, value);
    }

    static @NotNull Predicate lte(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.LTE, value);
    }

    static @NotNull Predicate gt(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.GT, value);
    }

    static @NotNull Predicate gte(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.GTE, value);
    }

    static @NotNull Predicate in(final @NotNull String field, final @NotNull java.util.Collection<?> values) {
        return new Comparison(field, Operator.IN, List.copyOf(values));
    }

    static @NotNull Predicate contains(final @NotNull String field, final @Nullable Object value) {
        return new Comparison(field, Operator.CONTAINS, value);
    }

    static @NotNull Predicate startsWith(final @NotNull String field, final @NotNull String value) {
        return new Comparison(field, Operator.STARTS_WITH, value);
    }

    static @NotNull Predicate and(final @NotNull Predicate... predicates) {
        return new Junction(true, List.of(predicates));
    }

    static @NotNull Predicate or(final @NotNull Predicate... predicates) {
        return new Junction(false, List.of(predicates));
    }
}
