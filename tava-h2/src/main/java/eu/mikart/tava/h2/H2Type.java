package eu.mikart.tava.h2;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Overrides the H2 column type for a record component. */
@Documented
@AdapterOverride(adapter = "h2", key = "type")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface H2Type {
    /**
     * Returns the native H2 type declaration.
     *
     * @return native H2 type declaration, such as {@code TIMESTAMP WITH TIME ZONE}
     */
    String value();
}
