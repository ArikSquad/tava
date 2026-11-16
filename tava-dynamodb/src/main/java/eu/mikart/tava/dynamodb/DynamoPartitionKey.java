package eu.mikart.tava.dynamodb;

import eu.mikart.tava.schema.annotation.AdapterOverride;
import java.lang.annotation.*;

/** Marks a record component as the DynamoDB partition key. */
@Documented
@AdapterOverride(adapter = "dynamodb", key = "partitionKey")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DynamoPartitionKey {
    /**
     * Returns the configured partition-key marker.
     *
     * @return adapter override value; defaults to {@code "true"}
     */
    String value() default "true";
}
