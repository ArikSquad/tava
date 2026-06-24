package eu.mikart.tava.schema;

import java.util.List;
import java.util.function.Consumer;

public record Schema(List<EntityDefinition> entities) {
    public Schema {
        entities = List.copyOf(entities);
        var names = new java.util.HashSet<String>();
        for (EntityDefinition entity : entities) {
            if (!names.add(entity.name())) throw new IllegalArgumentException("duplicate entity " + entity.name());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public EntityDefinition entity(String name) {
        return entities.stream().filter(entity -> entity.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity " + name));
    }

    public static final class Builder {
        private final List<EntityDefinition> entities = new java.util.ArrayList<>();

        public Builder entity(EntityDefinition definition) {
            entities.add(definition);
            return this;
        }

        public Builder entity(String name, Consumer<EntityBuilder> definition) {
            EntityBuilder builder = new EntityBuilder(name);
            definition.accept(builder);
            entities.add(builder.build());
            return this;
        }

        public Builder record(Class<? extends Record> recordType) {
            entities.add(RecordSchemas.describe(recordType));
            return this;
        }

        public Schema build() {
            return new Schema(entities);
        }
    }
}
