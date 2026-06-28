package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public record IndexDefinition(
        @NotNull String name,
        @NotNull List<String> fields,
        boolean unique,
        @NotNull Map<String, Object> settings
) {
    public IndexDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("index name is required");
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("index requires fields");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
