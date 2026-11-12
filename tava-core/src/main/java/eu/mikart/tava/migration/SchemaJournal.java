package eu.mikart.tava.migration;

import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.FieldType;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.LogicalType;
import eu.mikart.tava.schema.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SchemaJournal {
    static final String LOCK_ID = "__tava_migration_lock__";
    private final Tava database;
    private final SchemaHistory history;
    private final String journal;

    SchemaJournal(Tava database, SchemaHistory history) {
        this.database = Objects.requireNonNull(database, "database");
        this.history = history;
        this.journal = history.journal();
    }

    Optional<String> current() {
        Checkpoint checkpoint = read();
        if (checkpoint.active != null) throw interrupted(checkpoint.active);
        return checkpoint.applied == 0
                ? Optional.empty()
                : Optional.of(history.versions().get(checkpoint.applied - 1).id());
    }

    void move(int requested) {
        ensureStore();
        try (Lock ignored = acquire()) {
            Checkpoint checkpoint = read();
            if (requested == -1 && checkpoint.applied == 0 && checkpoint.active == null) return;
            int target = requested == -1 ? Math.max(0, checkpoint.applied - 1) : requested;
            Active active = checkpoint.active;
            if (active != null && (active.down ? target >= checkpoint.applied : target <= checkpoint.applied))
                throw interrupted(active);
            // Reject an incomplete rollback route before any version is changed.
            for (int index = checkpoint.applied - 1; index >= target; index--) {
                if (!history.versions().get(index).reversible())
                    throw new IllegalStateException("Version has no down actions: "
                            + history.versions().get(index).id());
            }
            if (active == null) verifyStartingSchema(checkpoint.applied);
            int applied = checkpoint.applied;
            if (active != null) {
                execute(active.index, active.down, active.completed, true);
                applied += active.down ? -1 : 1;
            }
            while (applied < target) {
                execute(applied, false, 0, false);
                applied++;
            }
            while (applied > target) {
                execute(applied - 1, true, 0, false);
                applied--;
            }
        }
    }

    private Checkpoint read() {
        boolean exists = database.schema().inspect().entities().stream()
                .anyMatch(entity -> entity.name().equals(journal));
        Map<String, EntityRecord> entries = new HashMap<>();
        if (exists) {
            try (var rows = records().stream(Query.all())) {
                rows.forEach(row -> {
                    if (!(row.get("id") instanceof String id))
                        throw new IllegalStateException("Invalid schema journal identifier");
                    if (!LOCK_ID.equals(id)) entries.put(id, row);
                });
            }
        }
        int applied = 0;
        boolean gap = false;
        Active active = null;
        for (int index = 0; index < history.versions().size(); index++) {
            SchemaHistory.Version version = history.versions().get(index);
            EntityRecord entry = entries.remove(version.id());
            if (entry == null) {
                gap = true;
                continue;
            }
            if (!version.checksum().equals(entry.get("checksum")))
                throw new IllegalStateException("Historical schema or actions changed: " + version.id());
            String state = Objects.toString(entry.get("state"), "");
            String direction = Objects.toString(entry.get("direction"), "");
            if (!(direction.equals("UP") || direction.equals("DOWN")))
                throw new IllegalStateException("Invalid schema journal direction: " + version.id());
            boolean down = direction.equals("DOWN");
            Object raw = entry.get("completed");
            if (!(raw instanceof Number completed)
                    || completed.longValue() < 0
                    || completed.longValue() > version.steps(down).size()
                    || completed.doubleValue() != completed.longValue()
                    || (down && !version.reversible()))
                throw new IllegalStateException("Invalid schema checkpoint: " + version.id());
            if (state.equals("REVERTED")
                    && down
                    && completed.intValue() == version.steps(true).size()) {
                gap = true;
                continue;
            }
            if (gap) throw new IllegalStateException("Recorded schema versions are not an ordered prefix");
            if (state.equals("APPLIED")
                    && !down
                    && completed.intValue() == version.steps(false).size()) {
                applied++;
                continue;
            }
            if (!(state.equals("RUNNING") || state.equals("FAILED")))
                throw new IllegalStateException("Invalid schema journal state: " + version.id());
            active = new Active(index, down, completed.intValue());
            if (down) applied++;
            gap = true;
        }
        if (!entries.isEmpty()) throw new IllegalStateException("Journal contains versions missing from this history");
        return new Checkpoint(applied, active);
    }

    private IllegalStateException interrupted(Active active) {
        String direction = active.down ? "down" : "up";
        return new IllegalStateException(
                "Version " + history.versions().get(active.index).id()
                        + " has unfinished " + direction + " actions; call history." + direction
                        + "(database) again to finish them before changing direction");
    }

    private void verifyStartingSchema(int applied) {
        if (applied == 0) {
            Set<String> managed = new HashSet<>();
            history.versions()
                    .forEach(version -> version.next().entities().forEach(entity -> managed.add(entity.name())));
            if (database.schema().inspect().entities().stream().anyMatch(entity -> managed.contains(entity.name())))
                throw new IllegalStateException("Existing managed storage has no recorded schema history");
        } else {
            SchemaHistory.Version version = history.versions().get(applied - 1);
            verify(version.next(), version.previous());
        }
    }

    private void execute(int index, boolean down, int start, boolean resuming) {
        SchemaHistory.Version version = history.versions().get(index);
        Predicate id = Predicate.eq("id", version.id());
        String direction = down ? "DOWN" : "UP";
        if (!resuming && !down && !records().exists(id)) {
            records()
                    .insert(EntityRecord.builder()
                            .set("id", version.id())
                            .set("checksum", version.checksum())
                            .set("state", "RUNNING")
                            .set("direction", direction)
                            .set("completed", 0)
                            .set("appliedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS))
                            .build());
        } else {
            records()
                    .update(
                            id,
                            Mutation.builder()
                                    .set("state", "RUNNING")
                                    .set("direction", direction)
                                    .set("completed", start)
                                    .build());
        }
        var steps = version.steps(down);
        int step = start;
        try {
            var context = new MigrationContext(database, version.previous(), version.next());
            for (; step < steps.size(); step++) {
                steps.get(step).action().accept(context);
                records()
                        .update(
                                id,
                                Mutation.builder().set("completed", step + 1).build());
            }
            verify(down ? version.previous() : version.next(), down ? version.next() : version.previous());
            records()
                    .update(
                            id,
                            Mutation.builder()
                                    .set("state", down ? "REVERTED" : "APPLIED")
                                    .build());
        } catch (RuntimeException | Error failure) {
            try {
                records().update(id, Mutation.builder().set("state", "FAILED").build());
            } catch (RuntimeException journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            if (failure instanceof Error error) throw error;
            String label = step < steps.size() ? steps.get(step).description() : "verify target schema";
            throw new IllegalStateException(
                    "Schema version " + version.id() + " " + direction.toLowerCase(Locale.ROOT) + " failed at " + label
                            + "; rerun the same operation to retry",
                    failure);
        }
    }

    private void verify(Schema target, Schema source) {
        if (!database.plan(target).changes().isEmpty())
            throw new IllegalStateException("Actions did not establish the target schema");
        Schema actual = database.schema().inspect();
        for (var previous : source.entities()) {
            var wanted = target.entities().stream()
                    .filter(entity -> entity.name().equals(previous.name()))
                    .findFirst();
            var stored = actual.entities().stream()
                    .filter(entity -> entity.name().equals(previous.name()))
                    .findFirst();
            if (stored.isEmpty()) continue;
            if (wanted.isEmpty()) throw new IllegalStateException("Actions did not remove entity " + previous.name());
            for (var field : previous.fields()) {
                if (wanted.get().fields().stream()
                                .noneMatch(value -> value.name().equals(field.name()))
                        && stored.get().fields().stream()
                                .anyMatch(value -> value.name().equals(field.name())))
                    throw new IllegalStateException(
                            "Actions did not remove field " + previous.name() + "." + field.name());
            }
            for (var index : previous.indexes()) {
                if (wanted.get().indexes().stream()
                                .noneMatch(value -> value.name().equals(index.name()))
                        && stored.get().indexes().stream()
                                .anyMatch(value -> value.name().equals(index.name())))
                    throw new IllegalStateException("Actions did not remove index " + index.name());
            }
        }
    }

    private void ensureStore() {
        Schema desired = metadataSchema();
        try {
            database.schema().ensure(desired);
        } catch (TavaException.Schema stale) {
            var actual = database.schema().inspect().entities().stream()
                    .filter(entity -> entity.name().equals(journal))
                    .findFirst();
            if (actual.isEmpty() || !metadataReady(desired.entity(journal), actual.get())) throw stale;
        }
    }

    private static boolean metadataReady(EntityDefinition desired, EntityDefinition actual) {
        return desired.fields().stream()
                .allMatch(field -> actual.fields().stream()
                        .anyMatch(stored -> stored.name().equals(field.name())
                                && stored.type().equals(field.type())
                                && stored.nullable() == field.nullable()
                                && stored.identity() == field.identity()));
    }

    private Lock acquire() {
        String owner = UUID.randomUUID().toString();
        var result = records()
                .insertResult(EntityRecord.builder()
                        .set("id", LOCK_ID)
                        .set("owner", owner)
                        .set("appliedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS))
                        .build());
        if (!result.inserted())
            throw new IllegalStateException("Schema history is locked by another runner in " + journal);
        return new Lock(owner);
    }

    private final class Lock implements AutoCloseable {
        private final String owner;

        private Lock(String owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (records().delete(Predicate.and(Predicate.eq("id", LOCK_ID), Predicate.eq("owner", owner))) != 1)
                throw new IllegalStateException("Schema history lock ownership was lost");
        }
    }

    private Records records() {
        return database.records(journal);
    }

    private Schema metadataSchema() {
        return Schema.builder()
                .entity(journal, entity -> {
                    entity.string("id").identity().length(255);
                    entity.string("owner").length(36);
                    entity.string("checksum").length(128);
                    entity.string("state").length(16);
                    entity.string("direction").length(8);
                    entity.integer("completed");
                    entity.field("appliedAt", FieldType.of(LogicalType.INSTANT))
                            .notNull()
                            .generated(GeneratedValue.NOW);
                })
                .build();
    }

    private record Checkpoint(int applied, Active active) {}

    private record Active(int index, boolean down, int completed) {}
}
