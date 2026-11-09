package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/** Maps a record type to a storage entity name. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Entity {
    /**
     * Returns the storage entity name.
     *
     * @return explicit entity name, or an empty string to derive it from the record type name
     */
    String value() default "";
}
