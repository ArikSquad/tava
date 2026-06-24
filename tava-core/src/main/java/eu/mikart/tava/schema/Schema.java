package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record Schema(@NotNull List<EntityDefinition> entities) {
    public Schema {
        entities = List.copyOf(entities);
        final var names = new java.util.HashSet<String>();
        for (final EntityDefinition entity : entities) {
            if (!names.add(entity.name())) throw new IllegalArgumentException("duplicate entity " + entity.name());
        }
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public @NotNull EntityDefinition entity(final @NotNull String name) {
        return entities.stream().filter(entity -> entity.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity " + name));
    }

    public static final class Builder {
        private final List<EntityDefinition> entities = new java.util.ArrayList<>();

        public @NotNull Builder entity(final @NotNull EntityDefinition definition) {
            entities.add(definition);
            return this;
        }

        public @NotNull Builder record(final @NotNull Class<? extends Record> recordType) {
            entities.add(RecordSchemas.describe(recordType));
            return this;
        }

        public @NotNull Schema build() {
            return new Schema(entities);
        }
    }
}
