package eu.mikart.tava.spi;

import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Sort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Constructs a strict, deterministic order and its continuation predicate.
 *
 * @since 1.3.0
 */
public final class KeysetPagination {
    private KeysetPagination() {}

    /**
     * Appends any missing identity fields to make the requested order unique.
     *
     * @param requested requested sort fields
     * @param identityFields fields that uniquely identify each record
     * @return immutable effective order
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if identity fields are empty or contain null or blank values, or if the
     *     requested order repeats a field
     * @since 1.3.0
     */
    public static List<Sort> ordering(List<Sort> requested, List<String> identityFields) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(identityFields, "identityFields");
        if (identityFields.isEmpty())
            throw new IllegalArgumentException("Keyset pagination requires a unique identity");
        List<Sort> order = new ArrayList<>(requested);
        Set<String> seen = new HashSet<>();
        for (Sort sort : requested) {
            if (!seen.add(sort.field())) throw new IllegalArgumentException("Repeated sort field: " + sort.field());
        }
        for (String field : identityFields) {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("Identity field is required");
            if (seen.add(field)) order.add(Sort.asc(field));
        }
        return List.copyOf(order);
    }

    /**
     * Builds the predicate for records after a continuation position. Nulls sort first in ascending order and last in
     * descending order.
     *
     * @param order complete effective ordering
     * @param values position values in order
     * @return predicate for subsequent records
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the order is empty, value counts differ, or the position has no successor
     * @since 1.3.0
     */
    public static Predicate after(List<Sort> order, List<?> values) {
        if (order.isEmpty() || order.size() != values.size())
            throw new IllegalArgumentException("Invalid continuation values");
        List<Predicate> alternatives = new ArrayList<>();
        List<Predicate> equalPrefix = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Sort sort = order.get(i);
            Object value = values.get(i);
            Predicate next;
            if (value == null) {
                next = sort.direction() == Sort.Direction.ASC ? Predicate.isNotNull(sort.field()) : null;
            } else if (sort.direction() == Sort.Direction.ASC) {
                next = Predicate.gt(sort.field(), value);
            } else {
                next = Predicate.or(Predicate.lt(sort.field(), value), Predicate.isNull(sort.field()));
            }
            if (next != null) {
                List<Predicate> branch = new ArrayList<>(equalPrefix);
                branch.add(next);
                alternatives.add(new Predicate.Junction(true, branch));
            }
            equalPrefix.add(value == null ? Predicate.isNull(sort.field()) : Predicate.eq(sort.field(), value));
        }
        if (alternatives.isEmpty()) throw new IllegalArgumentException("Continuation has no subsequent values");
        return new Predicate.Junction(false, alternatives);
    }
}
