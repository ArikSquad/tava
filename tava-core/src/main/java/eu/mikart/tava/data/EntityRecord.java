package eu.mikart.tava.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EntityRecord(@NotNull Map<String, Object> values) {
    public EntityRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public @Nullable Object get(final @NotNull String field) {
        return values.get(field);
    }

    public <T> @Nullable T get(final @NotNull String field, final @NotNull Class<T> type) {
        return type.cast(values.get(field));
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static @NotNull EntityRecord of(final @NotNull Map<String, Object> values) {
        return new EntityRecord(values);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public @NotNull Builder set(final @NotNull String field, final @Nullable Object value) {
            values.put(field, value);
            return this;
        }

        public @NotNull EntityRecord build() {
            return new EntityRecord(values);
        }
    }
}
