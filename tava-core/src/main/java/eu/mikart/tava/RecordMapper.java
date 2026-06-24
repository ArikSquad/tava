package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.annotation.Generated;
import eu.mikart.tava.schema.annotation.Field;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class RecordMapper<T extends Record> {
    private final Class<T> type;
    private final Constructor<T> constructor;
    private final RecordComponent[] components;

    RecordMapper(Class<T> type) {
        this.type = type;
        RecordSchemas.describe(type);
        this.components = type.getRecordComponents();
        try {
            Constructor<T> found = type.getDeclaredConstructor(
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            found.setAccessible(true);
            this.constructor = found;
        } catch (ReflectiveOperationException e) {
            throw new TavaException.Schema("Cannot access canonical constructor for " + type.getName(), e);
        }
    }

    EntityRecord write(T value) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (RecordComponent component : components) {
                Field field = component.getAnnotation(Field.class);
                String name = field != null && !field.value().isBlank() ? field.value() : component.getName();
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object fieldValue = accessor.invoke(value);
                Generated generated = component.getAnnotation(Generated.class);
                if (fieldValue == null && generated != null) {
                    if (generated.value() == GeneratedValue.UUID) fieldValue = UUID.randomUUID();
                    else continue;
                }
                values.put(name, fieldValue);
            }
            return EntityRecord.of(values);
        } catch (ReflectiveOperationException e) {
            throw new TavaException.Data("Cannot read record " + type.getName(), e);
        }
    }

    T read(EntityRecord value) {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Field field = components[i].getAnnotation(Field.class);
            String name = field != null && !field.value().isBlank() ? field.value() : components[i].getName();
            args[i] = convert(value.get(name), components[i].getType());
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new TavaException.Data("Cannot create record " + type.getName(), e);
        }
    }

    private Object convert(Object raw, Class<?> target) {
        return switch (raw) {
            case null -> null;
            case Object o when target.isInstance(o) -> o;
            case Object o when target == String.class -> o.toString();
            case Object o when target == UUID.class -> UUID.fromString(o.toString());
            case Timestamp timestamp when target == Instant.class -> timestamp.toInstant();
            case Object o when target == Instant.class -> Instant.parse(o.toString());
            case Number n when target == int.class || target == Integer.class -> n.intValue();
            case Number n when target == long.class || target == Long.class -> n.longValue();
            case Number n when target == boolean.class || target == Boolean.class -> n.intValue() != 0;
            case Number n when target == byte.class || target == Byte.class -> n.byteValue();
            default -> raw;
        };
    }
}
