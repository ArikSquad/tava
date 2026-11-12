package eu.mikart.tava.query;

import eu.mikart.tava.data.Page;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
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
            private final Iterator<Page<T>> pages = cursor(query, fetch);
            private Iterator<T> items = Collections.emptyIterator();

            public boolean hasNext() {
                while (!items.hasNext() && pages.hasNext())
                    items = pages.next().items().iterator();
                return items.hasNext();
            }

            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return items.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }
    /**
     * Binds a query to its page reader. The returned traversal is lazy and single-use; it includes empty pages and
     * retains all query options while following cursors.
     *
     * @param <T> item type
     * @param query initial query, including any starting cursor
     * @param fetch page reader
     * @return page iterator
     */
    public static <T> Cursor<T> cursor(Query query, Function<Query, Page<T>> fetch) {
        return new Cursor<>(query, fetch);
    }

    /**
     * A single-use page traversal bound to a query and page reader. Traversal rejects repeated cursors and more than
     * 100,000 continuation tokens. Not thread-safe.
     *
     * @param <T> item type
     */
    public static final class Cursor<T> implements Iterator<Page<T>>, Iterable<Page<T>> {
        private final Query query;
        private final Function<Query, Page<T>> fetch;
        private final Set<String> seen = new HashSet<>();
        private String continuation;
        private boolean done;

        private Cursor(Query query, Function<Query, Page<T>> fetch) {
            this.query = Objects.requireNonNull(query, "query");
            this.fetch = Objects.requireNonNull(fetch, "fetch");
            continuation = query.cursor();
            if (continuation != null) seen.add(continuation);
        }

        /** @return whether another page can be fetched */
        @Override
        public boolean hasNext() {
            return !done;
        }

        /**
         * Fetches the next page using the original query options.
         *
         * @return next page, possibly empty
         * @throws NoSuchElementException if traversal is complete
         * @throws IllegalStateException if interrupted or continuation tokens repeat or exceed the limit
         */
        @Override
        public Page<T> next() {
            if (done) throw new NoSuchElementException();
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Traversal interrupted");
            Page<T> page = Objects.requireNonNull(fetch.apply(query.withCursor(continuation)), "page");
            String next = page.nextCursor();
            if (next != null && (!seen.add(next) || seen.size() > 100000)) {
                done = true;
                throw new IllegalStateException("Invalid or excessive continuation cursors");
            }
            continuation = next;
            done = next == null;
            return page;
        }

        /** @return this single-use iterator */
        @Override
        public Iterator<Page<T>> iterator() {
            return this;
        }
    }
}
