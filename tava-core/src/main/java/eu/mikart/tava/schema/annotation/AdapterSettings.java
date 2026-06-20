package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT})
public @interface AdapterSettings {
    AdapterSetting[] value();
}
