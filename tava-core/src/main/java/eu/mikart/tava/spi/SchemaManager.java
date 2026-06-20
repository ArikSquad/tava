package eu.mikart.tava.spi;

import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;

public interface SchemaManager {
    Schema inspect();

    SchemaPlan plan(Schema desired);
}
