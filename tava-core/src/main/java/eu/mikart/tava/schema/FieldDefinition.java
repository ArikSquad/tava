package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

public record FieldDefinition(
        @NotNull String name,
        @NotNull FieldType type,
        boolean nullable,
        boolean identity,
        boolean unique,
        @NotNull GeneratedValue generated,
        @NotNull Map<String, Object> settings
) {
    public FieldDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required");
        generated = generated == null ? GeneratedValue.NONE : generated;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        if (identity && nullable) throw new IllegalArgumentException("identity field cannot be nullable");
    }
}
