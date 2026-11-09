package eu.mikart.tava.migration;

import eu.mikart.tava.schema.Schema;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One named action in a migration's forward or rollback sequence.
 *
 * @param description human-readable step description
 * @param action action run with a migration context
 */
public record MigrationStep(String description, Consumer<MigrationContext> action) {
    /**
     * Creates a migration step.
     *
     * @param description human-readable step description
     * @param action action run with a migration context
     * @throws IllegalArgumentException if {@code description} is blank
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public MigrationStep {
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("step description is required");
        action = Objects.requireNonNull(action, "step action is required");
    }

    /**
     * Creates a step that reconciles a schema.
     *
     * @param description human-readable step description
     * @param schema desired schema
     * @return schema migration step
     * @throws IllegalArgumentException if {@code description} is blank
     * @throws NullPointerException if {@code schema} is {@code null}
     */
    public static MigrationStep schema(final String description, final Schema schema) {
        final Schema desired = Objects.requireNonNull(schema, "schema is required");
        return action(description, context -> context.apply(description, desired));
    }

    /**
     * Creates a step from an application action.
     *
     * @param description human-readable step description
     * @param action migration action
     * @return action migration step
     * @throws IllegalArgumentException if {@code description} is blank
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static MigrationStep action(final String description, final Consumer<MigrationContext> action) {
        return new MigrationStep(description, action);
    }

    void run(final MigrationContext context) {
        context.operation(description, () -> action.accept(context));
    }
}
