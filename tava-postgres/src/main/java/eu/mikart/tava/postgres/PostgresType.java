package eu.mikart.tava.postgres;

import eu.mikart.tava.schema.annotation.AdapterOverride;

import java.lang.annotation.*;

@Documented
@AdapterOverride(adapter = "postgres", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface PostgresType {
    String value();
}
