package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;

/**
 * A reusable, typed field name. Generated model companions can expose these as constants.
 *
 * @param <T> Java type expected for values of the field
 * @param name field name
 * @param type class of the field's Java type
 */
public record FieldRef<T>(@NotNull String name, @NotNull Class<T> type) {
    /**
     * Creates a typed field reference.
     *
     * @param name field name
     * @param type class of the field's Java type
     * @throws IllegalArgumentException if {@code name} is blank or {@code type} is {@code null}
     */
    public FieldRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required");
    }

    /**
     * Creates a typed field reference.
     *
     * @param <T> Java type expected for values of the field
     * @param name field name
     * @param type class of the field's Java type
     * @return a reference to the named field
     * @throws IllegalArgumentException if {@code name} is blank or {@code type} is {@code null}
     */
    public static <T> @NotNull FieldRef<T> of(@NotNull String name, @NotNull Class<T> type) {
        return new FieldRef<>(name, type);
    }
}
