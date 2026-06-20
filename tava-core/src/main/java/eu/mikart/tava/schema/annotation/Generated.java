package eu.mikart.tava.schema.annotation;

import eu.mikart.tava.schema.GeneratedValue;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Generated {
    GeneratedValue value();
}
