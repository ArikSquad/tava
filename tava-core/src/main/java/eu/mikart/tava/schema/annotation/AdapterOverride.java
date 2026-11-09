package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/**
 * Marks an annotation type as supplying a setting for a particular storage adapter. The annotated type must declare a
 * {@code value()} element whose value is stored under the configured adapter and key.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface AdapterOverride {
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
}
