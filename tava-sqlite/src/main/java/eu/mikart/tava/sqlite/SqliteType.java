package eu.mikart.tava.sqlite;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the SQLite column type for a record component. */
@Documented
@AdapterOverride(adapter = "sqlite", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SqliteType {
    /**
     * Returns the native SQLite type declaration.
     *
     * @return native SQLite type declaration
     */
    String value();
}
