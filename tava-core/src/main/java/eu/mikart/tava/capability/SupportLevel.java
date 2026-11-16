package eu.mikart.tava.capability;

/** Describes how an adapter provides a capability. */
public enum SupportLevel {
    /** The adapter supports the feature directly through the portable API. */
    SUPPORTED,
    /** The adapter provides the feature through emulation. */
    EMULATED,
    /** The feature is available only through the adapter's native API. */
    NATIVE_ONLY,
    /** The adapter does not provide the feature. */
    UNSUPPORTED
}
