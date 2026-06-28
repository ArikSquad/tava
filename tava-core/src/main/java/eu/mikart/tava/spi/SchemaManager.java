package eu.mikart.tava.spi;

import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;
import org.jetbrains.annotations.NotNull;

public interface SchemaManager {
    @NotNull Schema inspect();

    @NotNull SchemaPlan plan(@NotNull Schema desired);
}
