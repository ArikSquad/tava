package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/** Marks a record component as unique in the generated schema. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Unique {}
