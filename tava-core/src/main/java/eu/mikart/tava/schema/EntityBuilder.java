package eu.mikart.tava.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityBuilder {
    private final String name;
    private final List<FieldDefinition> fields = new ArrayList<>();
    private final List<IndexDefinition> indexes = new ArrayList<>();
    private final Map<String, Object> settings = new LinkedHashMap<>();

    public EntityBuilder(String name) {
        this.name = name;
    }

    public EntityBuilder field(String name, FieldType type, boolean nullable) {
        fields.add(new FieldDefinition(name, type, nullable, false, false, GeneratedValue.NONE, Map.of()));
        return this;
    }

    public EntityBuilder field(FieldDefinition field) {
        fields.add(field);
        return this;
    }

    public EntityBuilder index(String name, boolean unique, String... fields) {
        indexes.add(new IndexDefinition(name, List.of(fields), unique, Map.of()));
        return this;
    }

    public EntityBuilder setting(String key, Object value) {
        settings.put(key, value);
        return this;
    }

    public EntityDefinition build() {
        return new EntityDefinition(name, fields, indexes, settings);
    }
}
