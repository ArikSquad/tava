package eu.mikart.tava.schema.annotation;

import org.jetbrains.annotations.Range;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Field {
    String value() default "";

    @Range(from = -1, to = Integer.MAX_VALUE) int length() default -1;

    @Range(from = -1, to = Integer.MAX_VALUE) int precision() default -1;

    @Range(from = -1, to = Integer.MAX_VALUE) int scale() default -1;
}
