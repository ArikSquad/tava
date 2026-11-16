package eu.mikart.tava.spi;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.TavaOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Scoped deadlines and driver cancellation shared by adapters and transfers.
 *
 * @since 1.3.0
 */
public final class OperationContext implements AutoCloseable {
    private static final ThreadLocal<OperationContext> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tava-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final OperationContext parent = CURRENT.get();
    private final Thread owner = Thread.currentThread();
    private final long started = System.nanoTime();
    private final TavaOptions options;
    private final Duration timeout;
    private final BooleanSupplier signal;
    private final ArrayList<Runnable> cancellations = new ArrayList<>();
    private final Map<String, Double> metrics = new LinkedHashMap<>();
    private final ScheduledFuture<?> timer;
    private volatile boolean cancelled;
    private boolean interruptedBeforeCancel;
    private boolean closed;

    private OperationContext(TavaOptions options, Duration timeout, BooleanSupplier signal) {
        this.options = options;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.signal = signal;
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must be non-negative");
        timeout.toNanos();
        CURRENT.set(this);
        timer = timeout.isZero() && signal == null
                ? null
                : TIMER.scheduleAtFixedRate(
                        () -> {
                            try {
                                if (owner.isInterrupted()
                                        || (signal != null && signal.getAsBoolean())
                                        || (!timeout.isZero() && System.nanoTime() - started >= timeout.toNanos()))
                                    cancel();
                            } catch (RuntimeException failure) {
                                cancel();
                            }
                        },
                        1,
                        10,
                        TimeUnit.MILLISECONDS);
    }
    /**
     * Opens a context using facade settings.
     *
     * @param options operation settings
     * @return context to close on the same thread
     * @since 1.3.0
     */
    public static OperationContext open(TavaOptions options) {
        return new OperationContext(options, options.timeout(), null);
    }
    /**
     * Opens a cancellation scope, inheriting facade settings when nested.
     *
     * @param timeout deadline, or zero
     * @param cancelled external cancellation signal
     * @return context to close on the same thread
     * @since 1.3.0
     */
    public static OperationContext open(Duration timeout, BooleanSupplier cancelled) {
        return new OperationContext(options(), timeout, Objects.requireNonNull(cancelled, "cancelled"));
    }
    /**
     * Returns settings for the active operation.
     *
     * @return current settings, or defaults outside a facade operation
     * @since 1.3.0
     */
    public static TavaOptions options() {
        return CURRENT.get() == null ? TavaOptions.defaults() : CURRENT.get().options;
    }
    /**
     * Returns the remaining time across all active deadlines.
     *
     * @return remaining deadline in nanoseconds, or zero when unlimited
     * @since 1.3.0
     */
    public static long remainingNanos() {
        long remaining = Long.MAX_VALUE;
        for (OperationContext c = CURRENT.get(); c != null; c = c.parent) {
            c.check();
            if (!c.timeout.isZero())
                remaining = Math.min(remaining, Math.max(1, c.timeout.toNanos() - (System.nanoTime() - c.started)));
        }
        return remaining == Long.MAX_VALUE ? 0 : remaining;
    }
    /**
     * Registers cancellation of a live driver operation. Callbacks must tolerate concurrent completion.
     *
     * @param callback cancellation action
     * @return registration to close before releasing driver resources
     * @since 1.3.0
     */
    public static AutoCloseable onCancel(Runnable callback) {
        ArrayList<OperationContext> scopes = new ArrayList<>();
        for (OperationContext c = CURRENT.get(); c != null; c = c.parent) {
            synchronized (c) {
                c.check();
                c.cancellations.add(callback);
                scopes.add(c);
            }
        }
        return () -> {
            for (OperationContext c : scopes)
                synchronized (c) {
                    c.cancellations.remove(callback);
                }
        };
    }

    private synchronized void cancel() {
        if (closed || cancelled) return;
        interruptedBeforeCancel = owner.isInterrupted();
        cancelled = true;
        owner.interrupt();
        for (Runnable callback : cancellations)
            Thread.startVirtualThread(() -> {
                synchronized (OperationContext.this) {
                    if (!closed && cancellations.contains(callback)) {
                        try {
                            callback.run();
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            });
    }
    /**
     * Throws when this scope, an outer scope, or the calling thread is cancelled.
     *
     * @since 1.3.0
     */
    public void check() {
        if (cancelled
                || (signal != null && signal.getAsBoolean())
                || Thread.currentThread().isInterrupted()
                || (!timeout.isZero() && System.nanoTime() - started >= timeout.toNanos()))
            throw new TavaException.Data("Operation cancelled or deadline exceeded", null);
        if (parent != null) parent.check();
    }
    /**
     * Adds numeric driver diagnostics to the current completion event.
     *
     * @param name metric name
     * @param value metric value
     * @since 1.3.0
     */
    public static void metric(String name, double value) {
        if (CURRENT.get() != null) CURRENT.get().metrics.merge(name, value, Double::sum);
    }
    /**
     * Returns collected driver diagnostics.
     *
     * @return immutable metrics collected in this scope
     * @since 1.3.0
     */
    public Map<String, Double> metrics() {
        return Map.copyOf(metrics);
    }
    /**
     * Restores the previous context and stops watching this operation.
     *
     * @since 1.3.0
     */
    @Override
    public synchronized void close() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Operation scope must close on its owning thread");
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Operation scopes must close in reverse order");
        closed = true;
        if (timer != null) timer.cancel(false);
        cancellations.clear();
        CURRENT.set(parent);
        if (cancelled && !interruptedBeforeCancel) Thread.interrupted();
    }
}
