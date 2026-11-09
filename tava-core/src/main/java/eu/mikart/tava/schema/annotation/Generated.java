package eu.mikart.tava.schema.annotation;

import eu.mikart.tava.schema.GeneratedValue;
import java.lang.annotation.*;

/** Declares how a record component's value is generated. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Generated {
    /**
     * Returns the generation strategy.
     *
     * @return value-generation strategy
     */
    GeneratedValue value();
}
