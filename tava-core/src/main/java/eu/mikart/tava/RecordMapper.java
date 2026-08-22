package eu.mikart.tava;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.RecordSchemas;
import eu.mikart.tava.schema.annotation.Field;
import eu.mikart.tava.schema.annotation.Generated;
import eu.mikart.tava.schema.annotation.Identity;
import eu.mikart.tava.schema.annotation.MappedWith;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RecordMapper<T extends Record> {
    private static final ClassValue<RecordMapper<?>> CACHE = new ClassValue<>() {
        @Override
        protected RecordMapper<?> computeValue(final Class<?> type) {
            return new RecordMapper<>(type.asSubclass(Record.class));
        }
    };

    private static final ClassValue<RecordMetadata> METADATA_CACHE = new ClassValue<>() {
        @Override
        protected RecordMetadata computeValue(final Class<?> type) {
            return RecordMetadata.create(type.asSubclass(Record.class));
        }
    };

    @SuppressWarnings("unchecked")
    static <T extends Record> RecordMapper<T> of(final @NotNull Class<T> type) {
        return (RecordMapper<T>) CACHE.get(type);
    }

    private final Class<T> type;
    private final RecordMetadata metadata;
    private final String entityName;

    private RecordMapper(final @NotNull Class<T> type) {
        this.type = type;
        this.entityName = RecordSchemas.describe(type).name();
        this.metadata = metadata(type);
    }

    @NotNull String entityName() {
        return entityName;
    }

    @NotNull EntityRecord write(final @NotNull T value) {
        final Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (final Component component : metadata.components) {
                Object fieldValue = component.accessor.invoke(value);
                if (fieldValue == null && component.generated != null) {
                    if (component.generated.value() == GeneratedValue.UUID) fieldValue = UUID.randomUUID();
                    else continue;
                }
                values.put(component.name, encode(component, fieldValue));
            }
            return EntityRecord.of(values);
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Data("Cannot read record " + type.getName(), failure);
        }
    }

    eu.mikart.tava.data.@NotNull Identity identity(final @NotNull Object value) {
        if (metadata.identities.size() != 1) {
            throw new TavaException.Schema("Entity requires exactly one identity for scalar ID operations; use Identity.of(...) for composite keys");
        }
        return eu.mikart.tava.data.Identity.of(metadata.identities.getFirst().name, value);
    }

    eu.mikart.tava.data.@NotNull Identity identity(final @NotNull T record) {
        final Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (final Component component : metadata.identities) {
                values.put(component.name, component.accessor.invoke(record));
            }
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Data("Cannot read identity", failure);
        }
        if (values.isEmpty()) throw new TavaException.Schema("Record has no @Identity field");
        return eu.mikart.tava.data.Identity.of(values);
    }

    @NotNull Mutation update(final @NotNull T record) {
        final Map<String, Object> values = new LinkedHashMap<>(write(record).values());
        for (final Component component : metadata.identities) values.remove(component.name);
        return new Mutation(values);
    }

    @NotNull Mutation write(final @NotNull Mutation mutation) {
        final Map<String, Object> values = new LinkedHashMap<>();
        mutation.values().forEach((name, value) -> {
            final Component component = metadata.byName.get(name);
            values.put(name, component == null ? value : encode(component, value));
        });
        final Map<String, Mutation.Operation> operations = new LinkedHashMap<>();
        mutation.operations().forEach((name, operation) -> {
            final Component component = metadata.byName.get(name);
            operations.put(name, new Mutation.Operation(operation.kind(),
                    component == null ? operation.value() : encodeElement(component, operation.value())));
        });
        return new Mutation(values, operations);
    }

    @NotNull Query write(final @NotNull Query query) {
        return new Query(write(query.predicate()), query.projection(), query.sorting(), query.limit(), query.cursor());
    }

    @NotNull Predicate write(final @NotNull Predicate predicate) {
        if (predicate instanceof Predicate.All) return predicate;
        if (predicate instanceof Predicate.Junction junction) {
            final List<Predicate> predicates = new ArrayList<>(junction.predicates().size());
            for (final Predicate child : junction.predicates()) predicates.add(write(child));
            return new Predicate.Junction(junction.and(), predicates);
        }
        final Predicate.Comparison comparison = (Predicate.Comparison) predicate;
        final Component component = metadata.byName.get(comparison.field());
        if (component == null || comparison.value() == null) return comparison;
        final Object encoded;
        if (comparison.operator() == Predicate.Operator.IN && comparison.value() instanceof Collection<?> values) {
            final List<Object> encodedValues = new ArrayList<>(values.size());
            for (final Object value : values) encodedValues.add(encode(component, value));
            encoded = List.copyOf(encodedValues);
        } else if (Collection.class.isAssignableFrom(component.type)
                && comparison.operator() == Predicate.Operator.CONTAINS) {
            encoded = encodeElement(component, comparison.value());
        } else {
            encoded = encode(component, comparison.value());
        }
        return new Predicate.Comparison(comparison.field(), comparison.operator(), encoded);
    }

    @NotNull T read(final @NotNull EntityRecord value) {
        final Object[] args = new Object[metadata.components.length];
        for (int i = 0; i < metadata.components.length; i++) {
            final Component component = metadata.components[i];
            args[i] = decode(component, value.get(component.name));
        }
        try {
            @SuppressWarnings("unchecked")
            final T result = (T) metadata.constructor.newInstance(args);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Data("Cannot create record " + type.getName(), failure);
        }
    }

    private Object encode(final Component component, final Object value) {
        if (value == null) return null;
        final ValueCodec<Object> codec = component.codec();
        if (codec != null) return codec.encode(value);
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Collection<?> collection) return collection.stream().map(this::encodeValue).toList();
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), encodeValue(item)));
            return result;
        }
        if (value instanceof Record embedded) {
            final Map<String, Object> result = new LinkedHashMap<>();
            final RecordMetadata embeddedMetadata = metadata(embedded.getClass());
            try {
                for (final Component child : embeddedMetadata.components) {
                    result.put(child.name, encode(child, child.accessor.invoke(embedded)));
                }
            } catch (ReflectiveOperationException failure) {
                throw new TavaException.Mapping("Cannot encode embedded record", failure);
            }
            return result;
        }
        return value;
    }

    private Object encodeValue(final Object value) {
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Collection<?> collection) return collection.stream().map(this::encodeValue).toList();
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), encodeValue(item)));
            return result;
        }
        return value;
    }

    private Object encodeElement(final Component component, final Object value) {
        if (value == null) return null;
        final Type generic = component.reflection.getGenericType();
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length > 0) {
            return encodeValue(value);
        }
        return encode(component, value);
    }

    private Object decode(final Component component, Object raw) {
        final ValueCodec<Object> codec = component.codec();
        if (codec != null) return codec.decode(raw);
        final Class<?> target = component.type;
        if (raw instanceof String text && (Collection.class.isAssignableFrom(target)
                || Map.class.isAssignableFrom(target) || target.isRecord())) {
            raw = ValueCodecs.fromJson(text);
        }
        if (raw != null && target.isEnum()) return Enum.valueOf(target.asSubclass(Enum.class), raw.toString());
        if (raw instanceof Collection<?> collection && Collection.class.isAssignableFrom(target)) {
            final Type elementType = component.reflection.getGenericType() instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0] : Object.class;
            final List<Object> values = collection.stream().map(value -> decodeValue(value, elementType)).toList();
            if (Set.class.isAssignableFrom(target)) return new LinkedHashSet<>(values);
            return new ArrayList<>(values);
        }
        if (raw instanceof Map<?, ?> map && target.isRecord()) {
            final RecordMetadata embeddedMetadata = metadata(target.asSubclass(Record.class));
            final Object[] values = new Object[embeddedMetadata.components.length];
            for (int i = 0; i < embeddedMetadata.components.length; i++) {
                final Component child = embeddedMetadata.components[i];
                values[i] = decode(child, map.get(child.name));
            }
            try {
                return embeddedMetadata.constructor.newInstance(values);
            } catch (ReflectiveOperationException failure) {
                throw new TavaException.Mapping("Cannot decode embedded record", failure);
            }
        }
        return convert(raw, target);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object decodeValue(final Object raw, final Type type) {
        if (raw == null || !(type instanceof Class<?> target)) return raw;
        if (target.isEnum()) return Enum.valueOf(target.asSubclass(Enum.class), raw.toString());
        if (target == UUID.class) return UUID.fromString(raw.toString());
        return convert(raw, target);
    }

    @SuppressWarnings("unchecked")
    private static @NotNull ValueCodec<Object> createCodec(final @NotNull MappedWith mapped) {
        try {
            return (ValueCodec<Object>) mapped.value().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new TavaException.Mapping("Cannot create codec " + mapped.value().getName(), failure);
        }
    }

    private static @NotNull RecordMetadata metadata(final @NotNull Class<? extends Record> type) {
        return METADATA_CACHE.get(type);
    }

    private static @NotNull String fieldName(final @NotNull RecordComponent component) {
        final Field field = component.getAnnotation(Field.class);
        return field != null && !field.value().isBlank() ? field.value() : component.getName();
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

    private static final class RecordMetadata {
        private final Component[] components;
        private final List<Component> identities;
        private final Map<String, Component> byName;
        private final Constructor<?> constructor;

        private RecordMetadata(
                final Component[] components,
                final List<Component> identities,
                final Map<String, Component> byName,
                final Constructor<?> constructor
        ) {
            this.components = components;
            this.identities = List.copyOf(identities);
            this.byName = Map.copyOf(byName);
            this.constructor = constructor;
        }

        private static @NotNull RecordMetadata create(final @NotNull Class<? extends Record> type) {
            final RecordComponent[] reflections = type.getRecordComponents();
            final Component[] components = new Component[reflections.length];
            final List<Component> identities = new ArrayList<>();
            final Map<String, Component> byName = new HashMap<>(Math.max(1, reflections.length * 2));
            for (int i = 0; i < reflections.length; i++) {
                final Component component = new Component(reflections[i]);
                components[i] = component;
                byName.put(component.name, component);
                if (component.identity) identities.add(component);
            }
            try {
                final Constructor<?> constructor = type.getDeclaredConstructor(
                        Arrays.stream(reflections).map(RecordComponent::getType).toArray(Class<?>[]::new));
                constructor.setAccessible(true);
                return new RecordMetadata(components, identities, byName, constructor);
            } catch (ReflectiveOperationException failure) {
                throw new TavaException.Schema("Cannot access canonical constructor for " + type.getName(), failure);
            }
        }
    }

    private static final class Component {
        private final RecordComponent reflection;
        private final String name;
        private final Class<?> type;
        private final Method accessor;
        private final boolean identity;
        private final Generated generated;
        private final MappedWith mapped;
        private volatile ValueCodec<Object> codec;

        private Component(final @NotNull RecordComponent reflection) {
            this.reflection = reflection;
            this.name = fieldName(reflection);
            this.type = reflection.getType();
            this.accessor = reflection.getAccessor();
            this.accessor.setAccessible(true);
            this.identity = reflection.isAnnotationPresent(Identity.class);
            this.generated = reflection.getAnnotation(Generated.class);
            this.mapped = reflection.getAnnotation(MappedWith.class);
        }

        private @Nullable ValueCodec<Object> codec() {
            if (mapped == null) return null;
            ValueCodec<Object> result = codec;
            if (result != null) return result;
            synchronized (this) {
                result = codec;
                if (result == null) {
                    result = createCodec(mapped);
                    codec = result;
                }
            }
            return result;
        }
    }
}
