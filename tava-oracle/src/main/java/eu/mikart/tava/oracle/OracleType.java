package eu.mikart.tava.oracle;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the Oracle column type for a record component. */
@Documented
@AdapterOverride(adapter = "oracle", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface OracleType {
    /**
     * Returns the native Oracle type declaration.
     *
     * @return native Oracle type declaration
     */
    String value();
}
