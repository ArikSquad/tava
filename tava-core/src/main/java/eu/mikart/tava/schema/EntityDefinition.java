package eu.mikart.tava.schema;

import java.util.List;
import java.util.Map;

public record EntityDefinition(
        String name,
        List<FieldDefinition> fields,
        List<IndexDefinition> indexes,
        Map<String, Object> settings
) {
    public EntityDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("entity name is required");
        fields = List.copyOf(fields);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        var names = new java.util.HashSet<String>();
        for (FieldDefinition field : fields) {
            if (!names.add(field.name())) throw new IllegalArgumentException("duplicate field " + field.name());
        }
    }

    public FieldDefinition field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field " + name + " in " + this.name));
    }
}
