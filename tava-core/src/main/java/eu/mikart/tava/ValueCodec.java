package eu.mikart.tava;

/**
 * Converts an application value to and from an adapter-friendly scalar or document value.
 *
 * @param <T> application value type handled by this codec
 */
public interface ValueCodec<T> {
    /**
     * Encodes an application value for storage by an adapter.
     *
     * @param value application value
     * @return adapter-friendly scalar or document value
     */
    Object encode(T value);

    /**
     * Decodes a value returned by an adapter.
     *
     * @param stored adapter-friendly scalar or document value
     * @return decoded application value
     */
    T decode(Object stored);
}
