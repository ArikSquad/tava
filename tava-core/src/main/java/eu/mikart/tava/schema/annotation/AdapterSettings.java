package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/** Container for repeated {@link AdapterSetting} declarations. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT})
public @interface AdapterSettings {
    /**
     * Returns the adapter settings contained in this annotation.
     *
     * @return contained adapter settings
     */
    AdapterSetting[] value();
}
