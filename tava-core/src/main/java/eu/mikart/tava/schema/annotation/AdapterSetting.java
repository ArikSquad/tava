package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

@Documented
@Repeatable(AdapterSettings.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT})
public @interface AdapterSetting {
    String adapter();

    String key();

    String value();
}
