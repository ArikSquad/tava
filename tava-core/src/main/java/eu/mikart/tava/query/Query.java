package eu.mikart.tava.query;

import java.util.List;

public record Query(
        Predicate predicate,
        List<String> projection,
        List<Sort> sorting,
        int limit,
        String cursor
) {
    public Query {
        predicate = predicate == null ? Predicate.all() : predicate;
        projection = projection == null ? List.of() : List.copyOf(projection);
        sorting = sorting == null ? List.of() : List.copyOf(sorting);
        if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Query all() {
        return builder().build();
    }

    public static final class Builder {
        private Predicate predicate = Predicate.all();
        private List<String> projection = List.of();
        private final List<Sort> sorting = new java.util.ArrayList<>();
        private int limit;
        private String cursor;

        public Builder where(Predicate predicate) {
            this.predicate = predicate;
            return this;
        }

        public Builder project(String... fields) {
            this.projection = List.of(fields);
            return this;
        }

        public Builder sort(Sort... values) {
            sorting.addAll(List.of(values));
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder cursor(String value) {
            this.cursor = value;
            return this;
        }

        public Query build() {
            return new Query(predicate, projection, sorting, limit, cursor);
        }
    }
}
