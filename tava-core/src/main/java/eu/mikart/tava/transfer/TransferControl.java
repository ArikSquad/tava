package eu.mikart.tava.transfer;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Cooperative transfer cancellation and memory limits. Checks run between driver calls; configure driver timeouts
 * separately to bound an individual database call.
 *
 * @param timeout maximum elapsed time per copy invocation, or zero for no deadline
 * @param cancelled application cancellation signal
 * @param maxIssues maximum retained diagnostics, including a truncation notice
 * @param maxTrackedCursors maximum distinct cursors per entity before stopping safely
 * @since 1.3.0
 */
public record TransferControl(Duration timeout, BooleanSupplier cancelled, int maxIssues, int maxTrackedCursors) {
    /**
     * Creates transfer controls.
     *
     * @param timeout non-negative timeout, or zero for no deadline
     * @param cancelled cancellation signal
     * @param maxIssues maximum retained diagnostics, between two and 100,000
     * @param maxTrackedCursors maximum tracked cursors, at least one
     */
    public TransferControl {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancelled, "cancelled");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        timeout.toNanos();
        if (maxIssues < 2 || maxIssues > 100000 || maxTrackedCursors < 1)
            throw new IllegalArgumentException("Invalid transfer limits");
    }

    /**
     * Returns conservative transfer controls.
     *
     * @return controls with no deadline, interruption support, and bounded diagnostics and cursor tracking
     */
    public static TransferControl defaults() {
        return new TransferControl(Duration.ZERO, () -> false, 1000, 100000);
    }

    void check(long started) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new IllegalStateException("Transfer cancelled");
        if (!timeout.isZero() && System.nanoTime() - started >= timeout.toNanos())
            throw new IllegalStateException("Transfer deadline exceeded");
    }
}
