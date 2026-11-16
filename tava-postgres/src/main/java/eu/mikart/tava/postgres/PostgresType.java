package eu.mikart.tava.postgres;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the PostgreSQL column type for a record component. */
@Documented
@AdapterOverride(adapter = "postgres", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface PostgresType {
    /**
     * Returns the native PostgreSQL type declaration.
     *
     * @return native PostgreSQL type declaration
     */
    String value();
}
