package eu.mikart.tava.schema.annotation;

import java.lang.annotation.*;

/** Marks a record component as part of the entity identity. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Identity {}
