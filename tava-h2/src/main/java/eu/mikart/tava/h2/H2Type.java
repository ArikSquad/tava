package eu.mikart.tava.h2;

import eu.mikart.tava.schema.annotation.AdapterOverride;

import java.lang.annotation.*;

@Documented
@AdapterOverride(adapter = "h2", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface H2Type {
    String value();
}
