package eu.mikart.tava.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Explicit old-to-new names used when adopting or evolving an existing schema.
 *
 * @param entities mappings from existing entity names to desired entity names
 * @param fields mappings from desired entity names to old-to-new field-name mappings
 */
public record SchemaRenames(
        @NotNull Map<String, String> entities, @NotNull Map<String, Map<String, String>> fields) {
    /**
     * Copies and freezes the supplied rename mappings.
     *
     * @param entities mappings from existing entity names to desired entity names
     * @param fields mappings from desired entity names to old-to-new field-name mappings
     * @throws NullPointerException if a mapping or any mapping key or value is {@code null}
     */
    public SchemaRenames {
        entities = Map.copyOf(entities);
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        fields.forEach((entity, names) -> copy.put(entity, Map.copyOf(names)));
        fields = Map.copyOf(copy);
    }

    /**
     * Returns an empty set of rename mappings.
     *
     * @return schema renames with no entity or field mappings
     */
    public static @NotNull SchemaRenames none() {
        return new SchemaRenames(Map.of(), Map.of());
    }

    /**
     * Creates a builder for entity and field rename mappings.
     *
     * @return a new schema rename builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether there are no entity or field rename mappings.
     *
     * @return {@code true} if both mapping sets are empty
     */
    public boolean empty() {
        return entities.isEmpty() && fields.isEmpty();
    }

    /** Builds mappings from existing entity and field names to desired names. */
    public static final class Builder {
        private final Map<String, String> entities = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> fields = new LinkedHashMap<>();

        /** Creates a schema rename builder. */
        public Builder() {}

        /**
         * Maps an existing entity name to its desired name.
         *
         * @param from existing entity name
         * @param to desired entity name
         * @return this builder
         * @throws IllegalArgumentException if either name is {@code null} or blank
         */
        public Builder entity(@NotNull String from, @NotNull String to) {
            entities.put(required(from), required(to));
            return this;
        }
        /**
         * Maps an existing field name to its desired name.
         *
         * @param entity final (new) entity name
         * @param from existing field name
         * @param to desired field name
         * @return this builder
         * @throws IllegalArgumentException if any name is {@code null} or blank
         */
        public Builder field(@NotNull String entity, @NotNull String from, @NotNull String to) {
            fields.computeIfAbsent(required(entity), ignored -> new LinkedHashMap<>())
                    .put(required(from), required(to));
            return this;
        }

        /**
         * Creates immutable rename mappings from the names added to this builder.
         *
         * @return schema rename mappings
         */
        public SchemaRenames build() {
            return new SchemaRenames(entities, fields);
        }

        private static String required(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("schema name is required");
            return value;
        }
    }
}
