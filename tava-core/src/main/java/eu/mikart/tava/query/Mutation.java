package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public record Mutation(@NotNull Map<String, Object> values) {
    public Mutation {
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public @NotNull Builder set(final @NotNull String field, final @Nullable Object value) {
            values.put(field, value);
            return this;
        }

        public @NotNull Mutation build() {
            return new Mutation(values);
        }
    }
}
