package eu.mikart.tava.transfer;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Transfer cancellation, deadlines, and memory limits. Active calls are interrupted and adapters apply driver deadlines
 * or statement cancellation when available. Configure driver connection and socket timeouts as well.
 *
 * @param timeout maximum elapsed time per copy invocation, or zero for no deadline
 * @param cancelled thread-safe application cancellation signal
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

    /**
     * Creates a builder with the default cancellation and memory controls.
     *
     * @return controls builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Configures named transfer controls. */
    public static final class Builder {
        private Builder() {}

        private Duration timeout = Duration.ZERO;
        private BooleanSupplier cancelled = () -> false;
        private int maxIssues = 1000;
        private int maxTrackedCursors = 100000;

        /**
         * Sets {@link TransferControl#timeout()}.
         *
         * @param value configured value
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * Sets {@link TransferControl#cancelled()}.
         *
         * @param value configured value
         * @return this builder
         */
        public Builder cancelled(BooleanSupplier value) {
            this.cancelled = value;
            return this;
        }

        /**
         * Sets {@link TransferControl#maxIssues()}.
         *
         * @param value configured value
         * @return this builder
         */
        public Builder maxIssues(int value) {
            this.maxIssues = value;
            return this;
        }

        /**
         * Sets {@link TransferControl#maxTrackedCursors()}.
         *
         * @param value configured value
         * @return this builder
         */
        public Builder maxTrackedCursors(int value) {
            this.maxTrackedCursors = value;
            return this;
        }

        /**
         * Creates validated transfer controls.
         *
         * @return immutable controls
         */
        public TransferControl build() {
            return new TransferControl(timeout, cancelled, maxIssues, maxTrackedCursors);
        }
    }

    void check(long started) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new IllegalStateException("Transfer cancelled");
        if (!timeout.isZero() && System.nanoTime() - started >= timeout.toNanos())
            throw new IllegalStateException("Transfer deadline exceeded");
    }
}
