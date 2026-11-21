package eu.mikart.tava.query;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Computes one result per group of matching records. Group keys and metric aliases are returned as fields in each
 * result. An empty group list computes totals over the matched records. Result order is unspecified.
 *
 * @param predicate filter applied before grouping
 * @param groupBy group key fields
 * @param metrics measures to compute
 */
public record Aggregation(Predicate predicate, List<String> groupBy, List<Metric> metrics) {
    /**
     * Creates a grouped aggregation request.
     *
     * @param predicate filter applied before grouping
     * @param groupBy group key fields
     * @param metrics measures to compute
     */
    public Aggregation {
        predicate = predicate == null ? Predicate.all() : predicate;
        groupBy = List.copyOf(groupBy);
        metrics = List.copyOf(metrics);
        if (metrics.isEmpty()) throw new IllegalArgumentException("At least one metric is required");
        var names = new HashSet<String>();
        for (String field : groupBy) {
            validateField(field);
            if (!names.add(field)) throw new IllegalArgumentException("Duplicate result field: " + field);
        }
        for (Metric metric : metrics) {
            if (!names.add(metric.alias()))
                throw new IllegalArgumentException("Duplicate result field: " + metric.alias());
        }
    }

    /**
     * Copies this request with a replacement filter.
     *
     * @param filter replacement filter
     * @return request with the new filter
     */
    public Aggregation withPredicate(Predicate filter) {
        return new Aggregation(filter, groupBy, metrics);
    }

    /** Available group measures. */
    public enum Operation {
        /** Counts rows in the group. */
        COUNT,
        /** Adds numeric field values. */
        SUM,
        /** Averages numeric field values. */
        AVG,
        /** Selects the smallest field value. */
        MIN,
        /** Selects the largest field value. */
        MAX
    }

    /**
     * A measure to compute for each group.
     *
     * @param alias result field name
     * @param operation measure to compute
     * @param field source field, or null for row count
     */
    public record Metric(String alias, Operation operation, String field) {
        /**
         * Creates a group measure.
         *
         * @param alias result field name
         * @param operation measure to compute
         * @param field source field, or null for row count
         */
        public Metric {
            validateField(alias);
            Objects.requireNonNull(operation, "operation");
            if (operation == Operation.COUNT) {
                if (field != null) throw new IllegalArgumentException("Row count does not take a source field");
            } else {
                validateField(field);
            }
        }

        /**
         * Creates a row count measure.
         *
         * @param alias result field name
         * @return count metric
         */
        public static Metric count(String alias) {
            return new Metric(alias, Operation.COUNT, null);
        }
    }

    private static void validateField(String field) {
        if (field == null
                || field.isBlank()
                || field.indexOf('.') >= 0
                || field.indexOf('\0') >= 0
                || field.startsWith("$")) {
            throw new IllegalArgumentException("Expected a top-level field name: " + field);
        }
    }
}
