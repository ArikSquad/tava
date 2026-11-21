package eu.mikart.tava.query;

import eu.mikart.tava.schema.annotation.Field;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * A reusable, typed field name. Generated model companions can expose these as constants.
 *
 * @param <R> owning record type
 * @param <T> Java type expected for values of the field
 * @param name field name
 * @param type class of the field's Java type
 */
public record FieldRef<R extends Record, T>(
        @NotNull String name, @NotNull Class<T> type) {
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
    public static <T> @NotNull FieldRef<Record, T> of(@NotNull String name, @NotNull Class<T> type) {
        return new FieldRef<>(name, type);
    }
    /**
     * Resolves a record component to its mapped field name, checking its declared Java type. Primitive components
     * accept their boxed class; other types must match exactly.
     *
     * @param <T> field value type
     * @param <R> owning record type
     * @param recordType owning record type
     * @param componentName Java record component name
     * @param type expected field class
     * @return mapped field reference
     * @throws IllegalArgumentException if the component is absent or has a different type
     */
    public static <R extends Record, T> FieldRef<R, T> of(Class<R> recordType, String componentName, Class<T> type) {
        Objects.requireNonNull(type, "type");
        var components = recordType.getRecordComponents();
        if (components == null) throw new IllegalArgumentException("record type is required");
        for (var component : components) {
            if (!component.getName().equals(componentName)) continue;
            Class<?> actual = component.getType();
            if (actual.isPrimitive()) {
                actual = MethodType.methodType(actual).wrap().returnType();
            }
            if (!actual.equals(type))
                throw new IllegalArgumentException("Component type does not match " + type.getName());
            Field mapped = component.getAnnotation(Field.class);
            String name = mapped == null || mapped.value().isBlank() ? componentName : mapped.value();
            return new FieldRef<>(name, type);
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
    public static <E> FieldRef<Record, Collection<E>> collection(String name, Class<E> elementType) {
        Objects.requireNonNull(elementType, "elementType");
        return new FieldRef<>(name, (Class<Collection<E>>) (Class<?>) Collection.class);
    }

    /**
     * Drops record ownership at a dynamic API boundary.
     *
     * @return value-typed field reference
     */
    public FieldRef<Record, T> untyped() {
        return of(name, type);
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return equality predicate
     */
    public TypedQuery.Filter<R> eq(T value) {
        return new TypedQuery.Filter<>(Predicate.eq(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return inequality predicate
     */
    public TypedQuery.Filter<R> ne(T value) {
        return new TypedQuery.Filter<>(Predicate.ne(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @param values values to match
     * @return membership predicate
     */
    public TypedQuery.Filter<R> in(Collection<? extends T> values) {
        return new TypedQuery.Filter<>(Predicate.in(name, values));
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return less-than predicate
     */
    public TypedQuery.Filter<R> lt(T value) {
        return new TypedQuery.Filter<>(Predicate.lt(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return less-than-or-equal predicate
     */
    public TypedQuery.Filter<R> lte(T value) {
        return new TypedQuery.Filter<>(Predicate.lte(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return greater-than predicate
     */
    public TypedQuery.Filter<R> gt(T value) {
        return new TypedQuery.Filter<>(Predicate.gt(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @param value comparison value
     * @return greater-than-or-equal predicate
     */
    public TypedQuery.Filter<R> gte(T value) {
        return new TypedQuery.Filter<>(Predicate.gte(name, value));
    }

    /**
     * Creates a portable field expression.
     *
     * @return null-check predicate
     */
    public TypedQuery.Filter<R> isNull() {
        return new TypedQuery.Filter<>(Predicate.isNull(name));
    }

    /**
     * Creates a portable field expression.
     *
     * @return non-null-check predicate
     */
    public TypedQuery.Filter<R> isNotNull() {
        return new TypedQuery.Filter<>(Predicate.isNotNull(name));
    }

    /**
     * Creates a portable field expression.
     *
     * @return ascending sort
     */
    public TypedQuery.Order<R> asc() {
        return new TypedQuery.Order<>(Sort.asc(name));
    }

    /**
     * Creates a portable field expression.
     *
     * @return descending sort
     */
    public TypedQuery.Order<R> desc() {
        return new TypedQuery.Order<>(Sort.desc(name));
    }
}
