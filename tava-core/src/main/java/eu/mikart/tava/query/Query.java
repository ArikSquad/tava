package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record Query(
        @NotNull Predicate predicate,
        @NotNull List<String> projection,
        @NotNull List<Sort> sorting,
        int limit,
        @Nullable String cursor
) {
    public Query {
        predicate = predicate == null ? Predicate.all() : predicate;
        projection = projection == null ? List.of() : List.copyOf(projection);
        sorting = sorting == null ? List.of() : List.copyOf(sorting);
        if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static @NotNull Query all() {
        return builder().build();
    }

    public static final class Builder {
        private Predicate predicate = Predicate.all();
        private List<String> projection = List.of();
        private final List<Sort> sorting = new java.util.ArrayList<>();
        private int limit;
        private String cursor;

        public @NotNull Builder where(final @Nullable Predicate predicate) {
            this.predicate = predicate;
            return this;
        }

        public @NotNull Builder project(final @NotNull String... fields) {
            this.projection = List.of(fields);
            return this;
        }

        public @NotNull Builder sort(final @NotNull Sort... values) {
            sorting.addAll(List.of(values));
            return this;
        }

        public @NotNull Builder limit(final int value) {
            this.limit = value;
            return this;
        }

        public @NotNull Builder cursor(final @Nullable String value) {
            this.cursor = value;
            return this;
        }

        public @NotNull Query build() {
            return new Query(predicate, projection, sorting, limit, cursor);
        }
    }
}
