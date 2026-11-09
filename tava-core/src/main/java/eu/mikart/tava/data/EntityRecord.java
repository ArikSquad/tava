package eu.mikart.tava.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable named field values for one entity record.
 *
 * @param values field names and their values
 */
public record EntityRecord(@NotNull Map<String, Object> values) {
    /**
     * Creates an immutable entity record from the supplied field values.
     *
     * @param values field names and their values
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public EntityRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Returns a field value without imposing a type.
     *
     * @param field field name
     * @return field value, or {@code null} when the field is absent or its value is {@code null}
     */
    public @Nullable Object get(final @NotNull String field) {
        return values.get(field);
    }

    /**
     * Returns a field value cast to the requested type.
     *
     * @param <T> requested value type
     * @param field field name
     * @param type class of the requested value type
     * @return field value, or {@code null} when the field is absent or its value is {@code null}
     * @throws ClassCastException if the field value is not an instance of {@code type}
     */
    public <T> @Nullable T get(final @NotNull String field, final @NotNull Class<T> type) {
        return type.cast(values.get(field));
    }

    /**
     * Starts building an entity record.
     *
     * @return a builder for an entity record
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Creates an entity record from a map of field values.
     *
     * @param values field names and their values
     * @return immutable entity record
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public static @NotNull EntityRecord of(final @NotNull Map<String, Object> values) {
        return new EntityRecord(values);
    }

    /** Builds one immutable entity record. */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Creates an empty entity record builder. */
        public Builder() {}

        /**
         * Sets a field value. A {@code null} value is allowed.
         *
         * @param field field name
         * @param value field value
         * @return this builder
         */
        public @NotNull Builder set(final @NotNull String field, final @Nullable Object value) {
            values.put(field, value);
            return this;
        }

        /**
         * Builds the immutable record from the values supplied so far.
         *
         * @return built entity record
         */
        public @NotNull EntityRecord build() {
            return new EntityRecord(values);
        }
    }
}
