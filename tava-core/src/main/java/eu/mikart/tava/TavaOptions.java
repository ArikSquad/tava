package eu.mikart.tava;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controls entity operations. Driver connection and socket timeouts remain driver settings.
 *
 * @param timeout operation deadline; zero disables it
 * @param maxAsyncOperations maximum submitted async operations; zero disables the limit; excess submissions fail
 * @param defaultPageSize page size for queries specifying zero
 * @param maxPageSize largest accepted page size
 * @param jdbcBatchSize largest driver batch, within the enclosing atomic insert
 * @param allowScans whether DynamoDB may fall back to scans
 * @param listener completion observer called on operation threads; must support concurrent calls; runtime exceptions
 *     are ignored
 * @since 1.3.0
 */
public record TavaOptions(
        Duration timeout,
        int maxAsyncOperations,
        int defaultPageSize,
        int maxPageSize,
        int jdbcBatchSize,
        boolean allowScans,
        Consumer<OperationEvent> listener) {
    private static final TavaOptions DEFAULTS =
            new TavaOptions(Duration.ZERO, 0, 500, Integer.MAX_VALUE - 1, 500, true, event -> {});

    /**
     * Validates operation settings.
     *
     * @param timeout operation deadline
     * @param maxAsyncOperations submission limit
     * @param defaultPageSize default page size
     * @param maxPageSize maximum page size
     * @param jdbcBatchSize maximum JDBC batch size
     * @param allowScans scan policy
     * @param listener completion observer
     * @since 1.3.0
     */
    public TavaOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(listener, "listener");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must be non-negative");
        timeout.toNanos();
        if (maxAsyncOperations < 0
                || defaultPageSize < 1
                || maxPageSize < defaultPageSize
                || maxPageSize == Integer.MAX_VALUE
                || jdbcBatchSize < 1) throw new IllegalArgumentException("Invalid operation limits");
    }
    /**
     * Returns default settings with bounded JDBC batches.
     *
     * @return backward-compatible defaults with bounded JDBC batches
     * @since 1.3.0
     */
    public static TavaOptions defaults() {
        return DEFAULTS;
    }
}
