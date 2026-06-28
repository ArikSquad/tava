package eu.mikart.tava.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record FieldType(
        @NotNull LogicalType logicalType,
        @Nullable Integer length,
        @Nullable Integer precision,
        @Nullable Integer scale
) {
    public FieldType {
        if (logicalType == null) throw new IllegalArgumentException("logicalType is required");
        if (length != null && length < 1) throw new IllegalArgumentException("length must be positive");
        if (precision != null && precision < 1) throw new IllegalArgumentException("precision must be positive");
        if (scale != null && (scale < 0 || precision == null || scale > precision)) {
            throw new IllegalArgumentException("scale requires precision and must not exceed it");
        }
    }

    public static @NotNull FieldType of(final @NotNull LogicalType type) {
        return new FieldType(type, null, null, null);
    }

    public static @NotNull FieldType string(final int length) {
        return new FieldType(LogicalType.STRING, length, null, null);
    }

    public static @NotNull FieldType decimal(final int precision, final int scale) {
        return new FieldType(LogicalType.DECIMAL, null, precision, scale);
    }
}
