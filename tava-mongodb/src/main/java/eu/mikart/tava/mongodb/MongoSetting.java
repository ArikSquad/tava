package eu.mikart.tava.mongodb;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Supplies a MongoDB-specific storage hint for a record component. */
@Documented
@AdapterOverride(adapter = "mongodb", key = "storage")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MongoSetting {
    /**
     * Returns the MongoDB storage setting value.
     *
     * @return MongoDB storage setting value
     */
    String value();
}
