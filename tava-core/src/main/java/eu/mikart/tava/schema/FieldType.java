package eu.mikart.tava.schema;

public record FieldType(LogicalType logicalType, Integer length, Integer precision, Integer scale) {
    public FieldType {
        if (logicalType == null) throw new IllegalArgumentException("logicalType is required");
        if (length != null && length < 1) throw new IllegalArgumentException("length must be positive");
        if (precision != null && precision < 1) throw new IllegalArgumentException("precision must be positive");
        if (scale != null && (scale < 0 || precision == null || scale > precision)) {
            throw new IllegalArgumentException("scale requires precision and must not exceed it");
        }
    }

    public static FieldType of(LogicalType type) {
        return new FieldType(type, null, null, null);
    }

    public static FieldType string(int length) {
        return new FieldType(LogicalType.STRING, length, null, null);
    }

    public static FieldType decimal(int precision, int scale) {
        return new FieldType(LogicalType.DECIMAL, null, precision, scale);
    }
}
