package eu.mikart.tava.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Range;

/** Builds an entity definition from field, index, and adapter-setting declarations. */
public final class EntityBuilder {
    private final String name;
    private final List<FieldDefinition> fields = new ArrayList<>();
    private final List<FieldBuilder> fluentFields = new ArrayList<>();
    private final List<IndexDefinition> indexes = new ArrayList<>();
    private final Map<String, Object> settings = new LinkedHashMap<>();

    /**
     * Creates a builder for an entity.
     *
     * @param name storage name of the entity
     */
    public EntityBuilder(String name) {
        this.name = name;
    }

    /**
     * Adds a field with an explicit type and nullability.
     *
     * @param name field name
     * @param type field type
     * @param nullable whether the field may contain {@code null}
     * @return this entity builder
     * @throws IllegalArgumentException if the field name or type is invalid
     */
    public EntityBuilder field(String name, FieldType type, boolean nullable) {
        fields.add(new FieldDefinition(name, type, nullable, false, false, GeneratedValue.NONE, Map.of()));
        return this;
    }

    /**
     * Starts a UUID field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder uuid(String name) {
        return field(name, FieldType.of(LogicalType.UUID));
    }

    /**
     * Starts a string field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder string(String name) {
        return field(name, FieldType.of(LogicalType.STRING));
    }

    /**
     * Starts a text field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder text(String name) {
        return field(name, FieldType.of(LogicalType.TEXT));
    }

    /**
     * Starts a 32-bit integer field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder integer(String name) {
        return field(name, FieldType.of(LogicalType.INT32));
    }

    /**
     * Alias for {@link #integer(String)}.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder int32(String name) {
        return integer(name);
    }

    /**
     * Starts a 64-bit integer field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder longInteger(String name) {
        return field(name, FieldType.of(LogicalType.INT64));
    }

    /**
     * Starts a 64-bit floating-point field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder doublePrecision(String name) {
        return field(name, FieldType.of(LogicalType.FLOAT64));
    }

    /**
     * Starts a decimal field with the given precision and scale.
     *
     * @param name field name
     * @param precision maximum number of decimal digits
     * @param scale number of digits after the decimal point
     * @return builder for the field
     * @throws IllegalArgumentException if the precision or scale is invalid
     */
    public FieldBuilder decimal(
            String name,
            @Range(from = 1, to = Integer.MAX_VALUE) int precision,
            @Range(from = 0, to = Integer.MAX_VALUE) int scale) {
        return field(name, FieldType.decimal(precision, scale));
    }

    /**
     * Starts a boolean field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder bool(String name) {
        return field(name, FieldType.of(LogicalType.BOOLEAN));
    }

    /**
     * Starts an instant field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder instant(String name) {
        return field(name, FieldType.of(LogicalType.INSTANT));
    }

    /**
     * Starts a local-date field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder localDate(String name) {
        return field(name, FieldType.of(LogicalType.LOCAL_DATE));
    }

    /**
     * Starts a binary field.
     *
     * @param name field name
     * @return builder for the field
     */
    public FieldBuilder binary(String name) {
        return field(name, FieldType.of(LogicalType.BINARY));
    }

    /**
     * Starts a field with the given type.
     *
     * @param name field name
     * @param type field type
     * @return builder for the field
     */
    public FieldBuilder field(String name, FieldType type) {
        FieldBuilder field = new FieldBuilder(this, name, type);
        fluentFields.add(field);
        return field;
    }

    /**
     * Adds a complete field definition.
     *
     * @param field field definition to add
     * @return this entity builder
     */
    public EntityBuilder field(FieldDefinition field) {
        fields.add(field);
        return this;
    }

    /**
     * Adds an index over the named fields.
     *
     * @param name index name
     * @param unique whether the index enforces uniqueness
     * @param fields indexed field names
     * @return this entity builder
     * @throws IllegalArgumentException if the index name or fields are invalid
     */
    public EntityBuilder index(String name, boolean unique, String... fields) {
        indexes.add(new IndexDefinition(name, List.of(fields), unique, Map.of()));
        return this;
    }

