package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Field {
    String value() default "";

    int length() default -1;

    int precision() default -1;

    int scale() default -1;
}
