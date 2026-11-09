package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.capability.SupportLevel;
import eu.mikart.tava.schema.FieldDefinition;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;

/** Basic JDBC dialect profile configured by adapter modules. */
public class StandardJdbcProfile implements JdbcProfile {
    private final String name;
    private final char quote;
    private final boolean ifNotExists;

    /**
     * Creates a profile with one identifier quote character and optional conditional DDL.
     *
     * @param name stable adapter name
     * @param quote identifier quote character
     * @param ifNotExists whether table creation supports {@code IF NOT EXISTS}
     */
    public StandardJdbcProfile(final @NotNull String name, final char quote, final boolean ifNotExists) {
        this.name = name;
        this.quote = quote;
        this.ifNotExists = ifNotExists;
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull String quote(@Pattern("[A-Za-z_][A-Za-z0-9_]*") final @NotNull String identifier) {
        if (!validIdentifier(identifier)) throw new IllegalArgumentException("Invalid identifier: " + identifier);
        return quote + identifier + quote;
    }

    /**
     * Checks whether an identifier uses the portable unquoted identifier syntax.
     *
     * @param identifier identifier to validate
     * @return {@code true} when the identifier is non-empty and contains only letters, digits, and underscores
     */
    protected static boolean validIdentifier(final String identifier) {
        if (identifier == null || identifier.isEmpty()) return false;
        final char first = identifier.charAt(0);
        if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_')) return false;
        for (int i = 1; i < identifier.length(); i++) {
            final char value = identifier.charAt(i);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '_')) return false;
        }
        return true;
    }

    /** @return SQL type corresponding to the field's logical type */
    @Override
    public @NotNull String type(final @NotNull FieldDefinition field) {
        return switch (field.type().logicalType()) {
            case STRING ->
                "VARCHAR(" + (field.type().length() == null ? 255 : field.type().length()) + ")";
            case TEXT, JSON -> "TEXT";
            case INT32 -> "INTEGER";
            case INT64 -> "BIGINT";
            case FLOAT64 -> "DOUBLE PRECISION";
            case DECIMAL ->
                "DECIMAL(" + field.type().precision() + "," + field.type().scale() + ")";
            case BOOLEAN -> "BOOLEAN";
            case UUID -> "VARCHAR(36)";
            case INSTANT -> "TIMESTAMP";
            case LOCAL_DATE -> "DATE";
            case LOCAL_DATE_TIME -> "TIMESTAMP";
            case BINARY -> "BLOB";
        };
    }

    @Override
    public @NotNull String identityClause(final @NotNull FieldDefinition field) {
        return "";
    }

    @Override
    public boolean supportsIfNotExists() {
        return ifNotExists;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.builder(name)
                .supported(
                        Feature.SCHEMA_ENFORCEMENT,
                        Feature.UNIQUE_CONSTRAINTS,
                        Feature.SECONDARY_INDEXES,
                        Feature.TRANSACTIONS,
                        Feature.CONDITIONAL_WRITES,
                        Feature.SORTING,
                        Feature.PAGINATION,
                        Feature.PROJECTION,
                        Feature.GENERATED_VALUES,
                        Feature.JSON,
                        Feature.BINARY,
                        Feature.BULK_READ,
                        Feature.BULK_WRITE)
                .set(
                        Feature.FOREIGN_KEYS,
                        SupportLevel.NATIVE_ONLY,
                        "Declare foreign keys through native schema operations")
                .set(Feature.JOINS, SupportLevel.NATIVE_ONLY, "Use native SQL joins")
                .set(Feature.AGGREGATION, SupportLevel.NATIVE_ONLY, "Use native SQL aggregation")
                .set(Feature.TTL, SupportLevel.NATIVE_ONLY, "Use native database facilities")
                .set(Feature.FULL_TEXT, SupportLevel.NATIVE_ONLY, "Use native database facilities")
                .build();
    }
}
