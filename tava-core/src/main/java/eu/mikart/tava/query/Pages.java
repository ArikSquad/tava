package eu.mikart.tava.query;

import eu.mikart.tava.data.Page;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Lazy page traversal, including filtered pages containing no records.
 *
 * @since 1.3.0
 */
public final class Pages {
    private Pages() {}
    /**
     * Creates a sequential stream that fetches pages on demand. Each page is fully released before returning it. Stops
     * safely if the adapter repeats a cursor or produces over 100,000 continuation tokens.
     *
     * @param <T> item type
     * @param query initial query
     * @param fetch page reader
     * @return lazy stream of records
     * @since 1.3.0
     */
    public static <T> Stream<T> stream(Query query, Function<Query, Page<T>> fetch) {
        Iterator<T> iterator = new Iterator<>() {
            private Iterator<T> items = Collections.emptyIterator();
            private String cursor = query.cursor();
            private boolean done;
            private final Set<String> seen = new HashSet<>();

            {
                if (cursor != null) seen.add(cursor);
            }

            public boolean hasNext() {
                while (!items.hasNext() && !done) {
                    if (Thread.currentThread().isInterrupted())
                        throw new IllegalStateException("Traversal interrupted");
                    Page<T> page = fetch.apply(
                            new Query(query.predicate(), query.projection(), query.sorting(), query.limit(), cursor));
                    cursor = page.nextCursor();
                    if (cursor != null && (!seen.add(cursor) || seen.size() > 100000))
                        throw new IllegalStateException("Invalid or excessive continuation cursors");
                    done = cursor == null;
                    items = page.items().iterator();
                }
                return items.hasNext();
            }

            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return items.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }
}
