package eu.mikart.tava.data;

import java.util.LinkedHashMap;
import java.util.Map;

public record EntityRecord(Map<String, Object> values) {
    public EntityRecord {
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Object get(String field) {
        return values.get(field);
    }

    public <T> T get(String field, Class<T> type) {
        return type.cast(values.get(field));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EntityRecord of(Map<String, Object> values) {
        return new EntityRecord(values);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder set(String field, Object value) {
            values.put(field, value);
            return this;
        }

        public EntityRecord build() {
            return new EntityRecord(values);
        }
    }
}
