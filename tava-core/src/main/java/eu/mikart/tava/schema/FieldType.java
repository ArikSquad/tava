package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

/**
 * Logical type and optional size constraints for a field.
 *
 * @param logicalType logical value type
 * @param length maximum length for a bounded string, or {@code null} when unspecified
 * @param precision maximum decimal digits, or {@code null} when unspecified
 * @param scale decimal digits after the decimal point, or {@code null} when unspecified
 */
public record FieldType(
        @NotNull LogicalType logicalType,
        @Nullable Integer length,
        @Nullable Integer precision,
        @Nullable Integer scale) {
    /**
     * Creates a field type and validates its optional size constraints.
     *
     * @param logicalType logical value type
     * @param length maximum length for a bounded string, or {@code null} when unspecified
     * @param precision maximum decimal digits, or {@code null} when unspecified
     * @param scale decimal digits after the decimal point, or {@code null} when unspecified
     * @throws IllegalArgumentException if a constraint is invalid or scale is specified without a valid precision
     */
    public FieldType {
        if (logicalType == null) throw new IllegalArgumentException("logicalType is required");
        if (length != null && length < 1) throw new IllegalArgumentException("length must be positive");
        if (precision != null && precision < 1) throw new IllegalArgumentException("precision must be positive");
        if (scale != null && (scale < 0 || precision == null || scale > precision)) {
            throw new IllegalArgumentException("scale requires precision and must not exceed it");
        }
    }

    /**
     * Creates a field type without size constraints.
     *
     * @param type logical value type
     * @return field type for the given logical type
     * @throws IllegalArgumentException if {@code type} is {@code null}
     */
    public static @NotNull FieldType of(final @NotNull LogicalType type) {
        return new FieldType(type, null, null, null);
    }

    /**
     * Creates a bounded string field type.
     *
     * @param length maximum string length
     * @return bounded string field type
     * @throws IllegalArgumentException if {@code length} is less than one
     */
    public static @NotNull FieldType string(final @Range(from = 1, to = Integer.MAX_VALUE) int length) {
        return new FieldType(LogicalType.STRING, length, null, null);
    }

    /**
     * Creates a decimal field type.
     *
     * @param precision maximum number of decimal digits
     * @param scale number of digits after the decimal point
     * @return decimal field type
     * @throws IllegalArgumentException if precision is less than one, scale is negative, or scale exceeds precision
     */
    public static @NotNull FieldType decimal(
            final @Range(from = 1, to = Integer.MAX_VALUE) int precision,
            final @Range(from = 0, to = Integer.MAX_VALUE) int scale) {
        return new FieldType(LogicalType.DECIMAL, null, precision, scale);
    }
}
