package eu.mikart.tava.migration;

import eu.mikart.tava.schema.Schema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public record Migration(@NotNull String id, List<MigrationStep> up, List<MigrationStep> down) {
    public Migration {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("migration id is required");
        up = List.copyOf(Objects.requireNonNull(up, "up steps are required"));
        down = down == null ? List.of() : List.copyOf(down);
        if (up.isEmpty()) throw new IllegalArgumentException("migration up steps are required");
    }

    public static Migration up(final String id, final Schema schema) {
        return builder(id).upSchema(schema).build();
    }

    public static Migration up(final String id, final Consumer<MigrationContext> action) {
        return builder(id).up(action).build();
    }

    public static Migration reversible(final String id, final Schema up, final Schema down) {
        return builder(id).upSchema(up).downSchema(down).build();
    }

    public static Builder builder(final String id) {
        return new Builder(id);
    }

    public boolean reversible() {
        return !down.isEmpty();
    }

    public static final class Builder {
        private final String id;
        private final List<MigrationStep> up = new ArrayList<>();
        private final List<MigrationStep> down = new ArrayList<>();

        private Builder(final String id) {
            this.id = id;
        }

        public Builder upSchema(final Schema schema) {
            up.add(MigrationStep.schema("Apply schema", schema));
            return this;
        }

        public Builder downSchema(final Schema schema) {
            down.add(MigrationStep.schema("Rollback schema", schema));
            return this;
        }

        public Builder up(final Consumer<MigrationContext> action) {
            return up("Run migration step", action);
        }

        public Builder up(final String description, final Consumer<MigrationContext> action) {
            up.add(MigrationStep.action(description, action));
            return this;
        }

        public Builder down(final Consumer<MigrationContext> action) {
            return down("Run rollback step", action);
        }

        public Builder down(final String description, final Consumer<MigrationContext> action) {
            down.add(MigrationStep.action(description, action));
            return this;
        }

        public Migration build() {
            return new Migration(id, up, down);
        }
    }
}
