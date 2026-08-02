package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit old-to-new names used when adopting or evolving an existing schema. */
public record SchemaRenames(@NotNull Map<String, String> entities,
                            @NotNull Map<String, Map<String, String>> fields) {
    public SchemaRenames {
        entities = Map.copyOf(entities);
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        fields.forEach((entity, names) -> copy.put(entity, Map.copyOf(names)));
        fields = Map.copyOf(copy);
    }
    public static @NotNull SchemaRenames none() { return new SchemaRenames(Map.of(), Map.of()); }
    public static @NotNull Builder builder() { return new Builder(); }
    public boolean empty() { return entities.isEmpty() && fields.isEmpty(); }

    public static final class Builder {
        private final Map<String, String> entities = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> fields = new LinkedHashMap<>();
        public Builder entity(@NotNull String from, @NotNull String to) {
            entities.put(required(from), required(to)); return this;
        }
        /** The entity name is the final (new) entity name. */
        public Builder field(@NotNull String entity, @NotNull String from, @NotNull String to) {
            fields.computeIfAbsent(required(entity), ignored -> new LinkedHashMap<>()).put(required(from), required(to)); return this;
        }
        public SchemaRenames build() { return new SchemaRenames(entities, fields); }
        private static String required(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("schema name is required");
            return value;
        }
    }
}
