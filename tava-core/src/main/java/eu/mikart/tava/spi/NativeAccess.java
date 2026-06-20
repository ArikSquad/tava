package eu.mikart.tava.spi;

import java.util.function.Function;

public interface NativeAccess {
    <T> T nativeHandle(Class<T> type);

    default <T, R> R withNative(Class<T> type, Function<T, R> callback) {
        return callback.apply(nativeHandle(type));
    }
}
