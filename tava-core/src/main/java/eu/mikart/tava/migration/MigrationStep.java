package eu.mikart.tava.migration;

import eu.mikart.tava.schema.Schema;

import java.util.Objects;
import java.util.function.Consumer;

public record MigrationStep(String description, Consumer<MigrationContext> action) {
    public MigrationStep {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("step description is required");
        action = Objects.requireNonNull(action, "step action is required");
    }

    public static MigrationStep schema(final String description, final Schema schema) {
        final Schema desired = Objects.requireNonNull(schema, "schema is required");
        return action(description, context -> context.apply(description, desired));
    }

    public static MigrationStep action(final String description, final Consumer<MigrationContext> action) {
        return new MigrationStep(description, action);
    }

    void run(final MigrationContext context) {
        context.operation(description, () -> action.accept(context));
    }
}
