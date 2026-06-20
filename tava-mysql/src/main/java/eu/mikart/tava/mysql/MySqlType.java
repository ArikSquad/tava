package eu.mikart.tava.mysql;

import eu.mikart.tava.schema.annotation.AdapterOverride;

import java.lang.annotation.*;

@Documented
@AdapterOverride(adapter = "mysql", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MySqlType {
    String value();
}
