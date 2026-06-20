package eu.mikart.tava.query;

import java.util.LinkedHashMap;
import java.util.Map;

public record Mutation(Map<String, Object> values) {
    public Mutation {
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder set(String field, Object value) {
            values.put(field, value);
            return this;
        }

        public Mutation build() {
            return new Mutation(values);
        }
    }
}
