package eu.mikart.tava.query;

import eu.mikart.tava.schema.RecordSchemas;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * A reusable, typed field name. Generated model companions can expose these as constants.
 *
 * @param <T> Java type expected for values of the field
 * @param name field name
 * @param type class of the field's Java type
 */
public record FieldRef<T>(@NotNull String name, @NotNull Class<T> type) {
    /**
     * Creates a typed field reference.
     *
     * @param name field name
     * @param type class of the field's Java type
     * @throws IllegalArgumentException if {@code name} is blank or {@code type} is {@code null}
     */
    public FieldRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required");
    }

    /**
     * Creates a typed field reference.
     *
     * @param <T> Java type expected for values of the field
     * @param name field name
     * @param type class of the field's Java type
     * @return a reference to the named field
     * @throws IllegalArgumentException if {@code name} is blank or {@code type} is {@code null}
     */
    public static <T> @NotNull FieldRef<T> of(@NotNull String name, @NotNull Class<T> type) {
        return new FieldRef<>(name, type);
    }
    /**
     * Resolves a record component to its mapped field name, checking its declared Java type. Primitive components
     * accept their boxed class; other types must match exactly.
     *
     * @param <T> field value type
     * @param recordType owning record type
     * @param componentName Java record component name
     * @param type expected field class
     * @return mapped field reference
     * @throws IllegalArgumentException if the component is absent or has a different type
     */
    public static <T> FieldRef<T> of(Class<? extends Record> recordType, String componentName, Class<T> type) {
        Objects.requireNonNull(type, "type");
        var components = recordType.getRecordComponents();
        if (components == null) throw new IllegalArgumentException("record type is required");
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            if (!component.getName().equals(componentName)) continue;
            Class<?> actual = component.getType();
            if (actual.isPrimitive()) {
                actual = MethodType.methodType(actual).wrap().returnType();
            }
            if (!actual.equals(type))
                throw new IllegalArgumentException("Component type does not match " + type.getName());
            var definition = RecordSchemas.describe(recordType);
            return of(definition.fields().get(i).name(), type);
        }
        throw new IllegalArgumentException("Unknown record component: " + componentName);
    }

    /**
     * Creates a collection reference without requiring an erased class cast at the call site. The element type guides
     * Java type checking; it does not validate stored collections.
     *
     * @param <E> element type
     * @param name field name
     * @param elementType element class
     * @return collection field reference
     */
    @SuppressWarnings("unchecked")
    public static <E> FieldRef<Collection<E>> collection(String name, Class<E> elementType) {
        Objects.requireNonNull(elementType, "elementType");
        return new FieldRef<>(name, (Class<Collection<E>>) (Class<?>) Collection.class);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return equality predicate
     */
    public Predicate eq(T value) {
        return Predicate.eq(this, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return inequality predicate
     */
    public Predicate ne(T value) {
        return Predicate.ne(this, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @param values values to match
     * @return membership predicate
     */
    public Predicate in(Collection<? extends T> values) {
        return Predicate.in(this, values);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return less-than predicate
     */
    public Predicate lt(T value) {
        return Predicate.lt(name, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return less-than-or-equal predicate
     */
    public Predicate lte(T value) {
        return Predicate.lte(name, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return greater-than predicate
     */
    public Predicate gt(T value) {
        return Predicate.gt(name, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return greater-than-or-equal predicate
     */
    public Predicate gte(T value) {
        return Predicate.gte(name, value);
    }

    /**
     * Creates a portable field expression.
     *
     * @return null-check predicate
     */
    public Predicate isNull() {
        return Predicate.isNull(this);
    }

    /**
     * Creates a portable field expression.
     *
     * @return non-null-check predicate
     */
    public Predicate isNotNull() {
        return Predicate.isNotNull(this);
    }

    /**
     * Creates a portable field expression.
     *
     * @return ascending sort
     */
    public Sort asc() {
        return Sort.asc(this);
    }

    /**
     * Creates a portable field expression.
     *
     * @return descending sort
     */
    public Sort desc() {
        return Sort.desc(this);
    }
}
