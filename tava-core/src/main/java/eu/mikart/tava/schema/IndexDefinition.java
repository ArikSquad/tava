package eu.mikart.tava.schema;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Schema metadata for an index on one or more entity fields.
 *
 * @param name index name
 * @param fields names of the indexed fields
 * @param unique whether the index enforces uniqueness
 * @param settings adapter-specific index settings
 */
public record IndexDefinition(
        @NotNull String name,
        @NotNull List<String> fields,
        boolean unique,
        @NotNull Map<String, Object> settings) {
    /**
     * Creates index metadata and validates its name and field list.
     *
     * @param name index name
     * @param fields names of the indexed fields
     * @param unique whether the index enforces uniqueness
     * @param settings adapter-specific index settings; {@code null} is treated as empty
     * @throws IllegalArgumentException if the name is blank or no fields are supplied
     * @throws NullPointerException if fields contains {@code null} or settings contains a null key or value
     */
    public IndexDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("index name is required");
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("index requires fields");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
