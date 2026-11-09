package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/** Declares an adapter-specific setting on a record type or component. */
@Documented
@Repeatable(AdapterSettings.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT})
public @interface AdapterSetting {
    /**
     * Returns the adapter identifier that owns the setting.
     *
     * @return adapter identifier
     */
    String adapter();

    /**
     * Returns the adapter-specific setting key.
     *
     * @return setting key
     */
    String key();

    /**
     * Returns the setting value.
     *
     * @return setting value
     */
    String value();
}
