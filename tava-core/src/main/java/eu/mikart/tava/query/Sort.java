package eu.mikart.tava.query;

import org.jetbrains.annotations.NotNull;

public record Sort(@NotNull String field, @NotNull Direction direction) {
    public enum Direction {ASC, DESC}

    public static @NotNull Sort asc(final @NotNull String field) {
        return new Sort(field, Direction.ASC);
    }

    public static @NotNull Sort desc(final @NotNull String field) {
        return new Sort(field, Direction.DESC);
    }
}
