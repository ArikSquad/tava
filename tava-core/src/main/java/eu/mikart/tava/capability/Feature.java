package eu.mikart.tava.capability;

/** Features that an adapter can report through its capabilities. */
public enum Feature {
    /** Enforces the declared entity and field schemas. */
    SCHEMA_ENFORCEMENT,
    /** Enforces unique constraints. */
    UNIQUE_CONSTRAINTS,
    /** Enforces foreign-key relationships. */
    FOREIGN_KEYS,
    /** Supports indexes other than the primary identity. */
    SECONDARY_INDEXES,
    /** Supports atomic transactions. */
    TRANSACTIONS,
    /** Supports writes conditional on record state. */
    CONDITIONAL_WRITES,
    /** Sorts query results. */
    SORTING,
    /** Reads results in pages. */
    PAGINATION,
    /** Returns selected fields from matching records. */
    PROJECTION,
    /** Joins related entities in a query. */
    JOINS,
    /** Computes aggregates over matching records. */
    AGGREGATION,
    /** Expires records after a configured duration. */
    TTL,
    /** Searches text using full-text matching. */
    FULL_TEXT,
    /** Generates field values when records are written. */
    GENERATED_VALUES,
    /** Stores and queries JSON values. */
    JSON,
    /** Stores and queries binary values. */
    BINARY,
    /** Reads multiple records in one operation. */
    BULK_READ,
    /** Writes multiple records in one operation. */
    BULK_WRITE
}
