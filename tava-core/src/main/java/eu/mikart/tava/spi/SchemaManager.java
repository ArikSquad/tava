package eu.mikart.tava.spi;

import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.SchemaPlan;
import org.jetbrains.annotations.NotNull;

public interface SchemaManager {
    @NotNull Schema inspect();

    @NotNull SchemaPlan plan(@NotNull Schema desired);

    default @NotNull SchemaPlan plan(@NotNull Schema desired, @NotNull SchemaRenames renames) {
        if (renames.empty()) return plan(desired);
        throw new eu.mikart.tava.TavaException.Capability("Adapter does not support schema rename mappings");
    }
}
