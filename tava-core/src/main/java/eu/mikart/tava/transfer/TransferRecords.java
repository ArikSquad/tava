package eu.mikart.tava.transfer;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.FieldDefinition;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class TransferRecords {
    private TransferRecords() {}

    static EntityRecord conform(
            String sourceAdapter,
            EntityDefinition entity,
            EntityRecord record,
            boolean allowLossy,
            List<TransferIssue> issues) {
        Set<String> fields = entity.fields().stream().map(field -> field.name()).collect(Collectors.toSet());
        Map<String, Object> values = new LinkedHashMap<>();
        record.values().forEach((field, value) -> {
            if (fields.contains(field)) {
                values.put(field, canonical(entity.field(field), value));
            } else if (!(sourceAdapter.equals("mongodb") && field.equals("_id"))) {
                String detail = "Source field is absent from target schema: " + entity.name() + "." + field;
                if (!allowLossy) throw new TavaException.Schema(detail);
                issues.add(new TransferIssue(entity.name(), field, TransferIssue.Severity.LOSSY, detail));
            }
        });
        return EntityRecord.of(values);
    }

    static Object canonical(FieldDefinition field, Object value) {
        if (value == null) return null;
        try {
            return switch (field.type().logicalType()) {
                case UUID -> value instanceof UUID ? value : UUID.fromString(value.toString());
                case INSTANT -> {
                    if (value instanceof OffsetDateTime date) yield date.toInstant();
                    if (value instanceof Date date) yield date.toInstant();
                    if (value instanceof Instant instant) yield instant;
                    yield Instant.parse(value.toString());
                }
                case LOCAL_DATE -> {
                    if (value instanceof java.sql.Date date) yield date.toLocalDate();
                    if (value instanceof LocalDate date) yield date;
                    yield LocalDate.parse(value.toString());
                }
                case LOCAL_DATE_TIME -> {
                    if (value instanceof Timestamp date) yield date.toLocalDateTime();
                    if (value instanceof LocalDateTime date) yield date;
                    yield LocalDateTime.parse(value.toString());
                }
                case INT32 ->
                    value instanceof Number number ? new BigDecimal(number.toString()).intValueExact() : value;
                case INT64 ->
                    value instanceof Number number ? new BigDecimal(number.toString()).longValueExact() : value;
                case FLOAT64 -> value instanceof Number number ? number.doubleValue() : value;
                case DECIMAL -> value instanceof Number number ? new BigDecimal(number.toString()) : value;
                case BOOLEAN -> {
                    if (value instanceof Number number) {
                        int numberValue = new BigDecimal(number.toString()).intValueExact();
                        if (numberValue != 0 && numberValue != 1)
                            throw new IllegalArgumentException("Invalid boolean number");
                        yield numberValue == 1;
                    }
                    yield value;
                }
                case JSON -> value instanceof String text ? ValueCodecs.fromJson(text) : value;
                default -> value;
            };
        } catch (RuntimeException failure) {
            throw new TavaException.Mapping(
                    "Cannot transfer field " + field.name() + " as "
                            + field.type().logicalType(),
                    failure);
        }
    }
}
