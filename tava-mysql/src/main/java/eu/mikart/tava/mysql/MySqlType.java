package eu.mikart.tava.mysql;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the MySQL column type for a record component. */
@Documented
@AdapterOverride(adapter = "mysql", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MySqlType {
    /**
     * Returns the native MySQL type declaration.
     *
     * @return native MySQL type declaration
     */
    String value();
}
