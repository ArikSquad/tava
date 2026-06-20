package eu.mikart.tava.query;

import java.util.List;

public sealed interface Predicate permits Predicate.All, Predicate.Comparison, Predicate.Junction {
    record All() implements Predicate {
    }

    record Comparison(String field, Operator operator, Object value) implements Predicate {
        public Comparison {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
            if (operator == null) throw new IllegalArgumentException("operator is required");
        }
    }

    record Junction(boolean and, List<Predicate> predicates) implements Predicate {
        public Junction {
            predicates = List.copyOf(predicates);
        }
    }

    enum Operator {EQ, NE, LT, LTE, GT, GTE, IN, CONTAINS, STARTS_WITH}

    static Predicate all() {
        return new All();
    }

    static Predicate eq(String field, Object value) {
        return new Comparison(field, Operator.EQ, value);
    }

    static Predicate ne(String field, Object value) {
        return new Comparison(field, Operator.NE, value);
    }

    static Predicate lt(String field, Object value) {
        return new Comparison(field, Operator.LT, value);
    }

    static Predicate lte(String field, Object value) {
        return new Comparison(field, Operator.LTE, value);
    }

    static Predicate gt(String field, Object value) {
        return new Comparison(field, Operator.GT, value);
    }

    static Predicate gte(String field, Object value) {
        return new Comparison(field, Operator.GTE, value);
    }

    static Predicate in(String field, java.util.Collection<?> values) {
        return new Comparison(field, Operator.IN, List.copyOf(values));
    }

    static Predicate contains(String field, Object value) {
        return new Comparison(field, Operator.CONTAINS, value);
    }

    static Predicate startsWith(String field, String value) {
        return new Comparison(field, Operator.STARTS_WITH, value);
    }

    static Predicate and(Predicate... predicates) {
        return new Junction(true, List.of(predicates));
    }

    static Predicate or(Predicate... predicates) {
        return new Junction(false, List.of(predicates));
    }
}
