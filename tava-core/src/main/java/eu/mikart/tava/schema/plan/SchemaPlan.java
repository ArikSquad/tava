package eu.mikart.tava.schema.plan;

import java.util.List;

public record SchemaPlan(List<SchemaChange> changes, Runnable application) {
    public SchemaPlan {
        changes = List.copyOf(changes);
    }

    public void apply() {
        apply(ApplyOptions.safe());
    }

    public void apply(ApplyOptions options) {
        for (SchemaChange change : changes) {
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
