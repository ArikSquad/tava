package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.capability.SupportLevel;
import eu.mikart.tava.schema.FieldDefinition;

public class StandardJdbcProfile implements JdbcProfile {
    private final String name;
    private final char quote;
    private final boolean ifNotExists;

    public StandardJdbcProfile(String name, char quote, boolean ifNotExists) {
        this.name = name;
        this.quote = quote;
        this.ifNotExists = ifNotExists;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String quote(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid identifier: " + identifier);
        return quote + identifier + quote;
    }

    @Override
    public String type(FieldDefinition field) {
        return switch (field.type().logicalType()) {
            case STRING -> "VARCHAR(" + (field.type().length() == null ? 255 : field.type().length()) + ")";
            case TEXT, JSON -> "TEXT";
            case INT32 -> "INTEGER";
            case INT64 -> "BIGINT";
            case DECIMAL -> "DECIMAL(" + field.type().precision() + "," + field.type().scale() + ")";
            case BOOLEAN -> "BOOLEAN";
            case UUID -> "VARCHAR(36)";
            case INSTANT -> "TIMESTAMP";
            case LOCAL_DATE -> "DATE";
            case LOCAL_DATE_TIME -> "TIMESTAMP";
            case BINARY -> "BLOB";
        };
    }

    @Override
    public String identityClause(FieldDefinition field) {
        return "";
    }

    @Override
    public boolean supportsIfNotExists() {
        return ifNotExists;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder(name)
                .supported(Feature.SCHEMA_ENFORCEMENT, Feature.UNIQUE_CONSTRAINTS, Feature.FOREIGN_KEYS,
                        Feature.SECONDARY_INDEXES, Feature.TRANSACTIONS, Feature.CONDITIONAL_WRITES,
                        Feature.SORTING, Feature.PAGINATION, Feature.PROJECTION, Feature.JOINS,
                        Feature.AGGREGATION, Feature.GENERATED_VALUES, Feature.JSON, Feature.BINARY,
                        Feature.BULK_READ, Feature.BULK_WRITE)
                .set(Feature.TTL, SupportLevel.NATIVE_ONLY, "Use native database facilities")
                .set(Feature.FULL_TEXT, SupportLevel.NATIVE_ONLY, "Use native database facilities")
                .build();
    }
}
