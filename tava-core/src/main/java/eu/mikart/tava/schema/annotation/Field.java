package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;
import org.jetbrains.annotations.Range;

/** Overrides the mapped name or type constraints of a record component. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Field {
    /**
     * Returns the storage field name.
     *
     * @return explicit field name, or an empty string to use the record component name
     */
    String value() default "";

    /**
     * Returns the maximum string length.
     *
     * @return maximum length, or a nonpositive value to leave the length unspecified
     */
    @Range(from = -1, to = Integer.MAX_VALUE)
    int length() default -1;

    /**
     * Returns the maximum number of decimal digits.
     *
     * @return decimal precision, or a value less than one when unspecified
     */
    @Range(from = -1, to = Integer.MAX_VALUE)
    int precision() default -1;

    /**
     * Returns the number of digits after the decimal point.
     *
     * @return decimal scale, or a negative value to use zero
     */
    @Range(from = -1, to = Integer.MAX_VALUE)
    int scale() default -1;
}
