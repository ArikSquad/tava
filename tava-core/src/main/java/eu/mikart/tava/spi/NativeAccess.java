package eu.mikart.tava.spi;

import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

public interface NativeAccess {
    <T> @NotNull T nativeHandle(@NotNull Class<T> type);

    default <T, R> @NotNull R withNative(final @NotNull Class<T> type, final @NotNull Function<T, R> callback) {
        return callback.apply(nativeHandle(type));
    }
}
