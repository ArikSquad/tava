package eu.mikart.tava.schema;

/** Portable logical data types, independent of their adapter-specific storage representation. */
public enum LogicalType {
    /** Character string with an optional maximum length. */
    STRING,
    /** Unbounded text value. */
    TEXT,
    /** Signed 32-bit integer. */
    INT32,
    /** Signed 64-bit integer. */
    INT64,
    /** 64-bit floating-point number. */
    FLOAT64,
    /** Decimal number with optional precision and scale. */
    DECIMAL,
    /** Boolean value. */
    BOOLEAN,
    /** Universally unique identifier. */
    UUID,
    /** Moment on the timeline, stored with an instant-based representation. */
    INSTANT,
    /** Calendar date without a time or time zone. */
    LOCAL_DATE,
    /** Local date and time without a time zone. */
    LOCAL_DATE_TIME,
    /** Binary data. */
    BINARY,
    /** JSON-compatible structured value. */
    JSON
}
