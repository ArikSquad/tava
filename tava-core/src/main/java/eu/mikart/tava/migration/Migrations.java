package eu.mikart.tava.migration;

import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.FieldType;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.LogicalType;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.schema.plan.SchemaChange;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Applies, rolls back, and resumes durable migrations through a Tava adapter. */
public final class Migrations {
    /** Default entity used to store migration state and locks. */
    public static final String DEFAULT_ENTITY = "_tava_migrations";

    private static final String LOCK_ID = "__tava_migration_lock__";

    private static <E extends Enum<E>> E journalValue(EntityRecord record, String field, Class<E> type) {
        Object value = record.get(field);
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.toString());
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid migration " + field + ": " + value, failure);
        }
    }

    private final Tava tava;
    private final String entity;
    private final ApplyOptions options;

    /**
     * Creates a migration manager using the default journal entity and safe schema changes.
     *
     * @param tava Tava facade used for journal and migration operations
     */
    public Migrations(final Tava tava) {
        this(tava, DEFAULT_ENTITY, ApplyOptions.safe());
    }

    /**
     * Creates a migration manager with an explicit journal entity and schema policy.
     *
     * @param tava Tava facade used for journal and migration operations
     * @param entity entity used to store migration state and locks
     * @param options policy for schema reconciliation
     */
    public Migrations(final Tava tava, final String entity, final ApplyOptions options) {
        if (entity == null || entity.isBlank()) throw new IllegalArgumentException("migration entity is required");
        this.tava = Objects.requireNonNull(tava, "tava is required");
        this.entity = entity;
        this.options = options == null ? ApplyOptions.safe() : options;
    }

    /**
     * Applies the supplied migrations in order.
     *
     * @param migrations migrations to apply
     * @return results in application order
     */
    public List<MigrationResult> apply(final Migration... migrations) {
        return apply(Arrays.asList(migrations));
    }

    /**
     * Applies the supplied migrations in order.
     *
     * @param migrations migrations to apply
     * @return results in application order
     */
    public List<MigrationResult> apply(final List<Migration> migrations) {
        ensureStore();
        try (MigrationLock ignored = acquireLock()) {
            return applyLocked(migrations);
        }
    }

    private List<MigrationResult> applyLocked(final List<Migration> migrations) {
        final Set<String> applied = appliedIds();
        final List<MigrationResult> results = new ArrayList<>();
        for (final Migration migration : migrations) {
            if (LOCK_ID.equals(migration.id())) throw new IllegalArgumentException("Reserved migration id");
            if (applied.contains(migration.id())) {
                EntityRecord history =
                        records().findOne(Predicate.eq("id", migration.id())).orElseThrow();
                verifyChecksum(migration, history);
                if (journalValue(history, "direction", MigrationStatus.Direction.class)
                        == MigrationStatus.Direction.DOWN)
                    throw new IllegalStateException("Rollback is interrupted; inspect status() and use resume()");
                results.add(new MigrationResult(migration.id(), false, List.of(), List.of()));
                continue;
            }
            if (records().findOne(Predicate.eq("id", migration.id())).isPresent())
                throw new IllegalStateException(
                        "Migration " + migration.id() + " is interrupted; inspect status() and use resume()");
            records()
                    .insert(EntityRecord.builder()
                            .set("id", migration.id())
                            .set("appliedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS))
                            .set("checksum", migration.checksum())
                            .set("direction", MigrationStatus.Direction.UP.name())
                            .set("state", MigrationStatus.State.RUNNING.name())
                            .set("completed", 0)
                            .build());
            final List<MigrationOperationResult> operations = runJournaled(migration, migration.up(), 0, false);
            applied.add(migration.id());
            results.add(result(migration.id(), true, operations));
        }
        return results;
    }

    /**
     * Rolls back a migration that has been applied.
     *
     * @param migration migration definition containing rollback steps
     * @return rollback result
     */
    public MigrationResult rollback(final Migration migration) {
        if (LOCK_ID.equals(migration.id())) throw new IllegalArgumentException("Reserved migration id");
        if (!migration.reversible()) {
            throw new IllegalArgumentException("Migration " + migration.id() + " does not define a down schema");
        }
        ensureStore();
        try (MigrationLock ignored = acquireLock()) {
            return rollbackLocked(migration);
        }
    }

    private MigrationResult rollbackLocked(final Migration migration) {
        if (!appliedIds().contains(migration.id())) {
            return new MigrationResult(migration.id(), false, List.of(), List.of());
        }
        EntityRecord history =
                records().findOne(Predicate.eq("id", migration.id())).orElseThrow();
        verifyChecksum(migration, history);
        if (journalValue(history, "direction", MigrationStatus.Direction.class) == MigrationStatus.Direction.DOWN)
            throw new IllegalStateException("Rollback is interrupted; inspect status() and use resume()");
        records()
                .update(
                        Predicate.eq("id", migration.id()),
                        Mutation.builder()
                                .set("direction", MigrationStatus.Direction.DOWN.name())
                                .set("state", MigrationStatus.State.RUNNING.name())
                                .set("completed", 0)
                                .build());
        final List<MigrationOperationResult> operations = runJournaled(migration, migration.down(), 0, true);
        return result(migration.id(), true, operations);
    }

    /**
     * Returns the identifiers of migrations that are currently applied.
     *
     * @return applied migration identifiers
     */
    public List<String> applied() {
        ensureStore();
        return List.copyOf(appliedIds());
    }

    /**
     * Returns migrations whose identifiers are not currently applied.
     *
     * @param migrations migration definitions to inspect
     * @return definitions that remain pending
     */
    public List<Migration> pending(final List<Migration> migrations) {
        ensureStore();
        final Set<String> applied = appliedIds();
        return migrations.stream()
                .filter(migration -> !applied.contains(migration.id()))
                .toList();
    }

    /**
     * Choice for handling the step that was active when an interrupted migration stopped.
     *
     * @since 1.3.0
     */
    public enum Recovery {
        /**
         * Rerun the step that was active when execution stopped.
         *
         * @since 1.3.0
         */
        RETRY_INCOMPLETE_STEP,
        /**
         * Treat the active step as complete and continue with the next step.
         *
         * @since 1.3.0
         */
        SKIP_INCOMPLETE_STEP
    }

    /**
     * Returns the typed durable journal entry for a migration.
     *
     * @param migrationId migration identifier
     * @return migration state, direction, completed step count, and optional checksum, if present
     * @throws IllegalArgumentException if the identifier is blank or reserved
     * @throws IllegalStateException if the stored journal entry is invalid
     * @since 1.3.0
     */
    public Optional<MigrationStatus> status(String migrationId) {
        if (migrationId == null || migrationId.isBlank())
            throw new IllegalArgumentException("migration id is required");
        if (LOCK_ID.equals(migrationId)) throw new IllegalArgumentException("Reserved migration id");
        ensureStore();
        return journal(migrationId).map(Migrations::status);
    }

    /**
     * Resumes an interrupted migration. Recovery must account for any side effects committed by the incomplete step.
     *
     * @param migration migration definition with the same checksum as the journal entry
     * @param recovery policy for the incomplete step
     * @return result for the resumed migration
     * @throws IllegalArgumentException if rollback cannot be resumed without down steps
     * @throws IllegalStateException if no interrupted migration exists, its journal is invalid, or its checksum differs
     *     from the supplied migration
     * @throws NullPointerException if {@code migration} or {@code recovery} is {@code null}
     * @since 1.3.0
     */
    public MigrationResult resume(Migration migration, Recovery recovery) {
        Objects.requireNonNull(recovery, "recovery");
        ensureStore();
        try (MigrationLock ignored = acquireLock()) {
            EntityRecord journal =
                    journal(migration.id()).orElseThrow(() -> new IllegalStateException("No migration journal"));
            verifyChecksum(migration, journal);
            MigrationStatus.State state = journalValue(journal, "state", MigrationStatus.State.class);
            if (state != MigrationStatus.State.RUNNING && state != MigrationStatus.State.FAILED)
                throw new IllegalStateException("Migration is not interrupted");
            MigrationStatus.Direction direction = journalValue(journal, "direction", MigrationStatus.Direction.class);
            if (direction == null) throw new IllegalStateException("Migration journal has no direction");
            boolean down = direction == MigrationStatus.Direction.DOWN;
            if (down && !migration.reversible())
                throw new IllegalArgumentException("Cannot resume rollback without its down steps");
            List<MigrationStep> steps = down ? migration.down() : migration.up();
            if (!(journal.get("completed") instanceof Number progress))
                throw new IllegalStateException("Migration journal has no valid progress");
            int completed = progress.intValue();
            if (completed < 0 || completed > steps.size())
                throw new IllegalStateException("Migration journal progress exceeds its steps");
            if (recovery == Recovery.SKIP_INCOMPLETE_STEP && completed < steps.size()) completed++;
            records()
                    .update(
                            Predicate.eq("id", migration.id()),
                            Mutation.builder()
                                    .set("state", MigrationStatus.State.RUNNING.name())
                                    .set("completed", completed)
                                    .build());
            return result(migration.id(), true, runJournaled(migration, steps, completed, down));
        }
    }

    private Optional<EntityRecord> journal(String migrationId) {
        return records().findOne(Predicate.eq("id", migrationId));
    }

    private static MigrationStatus status(EntityRecord journal) {
        String id = Objects.toString(journal.get("id"), null);
        MigrationStatus.State state = journalValue(journal, "state", MigrationStatus.State.class);
        MigrationStatus.Direction direction = journalValue(journal, "direction", MigrationStatus.Direction.class);
        if (state == null && direction == null) {
            state = MigrationStatus.State.APPLIED;
            direction = MigrationStatus.Direction.UP;
        } else if (state == null || direction == null) {
            throw new IllegalStateException("Migration journal has incomplete state");
        }

        Object rawCompleted = journal.get("completed");
        int completed = 0;
        if (rawCompleted instanceof Number value) {
            long count = value.longValue();
            if (count < 0 || count > Integer.MAX_VALUE || value.doubleValue() != count)
                throw new IllegalStateException("Migration journal has invalid progress");
            completed = (int) count;
        } else if (state != MigrationStatus.State.APPLIED) {
            throw new IllegalStateException("Migration journal has no valid progress");
        }

        Object rawChecksum = journal.get("checksum");
        if (rawChecksum != null && !(rawChecksum instanceof String))
            throw new IllegalStateException("Migration journal has an invalid checksum");
        return new MigrationStatus(id, state, direction, completed, (String) rawChecksum);
    }

    private void verifyChecksum(Migration migration, EntityRecord history) {
        if (migration.checksum() != null && !migration.checksum().equals(history.get("checksum")))
            throw new IllegalStateException("Migration checksum differs for " + migration.id());
    }

    private List<MigrationOperationResult> runJournaled(
            Migration migration, List<MigrationStep> steps, int start, boolean down) {
        final MigrationContext context = new MigrationContext(tava, options);
        Predicate id = Predicate.eq("id", migration.id());
        try {
            for (int index = start; index < steps.size(); index++) {
                steps.get(index).run(context);
                records()
                        .update(
                                id,
                                Mutation.builder().set("completed", index + 1).build());
            }
            if (down) records().delete(id);
            else
                records()
                        .update(
                                id,
                                Mutation.builder()
                                        .set("state", MigrationStatus.State.APPLIED.name())
                                        .build());
            return context.operations();
        } catch (RuntimeException | Error failure) {
            try {
                records()
                        .update(
                                id,
                                Mutation.builder()
                                        .set("state", MigrationStatus.State.FAILED.name())
                                        .build());
            } catch (RuntimeException journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            throw failure;
        }
    }

    private static MigrationResult result(
            final String id, final boolean applied, final List<MigrationOperationResult> operations) {
        final List<SchemaChange> changes = operations.stream()
                .flatMap(operation -> operation.changes().stream())
                .toList();
        return new MigrationResult(id, applied, changes, operations);
    }

    private void ensureStore() {
        Schema desired = metadataSchema();
        try {
            tava.plan(desired).apply(options);
        } catch (TavaException.Schema stalePlan) {
            try {
                EntityDefinition actual = tava.schema().inspect().entities().stream()
                        .filter(value -> value.name().equals(entity))
                        .findFirst()
                        .orElse(null);
                if (metadataStoreReady(desired.entity(entity), actual)) return;
            } catch (RuntimeException inspectionFailure) {
                stalePlan.addSuppressed(inspectionFailure);
            }
            throw stalePlan;
        }
    }

    private static boolean metadataStoreReady(EntityDefinition desired, EntityDefinition actual) {
        if (actual == null) return false;
        for (var field : desired.fields()) {
            var existing = actual.fields().stream()
                    .filter(value -> value.name().equals(field.name()))
                    .findFirst()
                    .orElse(null);
            if (existing == null
                    || !existing.type().equals(field.type())
                    || existing.nullable() != field.nullable()
                    || existing.identity() != field.identity()) return false;
        }
        return true;
    }

    private Set<String> appliedIds() {
        final Set<String> ids = new LinkedHashSet<>();
        String cursor = null;
        do {
            Query.Builder query =
                    Query.builder().project("id", "state", "direction").cursor(cursor);
            if (tava.capabilities().supports(Feature.SORTING)) query.sort(Sort.asc("id"));
            final var page = records().find(query.build());
            page.items().forEach(record -> {
                String id = String.valueOf(record.get("id"));
                MigrationStatus.State state = journalValue(record, "state", MigrationStatus.State.class);
                MigrationStatus.Direction direction =
                        journalValue(record, "direction", MigrationStatus.Direction.class);
                if (!LOCK_ID.equals(id)
                        && (state == null
                                || state == MigrationStatus.State.APPLIED
                                || direction == MigrationStatus.Direction.DOWN)) ids.add(id);
            });
            cursor = page.nextCursor();
        } while (cursor != null);
        return ids;
    }

    /**
     * Returns the current lock owner for operator-assisted recovery.
     *
     * @return lock owner token, if currently held
     * @throws IllegalStateException if the stored lock has no valid owner token
     * @since 1.3.0
     */
    public Optional<MigrationLockInfo> lock() {
        ensureStore();
        return records().findOne(Predicate.eq("id", LOCK_ID)).map(record -> {
            Object owner = record.get("owner");
            if (!(owner instanceof String token) || token.isBlank())
                throw new IllegalStateException("Migration lock has no valid owner token");
            return new MigrationLockInfo(token);
        });
    }

    /**
     * Releases a migration lock only after verifying that its owner has stopped. Locks never expire automatically.
     *
     * @param lock migration lock information returned by {@link #lock()}
     * @return {@code true} if this owner held and released the lock
     * @throws NullPointerException if {@code lock} is {@code null}
     * @since 1.3.0
     */
    public boolean releaseLock(final MigrationLockInfo lock) {
        Objects.requireNonNull(lock, "lock");
        ensureStore();
        return records().delete(lockPredicate(lock.owner())) == 1;
    }

    private Predicate lockPredicate(String owner) {
        return Predicate.and(Predicate.eq("id", LOCK_ID), Predicate.eq("owner", owner));
    }

    private MigrationLock acquireLock() {
        String owner = UUID.randomUUID().toString();
        final var result = records()
                .insertResult(EntityRecord.builder()
                        .set("id", LOCK_ID)
                        .set("owner", owner)
                        .set("appliedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS))
                        .build());
        if (!result.inserted())
            throw new IllegalStateException(
                    "Migration lock is held; inspect lock() and verify its owner has stopped before releaseLock(owner)");
        return new MigrationLock(owner);
    }

    private final class MigrationLock implements AutoCloseable {
        private final String owner;

        private MigrationLock(String owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (records().delete(lockPredicate(owner)) != 1)
                throw new IllegalStateException("Migration lock ownership was lost");
        }
    }

    private Records records() {
        return tava.records(entity);
    }

    private Schema metadataSchema() {
        return Schema.builder().entity(metadataEntity()).build();
    }

    private EntityDefinition metadataEntity() {
        return Schema.builder()
                .entity(entity, value -> {
                    value.string("id").identity().length(255);
                    value.string("owner").length(36);
                    value.string("checksum").length(128);
                    value.string("state").length(16);
                    value.string("direction").length(8);
                    value.integer("completed");
                    value.field("appliedAt", FieldType.of(LogicalType.INSTANT))
                            .notNull()
                            .generated(GeneratedValue.NOW);
                })
                .build()
                .entity(entity);
    }
}