    /**
     * Adds an adapter-specific entity setting.
     *
     * @param key setting name
     * @param value setting value
     * @return this entity builder
     */
    public EntityBuilder setting(String key, Object value) {
        settings.put(key, value);
        return this;
    }

    /**
     * Creates the entity definition, including all fields configured on this builder.
     *
     * @return immutable entity definition
     * @throws IllegalArgumentException if the entity definition contains invalid or duplicate fields
     */
    public EntityDefinition build() {
        List<FieldDefinition> definitions = new ArrayList<>(fields);
        fluentFields.stream().map(FieldBuilder::build).forEach(definitions::add);
        return new EntityDefinition(name, definitions, indexes, settings);
    }

    /** Configures one field before it is included in its entity definition. */
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

        /**
         * Makes the field required.
         *
         * @return this field builder
         */
        public FieldBuilder required() {
            return notNull();
        }

        /**
         * Disallows {@code null} values for the field.
         *
         * @return this field builder
         */
        public FieldBuilder notNull() {
            nullable = false;
            return this;
        }

        /**
         * Allows {@code null} values for the field.
         *
         * @return this field builder
         */
        public FieldBuilder nullable() {
            nullable = true;
            return this;
        }

        /**
         * Marks the field as part of the entity identity and makes it non-nullable.
         *
         * @return this field builder
         */
        public FieldBuilder identity() {
            identity = true;
            nullable = false;
            return this;
        }

        /**
         * Marks the field as unique.
         *
         * @return this field builder
         */
        public FieldBuilder unique() {
            unique = true;
            return this;
        }

        /**
         * Sets how the field value is generated.
         *
         * @param value generation strategy
         * @return this field builder
         */
        public FieldBuilder generated(GeneratedValue value) {
            generated = value;
            return this;
        }

        /**
         * Sets the maximum length of this string field.
         *
         * @param length maximum string length
         * @return this field builder
         * @throws IllegalArgumentException if {@code length} is less than one
         * @deprecated Use {@link #length(int)} for a clearer method name.
         */
        @Deprecated(since = "1.3.0", forRemoval = false)
        public FieldBuilder field(@Range(from = 1, to = Integer.MAX_VALUE) int length) {
            return length(length);
        }

        /**
         * Sets this field to a string type with a maximum length.
         *
         * @param length maximum string length
         * @return this field builder
         * @throws IllegalArgumentException if {@code length} is less than one
         */
        public FieldBuilder length(@Range(from = 1, to = Integer.MAX_VALUE) int length) {
            type = FieldType.string(length);
            return this;
        }

        /**
         * Sets this field to a decimal type with the given precision and scale.
         *
         * @param precision maximum number of decimal digits
         * @param scale number of digits after the decimal point
         * @return this field builder
         * @throws IllegalArgumentException if the precision or scale is invalid
         */
        public FieldBuilder decimal(
                @Range(from = 1, to = Integer.MAX_VALUE) int precision,
                @Range(from = 0, to = Integer.MAX_VALUE) int scale) {
            type = FieldType.decimal(precision, scale);
            return this;
        }

        /**
         * Adds an adapter-specific field setting.
         *
         * @param key setting name
         * @param value setting value
         * @return this field builder
         */
        public FieldBuilder setting(String key, Object value) {
            settings.put(key, value);
            return this;
        }

        /**
         * Adds this field to the entity and returns its builder.
         *
         * @return parent entity builder
         * @throws IllegalArgumentException if the field name or type is invalid
         */
        public EntityBuilder add() {
            if (entity.fluentFields.remove(this)) entity.field(build());
            return entity;
        }

        private FieldDefinition build() {
            return new FieldDefinition(name, type, nullable, identity, unique, generated, settings);
        }
    }
}
