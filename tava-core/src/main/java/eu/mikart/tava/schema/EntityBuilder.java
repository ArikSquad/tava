package eu.mikart.tava.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityBuilder {
    private final String name;
    private final List<FieldDefinition> fields = new ArrayList<>();
    private final List<FieldBuilder> fluentFields = new ArrayList<>();
    private final List<IndexDefinition> indexes = new ArrayList<>();
    private final Map<String, Object> settings = new LinkedHashMap<>();

    public EntityBuilder(String name) {
        this.name = name;
    }

    public EntityBuilder field(String name, FieldType type, boolean nullable) {
        fields.add(new FieldDefinition(name, type, nullable, false, false, GeneratedValue.NONE, Map.of()));
        return this;
    }

    public FieldBuilder uuid(String name) {
        return field(name, FieldType.of(LogicalType.UUID));
    }

    public FieldBuilder string(String name) {
        return field(name, FieldType.of(LogicalType.STRING));
    }

    public FieldBuilder text(String name) {
        return field(name, FieldType.of(LogicalType.TEXT));
    }

    public FieldBuilder integer(String name) {
        return field(name, FieldType.of(LogicalType.INT32));
    }

    public FieldBuilder int32(String name) {
        return integer(name);
    }

    public FieldBuilder longInteger(String name) {
        return field(name, FieldType.of(LogicalType.INT64));
    }

    public FieldBuilder decimal(String name, int precision, int scale) {
        return field(name, FieldType.decimal(precision, scale));
    }

    public FieldBuilder bool(String name) {
        return field(name, FieldType.of(LogicalType.BOOLEAN));
    }

    public FieldBuilder instant(String name) {
        return field(name, FieldType.of(LogicalType.INSTANT));
    }

    public FieldBuilder localDate(String name) {
        return field(name, FieldType.of(LogicalType.LOCAL_DATE));
    }

    public FieldBuilder binary(String name) {
        return field(name, FieldType.of(LogicalType.BINARY));
    }

    public FieldBuilder field(String name, FieldType type) {
        FieldBuilder field = new FieldBuilder(this, name, type);
        fluentFields.add(field);
        return field;
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
        List<FieldDefinition> definitions = new ArrayList<>(fields);
        fluentFields.stream().map(FieldBuilder::build).forEach(definitions::add);
        return new EntityDefinition(name, definitions, indexes, settings);
    }

    public static final class FieldBuilder {
        private final EntityBuilder entity;
        private final String name;
        private FieldType type;
        private boolean nullable = true;
        private boolean identity;
        private boolean unique;
        private GeneratedValue generated = GeneratedValue.NONE;
        private final Map<String, Object> settings = new LinkedHashMap<>();

        private FieldBuilder(EntityBuilder entity, String name, FieldType type) {
            this.entity = entity;
            this.name = name;
            this.type = type;
        }

        public FieldBuilder required() {
            return notNull();
        }

        public FieldBuilder notNull() {
            nullable = false;
            return this;
        }

        public FieldBuilder nullable() {
            nullable = true;
            return this;
        }

        public FieldBuilder identity() {
            identity = true;
            nullable = false;
            return this;
        }

        public FieldBuilder unique() {
            unique = true;
            return this;
        }

        public FieldBuilder generated(GeneratedValue value) {
            generated = value;
            return this;
        }

        public FieldBuilder field(int length) {
            return length(length);
        }

        public FieldBuilder length(int length) {
            type = FieldType.string(length);
            return this;
        }

        public FieldBuilder decimal(int precision, int scale) {
            type = FieldType.decimal(precision, scale);
            return this;
        }

        public FieldBuilder setting(String key, Object value) {
            settings.put(key, value);
            return this;
        }

        public EntityBuilder add() {
            if (entity.fluentFields.remove(this)) entity.field(build());
            return entity;
        }

        private FieldDefinition build() {
            return new FieldDefinition(name, type, nullable, identity, unique, generated, settings);
        }
    }
}
