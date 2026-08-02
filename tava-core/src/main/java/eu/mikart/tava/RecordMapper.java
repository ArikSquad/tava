package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.annotation.Generated;
import eu.mikart.tava.schema.annotation.Field;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.annotation.Identity;
import eu.mikart.tava.schema.annotation.MappedWith;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class RecordMapper<T extends Record> {
    private final Class<T> type;
    private final Constructor<T> constructor;
    private final RecordComponent[] components;

    RecordMapper(final @NotNull Class<T> type) {
        this.type = type;
        RecordSchemas.describe(type);
        this.components = type.getRecordComponents();
        try {
            // Records are mapped through their canonical constructor to preserve compact constructors.
            final Constructor<T> found = type.getDeclaredConstructor(
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            found.setAccessible(true);
            this.constructor = found;
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Schema("Cannot access canonical constructor for " + type.getName(), failure);
        }
    }

    @NotNull EntityRecord write(final @NotNull T value) {
        final Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (final RecordComponent component : components) {
                final Field field = component.getAnnotation(Field.class);
                final String name = field != null && !field.value().isBlank() ? field.value() : component.getName();
                final var accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object fieldValue = accessor.invoke(value);
                final Generated generated = component.getAnnotation(Generated.class);
                if (fieldValue == null && generated != null) {
                    if (generated.value() == GeneratedValue.UUID) fieldValue = UUID.randomUUID();
                    else continue;
                }
                values.put(name, encode(component, fieldValue));
            }
            return EntityRecord.of(values);
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Data("Cannot read record " + type.getName(), failure);
        }
    }

    eu.mikart.tava.data.@NotNull Identity identity(final @NotNull Object value) {
        final var identities = Arrays.stream(components).filter(c -> c.isAnnotationPresent(Identity.class)).toList();
        if (identities.size() != 1) throw new TavaException.Schema("Entity requires exactly one identity for scalar ID operations; use Identity.of(...) for composite keys");
        RecordComponent identity = identities.getFirst();
        return eu.mikart.tava.data.Identity.of(fieldName(identity), value);
    }

    eu.mikart.tava.data.@NotNull Identity identity(final @NotNull T record) {
        final Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (final RecordComponent component : components) if (component.isAnnotationPresent(Identity.class)) {
                component.getAccessor().setAccessible(true);
                values.put(fieldName(component), component.getAccessor().invoke(record));
            }
        } catch (ReflectiveOperationException failure) { throw new TavaException.Data("Cannot read identity", failure); }
        if (values.isEmpty()) throw new TavaException.Schema("Record has no @Identity field");
        return eu.mikart.tava.data.Identity.of(values);
    }

    @NotNull Mutation update(final @NotNull T record) {
        Map<String, Object> values = new LinkedHashMap<>(write(record).values());
        Arrays.stream(components).filter(c -> c.isAnnotationPresent(Identity.class)).map(this::fieldName).forEach(values::remove);
        return new Mutation(values);
    }

    @NotNull Mutation write(final @NotNull Mutation mutation) {
        Map<String, RecordComponent> byName = new LinkedHashMap<>();
        for (RecordComponent component : components) byName.put(fieldName(component), component);
        Map<String, Object> values = new LinkedHashMap<>();
        mutation.values().forEach((name, value) -> values.put(name,
                byName.containsKey(name) ? encode(byName.get(name), value) : value));
        Map<String, Mutation.Operation> operations = new LinkedHashMap<>();
        mutation.operations().forEach((name, operation) -> operations.put(name, new Mutation.Operation(
                operation.kind(), byName.containsKey(name) ? encodeElement(byName.get(name), operation.value()) : operation.value())));
        return new Mutation(values, operations);
    }

    @NotNull Query write(final @NotNull Query query) {
        return new Query(write(query.predicate()), query.projection(), query.sorting(), query.limit(), query.cursor());
    }

    @NotNull Predicate write(final @NotNull Predicate predicate) {
        if (predicate instanceof Predicate.All) return predicate;
        if (predicate instanceof Predicate.Junction junction)
            return new Predicate.Junction(junction.and(), junction.predicates().stream().map(this::write).toList());
        Predicate.Comparison comparison = (Predicate.Comparison) predicate;
        RecordComponent component = Arrays.stream(components)
                .filter(value -> fieldName(value).equals(comparison.field())).findFirst().orElse(null);
        if (component == null || comparison.value() == null) return comparison;
        Object encoded;
        if (comparison.operator() == Predicate.Operator.IN && comparison.value() instanceof Collection<?> values)
            encoded = values.stream().map(value -> encode(component, value)).toList();
        else if (Collection.class.isAssignableFrom(component.getType())
                && comparison.operator() == Predicate.Operator.CONTAINS)
            encoded = encodeElement(component, comparison.value());
        else encoded = encode(component, comparison.value());
        return new Predicate.Comparison(comparison.field(), comparison.operator(), encoded);
    }

    private String fieldName(RecordComponent component) {
        Field field = component.getAnnotation(Field.class);
        return field != null && !field.value().isBlank() ? field.value() : component.getName();
    }

    @NotNull T read(final @NotNull EntityRecord value) {
        final Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            final Field field = components[i].getAnnotation(Field.class);
            final String name = field != null && !field.value().isBlank() ? field.value() : components[i].getName();
            args[i] = decode(components[i], value.get(name));
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Data("Cannot create record " + type.getName(), failure);
        }
    }

    private Object encode(RecordComponent component, Object value) {
        if (value == null) return null;
        MappedWith mapped = component.getAnnotation(MappedWith.class);
        if (mapped != null) return codec(mapped).encode(value);
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Collection<?> collection) return collection.stream().map(this::encodeValue).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), encodeValue(item)));
            return result;
        }
        if (value instanceof Record embedded) {
            Map<String, Object> result = new LinkedHashMap<>();
            try { for (RecordComponent child : embedded.getClass().getRecordComponents()) { child.getAccessor().setAccessible(true); result.put(fieldName(child), encode(child, child.getAccessor().invoke(embedded))); } }
            catch (ReflectiveOperationException failure) { throw new TavaException.Mapping("Cannot encode embedded record", failure); }
            return result;
        }
        return value;
    }

    private Object encodeValue(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Collection<?> collection) return collection.stream().map(this::encodeValue).toList();
        if (value instanceof Map<?, ?> map) { Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> result.put(String.valueOf(key), encodeValue(item))); return result; }
        return value;
    }

    private Object encodeElement(RecordComponent component, Object value) {
        if (value == null) return null;
        Type generic = component.getGenericType();
        if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length > 0)
            return encodeValue(value);
        return encode(component, value);
    }

    private Object decode(RecordComponent component, Object raw) {
        MappedWith mapped = component.getAnnotation(MappedWith.class);
        if (mapped != null) return codec(mapped).decode(raw);
        Class<?> target = component.getType();
        if (raw instanceof String text && (Collection.class.isAssignableFrom(target) || Map.class.isAssignableFrom(target) || target.isRecord()))
            raw = ValueCodecs.fromJson(text);
        if (raw != null && target.isEnum()) return Enum.valueOf(target.asSubclass(Enum.class), raw.toString());
        if (raw instanceof Collection<?> collection && Collection.class.isAssignableFrom(target)) {
            Type elementType = component.getGenericType() instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0] : Object.class;
            List<Object> values = collection.stream().map(value -> decodeValue(value, elementType)).toList();
            if (Set.class.isAssignableFrom(target)) return new LinkedHashSet<>(values);
            return new ArrayList<>(values);
        }
        if (raw instanceof Map<?, ?> map && target.isRecord()) {
            try {
                RecordComponent[] children = target.getRecordComponents();
                Object[] values = new Object[children.length];
                for (int i = 0; i < children.length; i++) values[i] = decode(children[i], map.get(fieldName(children[i])));
                var ctor = target.getDeclaredConstructor(Arrays.stream(children).map(RecordComponent::getType).toArray(Class<?>[]::new));
                ctor.setAccessible(true); return ctor.newInstance(values);
            } catch (ReflectiveOperationException failure) { throw new TavaException.Mapping("Cannot decode embedded record", failure); }
        }
        return convert(raw, target);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object decodeValue(Object raw, Type type) {
        if (raw == null || !(type instanceof Class<?> target)) return raw;
        if (target.isEnum()) return Enum.valueOf(target.asSubclass(Enum.class), raw.toString());
        if (target == UUID.class) return UUID.fromString(raw.toString());
        return convert(raw, target);
    }

    @SuppressWarnings("unchecked") private ValueCodec<Object> codec(MappedWith mapped) {
        try { return (ValueCodec<Object>) mapped.value().getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) { throw new TavaException.Mapping("Cannot create codec " + mapped.value().getName(), failure); }
    }

    private @Nullable Object convert(final @Nullable Object raw, final @NotNull Class<?> target) {
        return switch (raw) {
            case null -> null;
            case Object o when target.isInstance(o) -> o;
            case Object o when target == String.class -> o.toString();
            case Object o when target == UUID.class -> UUID.fromString(o.toString());
            case Timestamp timestamp when target == Instant.class -> timestamp.toInstant();
            case Object o when target == Instant.class -> Instant.parse(o.toString());
            case Object o when target == OffsetDateTime.class -> OffsetDateTime.parse(o.toString());
            case Object o when target == LocalDate.class -> LocalDate.parse(o.toString());
            case Object o when target == LocalDateTime.class -> LocalDateTime.parse(o.toString());
            case Number n when target == int.class || target == Integer.class -> n.intValue();
            case Number n when target == long.class || target == Long.class -> n.longValue();
            case Number n when target == boolean.class || target == Boolean.class -> n.intValue() != 0;
            case Number n when target == byte.class || target == Byte.class -> n.byteValue();
            default -> raw;
        };
    }
}
