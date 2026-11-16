package eu.mikart.tava.data;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One page of query results and an optional cursor for the next page.
 *
 * @param <T> item type
 * @param items items in this page
 * @param nextCursor cursor for the next page, or {@code null} when there are no more pages
 */
public record Page<T>(@NotNull List<T> items, @Nullable String nextCursor) {
    /**
     * Creates a page with an immutable copy of its items.
     *
     * @param items items in this page
     * @param nextCursor cursor for the next page, or {@code null} when there are no more pages
     * @throws NullPointerException if {@code items} or any item in the list is {@code null}
     */
    public Page {
        items = List.copyOf(items);
    }
}
