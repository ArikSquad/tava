package eu.mikart.tava.data;

import eu.mikart.tava.query.Predicate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * One or more named values that uniquely identify an entity.
 *
 * @param values field names and identity values
 */
public record Identity(@NotNull Map<String, Object> values) {
    /**
     * Creates an identity from one or more field values.
     *
     * @param values field names and identity values
     * @throws IllegalArgumentException if {@code values} is empty
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public Identity {
        values = Map.copyOf(new LinkedHashMap<>(values));
        if (values.isEmpty()) throw new IllegalArgumentException("identity is empty");
    }

    /**
     * Creates an identity from one field and its value.
     *
     * @param field identity field name
     * @param value identity value
     * @return the identity for the supplied field and value
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    public static @NotNull Identity of(@NotNull String field, Object value) {
        return new Identity(Map.of(field, value));
    }

    /**
     * Creates an identity from a map of field values.
     *
     * @param values field names and identity values
     * @return the identity for the supplied values
     * @throws IllegalArgumentException if {@code values} is empty
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public static @NotNull Identity of(@NotNull Map<String, ?> values) {
        return new Identity(new LinkedHashMap<>(values));
    }

    /**
     * Creates a predicate that matches all identity field values.
     *
     * @return an equality predicate for this identity
     */
    public @NotNull Predicate predicate() {
        return Predicate.and(values.entrySet().stream()
                .map(e -> Predicate.eq(e.getKey(), e.getValue()))
                .toArray(Predicate[]::new));
    }
}
