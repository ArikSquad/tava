package eu.mikart.tava.schema.plan;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record SchemaPlan(@NotNull List<SchemaChange> changes, @NotNull Runnable application) {
    public SchemaPlan {
        changes = List.copyOf(changes);
    }

    public void apply() {
        apply(ApplyOptions.safe());
    }

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
