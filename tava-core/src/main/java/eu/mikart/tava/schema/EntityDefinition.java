package eu.mikart.tava.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable schema definition for one entity.
 *
 * @param name entity storage name
 * @param fields definitions of the entity's fields
 * @param indexes index definitions for the entity
 * @param settings adapter-specific entity settings
 */
public record EntityDefinition(
        @NotNull String name,
        @NotNull List<FieldDefinition> fields,
        @NotNull List<IndexDefinition> indexes,
        @NotNull Map<String, Object> settings) {
    /**
     * Creates an entity definition and validates its field names.
     *
     * @param name entity storage name
     * @param fields definitions of the entity's fields
     * @param indexes index definitions for the entity; {@code null} is treated as empty
     * @param settings adapter-specific entity settings; {@code null} is treated as empty
     * @throws IllegalArgumentException if the name is blank or fields have duplicate names
     * @throws NullPointerException if fields is {@code null} or contains {@code null}, indexes contains {@code null},
     *     or settings contains a null key or value
     */
    public EntityDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("entity name is required");
        fields = List.copyOf(fields);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        final var names = new HashSet<String>();
        for (final FieldDefinition field : fields) {
            if (!names.add(field.name())) throw new IllegalArgumentException("duplicate field " + field.name());
        }
    }

    /**
     * Returns the field with the given name.
     *
     * @param name field name
     * @return matching field definition
     * @throws IllegalArgumentException if the entity has no field with that name
     */
    public @NotNull FieldDefinition field(final @NotNull String name) {
        return fields.stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field " + name + " in " + this.name));
    }
}
