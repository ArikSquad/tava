package eu.mikart.tava;

/** Converts an application value to and from an adapter-friendly scalar or document value. */
public interface ValueCodec<T> {
    Object encode(T value);
    T decode(Object stored);
}
