package eu.mikart.tava.sqlserver;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the SQL Server column type for a record component. */
@Documented
@AdapterOverride(adapter = "sqlserver", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SqlServerType {
    /**
     * Returns the native SQL Server type declaration.
     *
     * @return native SQL Server type declaration
     */
    String value();
}
