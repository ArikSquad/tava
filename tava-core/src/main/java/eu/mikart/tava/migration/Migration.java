package eu.mikart.tava.migration;

import eu.mikart.tava.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ordered forward and rollback steps for one migration.
 *
 * @param id stable migration identifier
 * @param up forward steps
 * @param down rollback steps, or an empty list when the migration is not reversible
 * @param checksum optional immutable content digest used to reject changed migration definitions
 */
public record Migration(
        @NotNull String id,
        List<MigrationStep> up,
        List<MigrationStep> down,
        @Nullable String checksum) {
    /**
     * Creates a migration without a content checksum.
     *
     * @param id stable migration identifier
     * @param up forward steps
     * @param down rollback steps, or an empty list when the migration is not reversible
     */
    public Migration(String id, List<MigrationStep> up, List<MigrationStep> down) {
        this(id, up, down, null);
    }

    /**
     * Creates a migration and optionally binds it to an immutable content digest.
     *
     * @param id stable migration identifier
     * @param up forward steps
     * @param down rollback steps, or an empty list when the migration is not reversible
     * @param checksum optional immutable content digest used to reject changed migration definitions
     * @throws IllegalArgumentException if the identifier is blank or the checksum is blank or too long
     * @throws NullPointerException if {@code up} is {@code null}
     * @since 1.3.0
     */
    public Migration {
        if (checksum != null && (checksum.isBlank() || checksum.length() > 128))
            throw new IllegalArgumentException("checksum must contain 1 to 128 characters");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("migration id is required");
        up = List.copyOf(Objects.requireNonNull(up, "up steps are required"));
        down = down == null ? List.of() : List.copyOf(down);
        if (up.isEmpty()) throw new IllegalArgumentException("migration up steps are required");
    }

    /**
     * Returns the optional content digest for this migration.
     *
     * @return content digest, or {@code null} when checksum verification is disabled
     * @since 1.3.0
     */
    @Override
    public @Nullable String checksum() {
        return checksum;
    }

    /**
     * Creates a forward-only schema migration.
     *
     * @param id stable migration identifier
     * @param schema schema to apply
     * @return the migration definition
     */
    public static Migration up(final String id, final Schema schema) {
        return builder(id).upSchema(schema).build();
    }

    /**
     * Creates a forward-only migration from an application action.
     *
     * @param id stable migration identifier
     * @param action migration action
     * @return the migration definition
     */
    public static Migration up(final String id, final Consumer<MigrationContext> action) {
        return builder(id).up(action).build();
    }

    /**
     * Creates a reversible schema migration.
     *
     * @param id stable migration identifier
     * @param up schema to apply
     * @param down schema to restore during rollback
     * @return the migration definition
     */
    public static Migration reversible(final String id, final Schema up, final Schema down) {
        return builder(id).upSchema(up).downSchema(down).build();
    }

    /**
     * Starts building a migration with ordered forward and rollback steps.
     *
     * @param id stable migration identifier
     * @return a builder for the migration
     */
    public static Builder builder(final String id) {
        return new Builder(id);
    }

    /**
     * Reports whether this migration defines at least one rollback step.
     *
     * @return {@code true} when rollback steps are defined
     */
    public boolean reversible() {
        return !down.isEmpty();
    }

    /** Builds one migration definition. */
    public static final class Builder {
        private final String id;
        private String checksum;
        private final List<MigrationStep> up = new ArrayList<>();
        private final List<MigrationStep> down = new ArrayList<>();

        private Builder(final String id) {
            this.id = id;
        }

        /**
         * Sets an immutable content digest supplied by the application or migration build tooling.
         *
         * @param checksum migration content digest
         * @return this builder
         * @throws NullPointerException if {@code checksum} is {@code null}
         * @since 1.3.0
         */
        public Builder checksum(String checksum) {
            this.checksum = Objects.requireNonNull(checksum, "checksum");
            return this;
        }

        /**
         * Adds a schema reconciliation step to the forward migration.
         *
         * @param schema desired schema
         * @return this builder
         */
        public Builder upSchema(final Schema schema) {
            up.add(MigrationStep.schema("Apply schema", schema));
            return this;
        }

        /**
         * Adds a schema reconciliation step to the rollback.
         *
         * @param schema schema to restore
         * @return this builder
         */
        public Builder downSchema(final Schema schema) {
            down.add(MigrationStep.schema("Rollback schema", schema));
            return this;
        }

        /**
         * Adds a forward action with a default description.
         *
         * @param action migration action
         * @return this builder
         */
        public Builder up(final Consumer<MigrationContext> action) {
            return up("Run migration step", action);
        }

        /**
         * Adds a named forward action.
         *
         * @param description human-readable operation description
         * @param action migration action
         * @return this builder
         */
        public Builder up(final String description, final Consumer<MigrationContext> action) {
            up.add(MigrationStep.action(description, action));
            return this;
        }

        /**
         * Adds a rollback action with a default description.
         *
         * @param action rollback action
         * @return this builder
         */
        public Builder down(final Consumer<MigrationContext> action) {
            return down("Run rollback step", action);
        }

        /**
         * Adds a named rollback action.
         *
         * @param description human-readable operation description
         * @param action rollback action
         * @return this builder
         */
        public Builder down(final String description, final Consumer<MigrationContext> action) {
            down.add(MigrationStep.action(description, action));
            return this;
        }

        /**
         * Builds the immutable migration definition.
         *
         * @return migration definition
         */
        public Migration build() {
            return new Migration(id, up, down, checksum);
        }
    }
}
