package eu.mikart.tava.schema;

import java.util.List;
import java.util.Map;

public record IndexDefinition(String name, List<String> fields, boolean unique, Map<String, Object> settings) {
    public IndexDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("index name is required");
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("index requires fields");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
