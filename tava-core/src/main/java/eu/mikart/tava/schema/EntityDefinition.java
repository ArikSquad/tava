package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public record EntityDefinition(
        @NotNull String name,
        @NotNull List<FieldDefinition> fields,
        @NotNull List<IndexDefinition> indexes,
        @NotNull Map<String, Object> settings
) {
    public EntityDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("entity name is required");
        fields = List.copyOf(fields);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        final var names = new java.util.HashSet<String>();
        for (final FieldDefinition field : fields) {
            if (!names.add(field.name())) throw new IllegalArgumentException("duplicate field " + field.name());
        }
    }

    public @NotNull FieldDefinition field(final @NotNull String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field " + name + " in " + this.name));
    }
}
