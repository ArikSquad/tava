package eu.mikart.tava.schema;

import eu.mikart.tava.schema.annotation.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.RecordComponent;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class RecordSchemas {

    public static @NotNull EntityDefinition describe(final @NotNull Class<? extends Record> type) {
        if (!type.isRecord()) throw new IllegalArgumentException(type.getName() + " is not a record");
        final Entity entity = type.getAnnotation(Entity.class);
        final String name = entity != null && !entity.value().isBlank()
            ? entity.value() : decapitalize(type.getSimpleName());
        final List<FieldDefinition> fields = new ArrayList<>();
        for (final RecordComponent component : type.getRecordComponents()) fields.add(describe(component));
        final Map<String, Object> entitySettings = new LinkedHashMap<>(
            settings(type.getAnnotationsByType(AdapterSetting.class)));
        for (final FieldDefinition field : fields) {
            if ("true".equals(field.settings().get("dynamodb.partitionKey"))) {
                if (entitySettings.put("dynamodb.partitionKey", field.name()) != null)
                    throw new IllegalArgumentException("Only one DynamoDB partition key is supported");
            }
        }
        return new EntityDefinition(name, fields, List.of(), entitySettings);
    }

    private static @NotNull FieldDefinition describe(final @NotNull RecordComponent component) {
        final Field field = component.getAnnotation(Field.class);
        final String name = field != null && !field.value().isBlank() ? field.value() : component.getName();
        final boolean identity = component.isAnnotationPresent(Identity.class);
        final boolean nullable = !component.getType().isPrimitive()
            && !identity && !component.isAnnotationPresent(Required.class);
        final Generated generated = component.getAnnotation(Generated.class);
        return new FieldDefinition(name, typeOf(component.getType(), field), nullable, identity,
            component.isAnnotationPresent(Unique.class),
            generated == null ? GeneratedValue.NONE : generated.value(),
            settings(component));
    }

    private static @NotNull FieldType typeOf(final @NotNull Class<?> type, final @Nullable Field field) {
        if (type == String.class) return field != null && field.length() > 0
            ? FieldType.string(field.length()) : FieldType.of(LogicalType.TEXT);
        if (type == int.class || type == Integer.class || type == short.class || type == Short.class)
            return FieldType.of(LogicalType.INT32);
        if (type == long.class || type == Long.class) return FieldType.of(LogicalType.INT64);
        if (type == boolean.class || type == Boolean.class) return FieldType.of(LogicalType.BOOLEAN);
        if (type == UUID.class) return FieldType.of(LogicalType.UUID);
        if (type == Instant.class || type == OffsetDateTime.class) return FieldType.of(LogicalType.INSTANT);
        if (type == LocalDate.class) return FieldType.of(LogicalType.LOCAL_DATE);
        if (type == LocalDateTime.class) return FieldType.of(LogicalType.LOCAL_DATE_TIME);
        if (type == byte[].class) return FieldType.of(LogicalType.BINARY);
        if (type == BigDecimal.class) {
            if (field == null || field.precision() < 1) {
                throw new IllegalArgumentException("BigDecimal field requires @Field(precision=..., scale=...)");
            }
            return FieldType.decimal(field.precision(), Math.max(field.scale(), 0));
        }
        if (Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type))
            return FieldType.of(LogicalType.JSON);
        throw new IllegalArgumentException("Unsupported record field type " + type.getTypeName());
    }

    private static @NotNull Map<String, Object> settings(final @NotNull AdapterSetting[] annotations) {
        final Map<String, Object> settings = new LinkedHashMap<>();
        for (final AdapterSetting setting : annotations) {
            settings.put(setting.adapter() + "." + setting.key(), setting.value());
        }
        return settings;
    }

    private static @NotNull Map<String, Object> settings(final @NotNull RecordComponent component) {
        final Map<String, Object> settings = new LinkedHashMap<>(
            settings(component.getAnnotationsByType(AdapterSetting.class)));
        for (final Annotation annotation : component.getAnnotations()) {
            final AdapterOverride override = annotation.annotationType().getAnnotation(AdapterOverride.class);
            if (override == null) continue;
            try {
                // Adapter-specific annotations keep the public model clean while still carrying vendor hints.
                final Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                settings.put(override.adapter() + "." + override.key(), value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Adapter override annotation must expose value()", failure);
            }
        }
        return settings;
    }

    private static @NotNull String decapitalize(final @NotNull String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
