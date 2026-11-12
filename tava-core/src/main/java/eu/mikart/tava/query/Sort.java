package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;

/**
 * Sort order for one query field.
 *
 * @param field field name
 * @param direction sort direction
 */
public record Sort(@NotNull String field, @NotNull Direction direction) {
    /**
     * Creates a sort order.
     *
     * @param field field name
     * @param direction sort direction
     * @throws IllegalArgumentException if the field is blank or the direction is {@code null}
     */
    public Sort {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
        if (direction == null) throw new IllegalArgumentException("direction is required");
    }

    /** Direction in which field values are ordered. */
    public enum Direction {
        /** Order values from lowest to highest. */
        ASC,
        /** Order values from highest to lowest. */
        DESC
    }

    /**
     * Creates an ascending sort order.
     *
     * @param field field name
     * @return ascending sort order
     * @throws IllegalArgumentException if the field is blank
     */
    public static @NotNull Sort asc(final @NotNull String field) {
        return new Sort(field, Direction.ASC);
    }

    /**
     * Creates a descending sort order.
     *
     * @param field field name
     * @return descending sort order
     * @throws IllegalArgumentException if the field is blank
     */
    public static @NotNull Sort desc(final @NotNull String field) {
        return new Sort(field, Direction.DESC);
    }
    /**
     * Creates a portable field expression.
     *
     * @param field typed field reference
     * @return ascending sort order
     */
    public static Sort asc(FieldRef<?> field) {
        return asc(field.name());
    }

    /**
     * Creates a portable field expression.
     *
     * @param field typed field reference
     * @return descending sort order
     */
    public static Sort desc(FieldRef<?> field) {
        return desc(field.name());
    }
}
