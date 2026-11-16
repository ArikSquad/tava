package eu.mikart.tava.spi;

import java.util.Objects;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Exposes an adapter's underlying driver handles to adapter-specific code. */
public interface NativeAccess {
    /**
     * Returns a native driver handle of the requested type.
     *
     * @param <T> native handle type
     * @param type class token for the requested handle
     * @return native handle
     * @deprecated Use {@link #withNative(Class, Function)} so the adapter can scope handle use to a callback.
     */
    @Deprecated(since = "1.3.0", forRemoval = false)
    <T> @NotNull T nativeHandle(@NotNull Class<T> type);

    /**
     * Runs a callback with the requested native handle.
     *
     * <p>Implementations may scope the handle's lifetime to the callback. Do not retain a scoped handle after the
     * callback returns.
     *
     * @param <T> native handle type
     * @param <R> callback result type
     * @param type class token for the requested handle
     * @param callback action to run with the handle
     * @return result returned by the callback, possibly {@code null}
     */
    @SuppressWarnings("deprecation")
    default <T, R> @Nullable R withNative(final @NotNull Class<T> type, final @NotNull Function<T, R> callback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
        return callback.apply(nativeHandle(type));
    }
}
