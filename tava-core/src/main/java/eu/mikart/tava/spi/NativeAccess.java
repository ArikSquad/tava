package eu.mikart.tava.spi;

import eu.mikart.tava.TavaException;
import java.util.Objects;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Exposes an adapter's underlying driver handles to adapter-specific code. */
public interface NativeAccess {
    /**
     * Callback that may fail with a checked driver exception.
     *
     * @param <T> native handle type
     * @param <R> callback result type
     * @since 1.3.2
     */
    @FunctionalInterface
    interface CheckedCallback<T, R> {
        /**
         * Runs against the scoped handle.
         *
         * @param handle native handle
         * @return callback result
         * @throws Exception if the operation fails with a checked exception
         */
        R apply(T handle) throws Exception;
    }

    /**
     * Runs a callback that can throw checked exceptions using the same handle scope as {@link #withNative}.
     *
     * @param <T> native handle type
     * @param <R> callback result type
     * @param type class token for the requested handle
     * @param callback action to run with the handle
     * @return result returned by the callback, possibly {@code null}
     * @throws TavaException.Data if the callback throws a checked exception
     * @since 1.3.2
     */
    default <T, R> @Nullable R withNativeChecked(Class<T> type, CheckedCallback<T, R> callback) {
        Objects.requireNonNull(callback, "callback");
        return withNative(type, handle -> {
            try {
                return callback.apply(handle);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new TavaException.Data("Use native handle", failure);
            }
        });
    }

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
