package eu.mikart.tava.query;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

/**
 * A page request. Cursors continue an ordering; they do not preserve a database snapshot. Concurrent changes to records
 * or their sort values may affect later pages. Reuse a cursor only with the same adapter, entity, predicate,
 * projection, sorting, and page size.
 *
 * @param predicate filter applied to each page
 * @param projection fields returned for each record, or empty for all fields
 * @param sorting requested order before unique identity fields are appended
 * @param limit maximum records returned, with zero selecting the adapter default
 * @param cursor opaque continuation token returned by the previous page
 */
public record Query(
        @NotNull Predicate predicate,
        @NotNull List<String> projection,
        @NotNull List<Sort> sorting,
        @Range(from = 0, to = Integer.MAX_VALUE - 1) int limit,
        @Nullable String cursor) {
    /**
     * Creates a query without a continuation cursor.
     *
     * @param predicate filter applied to each page
     * @param projection fields returned for each record
     * @param sorting requested order
     * @param limit maximum records returned, with zero selecting the adapter default
     * @since 1.3.0
     */
    public Query(
            @NotNull Predicate predicate,
            @NotNull List<String> projection,
            @NotNull List<Sort> sorting,
            @Range(from = 0, to = Integer.MAX_VALUE - 1) int limit) {
        this(predicate, projection, sorting, limit, null);
    }

    /**
     * Creates a validated query request.
     *
     * @param predicate filter applied to each page
     * @param projection fields returned for each record, or empty for all fields
     * @param sorting requested order before unique identity fields are appended
     * @param limit maximum records returned, with zero selecting the adapter default
     * @param cursor opaque continuation token returned by the previous page
     */
    public Query {
        predicate = predicate == null ? Predicate.all() : predicate;
        projection = projection == null ? List.of() : List.copyOf(projection);
        sorting = sorting == null ? List.of() : List.copyOf(sorting);
        if (limit < 0 || limit == Integer.MAX_VALUE)
            throw new IllegalArgumentException("limit must be between 0 and Integer.MAX_VALUE - 1");
    }

    /**
     * Creates a builder with default filtering and paging options.
     *
     * @return a query builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Creates a query that matches all records using default paging options.
     *
     * @return an unfiltered query
     */
    public static @NotNull Query all() {
        return builder().build();
    }

    /** Configures a query request. */
    public static final class Builder {
        private Predicate predicate = Predicate.all();
        private List<String> projection = List.of();
        private final List<Sort> sorting = new ArrayList<>();
        private int limit;
        private String cursor;

        /** Creates a query builder with default options. */
        public Builder() {}

        /**
         * Sets the predicate applied to each page.
         *
         * @param predicate filter predicate, or {@code null} to match all records
         * @return this builder
         */
        public @NotNull Builder where(final @Nullable Predicate predicate) {
            this.predicate = predicate;
            return this;
        }

        /**
         * Sets the fields returned for each record.
         *
         * @param fields projected field names; no fields selects all fields
         * @return this builder
         */
        public @NotNull Builder project(final @NotNull String... fields) {
            this.projection = List.of(fields);
            return this;
        }

        /**
         * Appends sort fields to the requested order.
         *
         * @param values sort fields
         * @return this builder
         */
        public @NotNull Builder sort(final @NotNull Sort... values) {
            sorting.addAll(List.of(values));
            return this;
        }

        /**
         * Sets the maximum number of records returned in one page.
         *
         * @param value page size, or zero to use the adapter default
         * @return this builder
         */
        public @NotNull Builder limit(final @Range(from = 0, to = Integer.MAX_VALUE - 1) int value) {
            this.limit = value;
            return this;
        }

        /**
         * Continues after the position in a previous page.
         *
         * @param value opaque cursor returned by the previous page, or {@code null} for the first page
         * @return this builder
         */
        public @NotNull Builder cursor(final @Nullable String value) {
            this.cursor = value;
            return this;
        }

        /**
         * Builds the immutable query request.
         *
         * @return query request
         */
        public @NotNull Query build() {
            return new Query(predicate, projection, sorting, limit, cursor);
        }
    }
}
