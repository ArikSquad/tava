package eu.mikart.tava.dynamodb;

import eu.mikart.tava.schema.annotation.AdapterOverride;

import java.lang.annotation.*;

@Documented
@AdapterOverride(adapter = "dynamodb", key = "partitionKey")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DynamoPartitionKey {
    String value() default "true";
}
