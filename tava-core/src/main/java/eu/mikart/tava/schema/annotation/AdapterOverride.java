package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface AdapterOverride {
    String adapter();

    String key();
}
