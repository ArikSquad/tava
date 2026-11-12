package eu.mikart.tava.spi;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Inspects and plans changes to an adapter's schema. */
public interface SchemaManager {
    /**
     * Inspects the schema currently stored by the adapter.
     *
     * @return current schema
     */
    @NotNull
    Schema inspect();

    /**
     * Plans changes needed to match the desired entities while preserving unrelated storage.
     *
     * @param desired desired schema
     * @return an inert plan
     */
    @NotNull
    SchemaPlan plan(@NotNull Schema desired);

    /**
     * Plans and applies safe changes using freshly inspected storage.
     *
     * @param desired desired schema
     * @return applied changes
     */
    default List<SchemaChange> ensure(@NotNull Schema desired) {
        return update(desired, ApplyOptions.safe());
    }

    /**
     * Plans and applies safe changes while preserving unrelated storage.
     *
     * @param desired desired schema
     * @return applied changes
     */
    default List<SchemaChange> update(@NotNull Schema desired) {
        return ensure(desired);
    }

    /**
     * Plans and applies changes with explicit risk allowances.
     *
     * @param desired desired schema
     * @param options allowed risks
     * @return applied changes
     */
    default List<SchemaChange> update(@NotNull Schema desired, @NotNull ApplyOptions options) {
        SchemaPlan plan = plan(desired);
        plan.apply(options);
        return plan.changes();
    }

    /**
     * Plans an exact namespace match, including storage omitted from the desired schema.
     *
     * @param desired complete schema for the namespace
     * @return an inert plan
     * @throws TavaException.Capability if exact schema reconciliation is unsupported
     * @since 1.3.0
     */
    default @NotNull SchemaPlan planExact(@NotNull Schema desired) {
        throw new TavaException.Capability("Adapter does not support exact schema reconciliation");
    }

    /**
     * Plans schema changes while mapping existing entity and field names to desired names.
     *
     * @param desired desired schema
     * @param renames explicit entity and field rename mappings
     * @return an inert plan
     */
    default @NotNull SchemaPlan plan(@NotNull Schema desired, @NotNull SchemaRenames renames) {
        if (renames.empty()) return plan(desired);
        throw new TavaException.Capability("Adapter does not support schema rename mappings");
    }
}
