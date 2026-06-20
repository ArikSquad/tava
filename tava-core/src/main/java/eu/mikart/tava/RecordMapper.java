package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.annotation.Generated;
import eu.mikart.tava.schema.annotation.Field;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
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
            @SuppressWarnings("unchecked")
            Constructor<T> found = (Constructor<T>) type.getDeclaredConstructor(
                    java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
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
                    if (generated.value() == eu.mikart.tava.schema.GeneratedValue.UUID) fieldValue = UUID.randomUUID();
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
        if (raw == null || target.isInstance(raw)) return raw;
        if (target == String.class) return raw.toString();
        if (target == UUID.class) return UUID.fromString(raw.toString());
        if (target == Instant.class) {
            if (raw instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
            return Instant.parse(raw.toString());
        }
        if ((target == int.class || target == Integer.class) && raw instanceof Number n) return n.intValue();
        if ((target == long.class || target == Long.class) && raw instanceof Number n) return n.longValue();
        if ((target == boolean.class || target == Boolean.class) && raw instanceof Number n) return n.intValue() != 0;
        return raw;
    }
}
