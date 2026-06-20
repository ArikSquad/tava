package eu.mikart.tava.sqlserver;

import eu.mikart.tava.schema.annotation.AdapterOverride;

import java.lang.annotation.*;

@Documented
@AdapterOverride(adapter = "sqlserver", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SqlServerType {
    String value();
}
