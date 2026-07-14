package eu.mikart.tava.data;

import eu.mikart.tava.query.Predicate;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** One or more named values that uniquely identify an entity. */
public record Identity(@NotNull Map<String, Object> values) {
    public Identity { values = Map.copyOf(new LinkedHashMap<>(values)); if (values.isEmpty()) throw new IllegalArgumentException("identity is empty"); }
    public static @NotNull Identity of(@NotNull String field, Object value) { return new Identity(Map.of(field, value)); }
    public static @NotNull Identity of(@NotNull Map<String, ?> values) { return new Identity(new LinkedHashMap<>(values)); }
    public @NotNull Predicate predicate() {
        return Predicate.and(values.entrySet().stream().map(e -> Predicate.eq(e.getKey(), e.getValue())).toArray(Predicate[]::new));
    }
}
