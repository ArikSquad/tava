package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;

/** A reusable, typed field name. Generated model companions can expose these as constants. */
public record FieldRef<T>(@NotNull String name, @NotNull Class<T> type) {
    public FieldRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required");
    }

    public static <T> @NotNull FieldRef<T> of(@NotNull String name, @NotNull Class<T> type) {
        return new FieldRef<>(name, type);
    }
}
