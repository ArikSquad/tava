package eu.mikart.tava.schema.annotation;

import eu.mikart.tava.ValueCodec;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects a custom value codec for a record component. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MappedWith {
    /**
     * Returns the codec class used to map the component value.
     *
     * @return custom value codec class, which must have an accessible no-argument constructor
     */
    Class<? extends ValueCodec<?>> value();
}
