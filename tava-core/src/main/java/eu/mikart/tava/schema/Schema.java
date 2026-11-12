package eu.mikart.tava.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable collection of entity definitions.
 *
 * @param entities entity definitions in the schema
 */
public record Schema(@NotNull List<EntityDefinition> entities) {
    /**
     * Creates a schema with unique entity names.
     *
     * @param entities entity definitions in the schema
     * @throws IllegalArgumentException if entity names are duplicated
     * @throws NullPointerException if the list or an entity definition is {@code null}
     */
    public Schema {
        entities = List.copyOf(entities);
        final var names = new HashSet<String>();
        for (final EntityDefinition entity : entities) {
            if (!names.add(entity.name())) throw new IllegalArgumentException("duplicate entity " + entity.name());
        }
    }

    /**
     * Creates a schema from record types in declaration order.
     *
     * @param recordTypes record types to describe
     * @return immutable schema
     */
    @SafeVarargs
    public static @NotNull Schema of(Class<? extends Record>... recordTypes) {
        Builder builder = builder();
        for (Class<? extends Record> type : recordTypes) builder.record(type);
        return builder.build();
    }

    /**
     * Creates a builder for an entity schema.
     *
     * @return a new schema builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Returns the entity with the given name.
     *
     * @param name entity name
     * @return matching entity definition
     * @throws IllegalArgumentException if no entity has that name
     */
    public @NotNull EntityDefinition entity(final @NotNull String name) {
        return entities.stream()
                .filter(entity -> entity.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity " + name));
    }

    /** Builds a schema by adding entity definitions one at a time. */
    public static final class Builder {
        private final List<EntityDefinition> entities = new ArrayList<>();

        /** Creates an empty schema builder. */
        public Builder() {}

        /**
         * Adds an entity definition to the schema.
         *
         * @param definition entity definition to add
         * @return this builder
         */
        public @NotNull Builder entity(final @NotNull EntityDefinition definition) {
            entities.add(definition);
            return this;
        }

        /**
         * Builds an entity definition with a callback and adds it to the schema.
         *
         * @param name entity name
         * @param definition callback that declares the entity's fields, indexes, and settings
         * @return this builder
         * @throws NullPointerException if {@code definition} is {@code null}
         * @throws IllegalArgumentException if the built entity definition is invalid
         */
        public Builder entity(String name, Consumer<EntityBuilder> definition) {
            EntityBuilder builder = new EntityBuilder(name);
            definition.accept(builder);
            entities.add(builder.build());
            return this;
        }

        /**
         * Adds the schema derived from a Java record type.
         *
         * @param recordType record type to inspect
         * @return this builder
         * @throws IllegalArgumentException if the type is not a record or has an unsupported field type
         * @throws NullPointerException if {@code recordType} is {@code null}
         */
        public @NotNull Builder record(final @NotNull Class<? extends Record> recordType) {
            entities.add(RecordSchemas.describe(recordType));
            return this;
        }

        /**
         * Creates an immutable schema from the added entities.
         *
         * @return schema containing the added entities
         * @throws IllegalArgumentException if entity names are duplicated
         * @throws NullPointerException if a null entity definition was added
         */
        public @NotNull Schema build() {
            return new Schema(entities);
        }
    }
}
