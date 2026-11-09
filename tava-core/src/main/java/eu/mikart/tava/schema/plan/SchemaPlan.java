package eu.mikart.tava.schema.plan;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * A set of schema changes and the operation that applies them.
 *
 * @param changes planned schema changes
 * @param application operation that applies the changes
 */
public record SchemaPlan(
        @NotNull List<SchemaChange> changes, @NotNull Runnable application) {
    /**
     * Creates a schema plan.
     *
     * @param changes planned schema changes
     * @param application operation that applies the changes
     * @throws NullPointerException if {@code changes} or one of its elements is {@code null}
     */
    public SchemaPlan {
        changes = List.copyOf(changes);
    }

    /**
     * Applies the plan using the default options, which reject lossy and destructive changes.
     *
     * @throws IllegalStateException if a change is unsupported, lossy, or destructive
     */
    public void apply() {
        apply(ApplyOptions.safe());
    }

    /**
     * Applies the plan after checking each change against the supplied risk allowances.
     *
     * @param options allowances for lossy and destructive changes
     * @throws IllegalStateException if a change is unsupported or a risky change is not allowed
     */
    public void apply(final @NotNull ApplyOptions options) {
        for (final SchemaChange change : changes) {
            if (change.risk() == ChangeRisk.UNSUPPORTED)
                throw new IllegalStateException("Unsupported schema change: " + change.description());
            if (change.risk() == ChangeRisk.LOSSY && !options.allowLossy())
                throw new IllegalStateException("Lossy schema change requires opt-in: " + change.description());
            if (change.risk() == ChangeRisk.DESTRUCTIVE && !options.allowDestructive())
                throw new IllegalStateException("Destructive schema change requires opt-in: " + change.description());
        }
        application.run();
    }
}
