package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public record Mutation(@NotNull Map<String, Object> values, @NotNull Map<String, Operation> operations) {
    public enum Kind { INCREMENT, APPEND_IF_ABSENT, @Deprecated APPEND, REMOVE }
    public record Operation(@NotNull Kind kind, @Nullable Object value) {}
    public Mutation {
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        operations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(operations));
    }

    public Mutation(@NotNull Map<String, Object> values) { this(values, Map.of()); }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public @NotNull Builder set(final @NotNull String field, final @Nullable Object value) {
            values.put(field, value);
            return this;
        }

        public <T> @NotNull Builder set(final @NotNull FieldRef<T> field, final @Nullable T value) { return set(field.name(), value); }
        public @NotNull Builder increment(final @NotNull FieldRef<? extends Number> field, final @NotNull Number amount) { operations.put(field.name(), new Operation(Kind.INCREMENT, amount)); return this; }
        /** Atomically adds a value only when the collection does not already contain it. */
        public <T> @NotNull Builder appendIfAbsent(final @NotNull FieldRef<? extends java.util.Collection<T>> field, final @Nullable T value) { operations.put(field.name(), new Operation(Kind.APPEND_IF_ABSENT, value)); return this; }
        /** Alias for {@link #appendIfAbsent(FieldRef, Object)} retained for source compatibility. */
        public <T> @NotNull Builder append(final @NotNull FieldRef<? extends java.util.Collection<T>> field, final @Nullable T value) { return appendIfAbsent(field, value); }
        public <T> @NotNull Builder remove(final @NotNull FieldRef<? extends java.util.Collection<T>> field, final @Nullable T value) { operations.put(field.name(), new Operation(Kind.REMOVE, value)); return this; }

        private final Map<String, Operation> operations = new LinkedHashMap<>();

        public @NotNull Mutation build() {
            return new Mutation(values, operations);
        }
    }
}
