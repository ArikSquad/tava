package eu.mikart.tava.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record Page<T>(@NotNull List<T> items, @Nullable String nextCursor) {
    public Page {
        items = List.copyOf(items);
    }
}
