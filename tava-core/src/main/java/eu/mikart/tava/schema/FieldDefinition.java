package eu.mikart.tava.schema;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Schema metadata for one entity field.
 *
 * @param name field name
 * @param type logical field type
 * @param nullable whether the field may contain {@code null}
 * @param identity whether the field participates in the entity identity
 * @param unique whether the field must be unique
 * @param generated value-generation strategy
 * @param settings adapter-specific field settings
 */
public record FieldDefinition(
        @NotNull String name,
        @NotNull FieldType type,
        boolean nullable,
        boolean identity,
        boolean unique,
        @NotNull GeneratedValue generated,
        @NotNull Map<String, Object> settings) {
    /**
     * Creates field metadata and validates its name, type, and identity nullability.
     *
     * @param name field name
     * @param type logical field type
     * @param nullable whether the field may contain {@code null}
     * @param identity whether the field participates in the entity identity
     * @param unique whether the field must be unique
     * @param generated value-generation strategy; {@code null} means {@link GeneratedValue#NONE}
     * @param settings adapter-specific field settings; {@code null} is treated as empty
     * @throws IllegalArgumentException if the name is blank, the type is {@code null}, or an identity field is nullable
     * @throws NullPointerException if settings contains a null key or value
     */
    public FieldDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required");
        generated = generated == null ? GeneratedValue.NONE : generated;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        if (identity && nullable) throw new IllegalArgumentException("identity field cannot be nullable");
    }
}
