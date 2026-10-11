package eu.mikart.tava.migration;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.h2.H2;
import eu.mikart.tava.migration.Migration;
import eu.mikart.tava.migration.MigrationOperationResult;
import eu.mikart.tava.migration.MigrationResult;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    private Tava openTava(String namespace) {
        return Tava.open(H2.memory(namespace));
    }

    private String uniqueEntity(String prefix) {
        return (prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void migrationWithNoopUpAndDownRollback() {
        String entity = uniqueEntity("rollback");
        Migration create = Migration.builder(entity + "_create")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert initial", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "Initial").build()))
                .down("Delete initial", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(create);
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            Migration noopUpWithDown = Migration.builder(entity + "_noop_up_with_down")
                    .up("No-op up", ctx -> {})
                    .down("Delete all", ctx -> ctx.delete(entity, Predicate.all()))
                    .build();

            tava.migrations().apply(noopUpWithDown);
            // Up is no-op, so data remains
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            MigrationResult rollback = tava.migrations().rollback(noopUpWithDown);
            assertTrue(rollback.applied());
            // Down step deletes all
            assertEquals(0, tava.records(entity).find(Query.all()).items().size());
        }
    }

    @Test
    void migrationWithMultipleUpDownStepsInterleaved() {
        String entity = uniqueEntity("interleaved");
        Migration migration = Migration.builder(entity + "_interleaved")
                .upSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .up("Insert A", ctx -> ctx.insert(entity, EntityRecord.builder().set("id", "a").build()))
                .upSchema(Schema.builder().entity(entity, e -> e.string("name").field(100)).build())
                .up("Update A name", ctx -> ctx.update(entity, Predicate.eq("id", "a"),
                        Mutation.builder().set("name", "First").build()))
                .up("Insert B", ctx -> ctx.insert(entity, EntityRecord.builder().set("id", "b").build()))
                .upSchema(Schema.builder().entity(entity, e -> e.integer("score")).build())
                .down("Delete B", ctx -> ctx.delete(entity, Predicate.eq("id", "b")))
                .downSchema(Schema.builder().entity(entity, e -> e.integer("score")).build())
                .down("Clear A name", ctx -> ctx.update(entity, Predicate.eq("id", "a"),
                        Mutation.builder().set("name", null).build()))
                .downSchema(Schema.builder().entity(entity, e -> e.string("name").field(100)).build())
                .down("Delete A", ctx -> ctx.delete(entity, Predicate.eq("id", "a")))
                .downSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(migration);

            var records = tava.records(entity).find(Query.all()).items();
            assertEquals(2, records.size());
            // Both records exist, but only "a" has name set
            var recordA = records.stream().filter(r -> "a".equals(r.get("id"))).findFirst().orElseThrow();
            var recordB = records.stream().filter(r -> "b".equals(r.get("id"))).findFirst().orElseThrow();
            assertEquals("First", recordA.get("name"));
            assertNull(recordA.get("score"));
            assertNull(recordB.get("name"));
            assertNull(recordB.get("score"));

            MigrationResult rollback = tava.migrations().rollback(migration);
            assertTrue(rollback.applied());
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());
        }
    }

    @Test
    void migrationFailureLeavesPartialDataWithoutRecordingAppliedMigration() {
        String entity = uniqueEntity("failup");
        Migration migration = Migration.builder(entity + "_fail_up")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert first", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "First").build()))
                .up("Fail on second", ctx -> {
                    throw new RuntimeException("Simulated failure");
                })
                .up("Never reached", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "2").set("name", "Second").build()))
                .down("Delete first", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .downSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .build();

        try (Tava tava = openTava(entity)) {
            assertThrows(RuntimeException.class, () -> tava.migrations().apply(migration));

            assertTrue(tava.migrations().applied().isEmpty());
            // Partial changes are committed (no automatic transaction rollback)
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());
            assertEquals("First", tava.records(entity).find(Query.all()).items().getFirst().get("name"));
        }
    }

    @Test
    void migrationWithSchemaChangesAndDataMutationsInSameStep() {
        String entity = uniqueEntity("schema_data");
        Migration migration = Migration.builder(entity + "_schema_data")
                .up("Create table and seed data", ctx -> {
                    ctx.apply("Create entity", Schema.builder().entity(entity, e -> {
                        e.string("id").identity().field(36);
                        e.string("email").required().field(255);
                        e.integer("score").required();
                    }).build());

                    ctx.insert(entity, EntityRecord.builder()
                            .set("id", "1").set("email", "a@b.com").set("score", 10).build());
                    ctx.insert(entity, EntityRecord.builder()
                            .set("id", "2").set("email", "c@d.com").set("score", 20).build());
                })
                .down("Delete all data", ctx -> ctx.delete(entity, Predicate.all()))
                .build();

        try (Tava tava = openTava(entity)) {
            MigrationResult result = tava.migrations().apply(migration).getFirst();
            assertTrue(result.applied());
            assertEquals(2, result.operations().stream()
                    .filter(op -> op.description().startsWith("Insert into " + entity))
                    .mapToLong(MigrationOperationResult::affectedRows).sum());

            var records = tava.records(entity).find(Query.all()).items();
            assertEquals(2, records.size());

            MigrationResult rollback = tava.migrations().rollback(migration);
            assertTrue(rollback.applied());
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());
        }
    }

    @Test
    void migrationRollbackWhenDownStepFails() {
        String entity = uniqueEntity("faildown");
        Migration migration = Migration.builder(entity + "_fail_down")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "Test").build()))
                .down("Fail on rollback", ctx -> {
                    throw new RuntimeException("Rollback failure");
                })
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(migration);
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            assertThrows(RuntimeException.class, () -> tava.migrations().rollback(migration));

            assertTrue(tava.migrations().applied().contains(migration.id()));
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());
        }
    }

    @Test
    void multipleMigrationsAppliedInSequenceWithDependencies() {
        String entity = uniqueEntity("deps");
        Migration m1 = Migration.builder(entity + "_m1")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("base").required().field(50);
                }).build())
                .up("Insert base", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("base", "base1").build()))
                .down("Delete base", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .downSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("base").required().field(50);
                }).build())
                .build();

        Migration m2 = Migration.builder(entity + "_m2")
                .upSchema(Schema.builder().entity(entity, e -> e.string("extra").field(100)).build())
                .up("Update base", ctx -> ctx.update(entity, Predicate.eq("id", "1"),
                        Mutation.builder().set("extra", "extra1").build()))
                .down("Clear extra", ctx -> ctx.update(entity, Predicate.eq("id", "1"),
                        Mutation.builder().set("extra", null).build()))
                .downSchema(Schema.builder().entity(entity, e -> e.string("extra").field(100)).build())
                .build();

        Migration m3 = Migration.builder(entity + "_m3")
                .up("Add derived", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "2").set("base", "base2").set("extra", "extra2").build()))
                .down("Delete derived", ctx -> ctx.delete(entity, Predicate.eq("id", "2")))
                .build();

        try (Tava tava = openTava(entity)) {
            List<MigrationResult> results = tava.migrations().apply(m1, m2, m3);
            assertEquals(3, results.stream().filter(MigrationResult::applied).count());

            var records = tava.records(entity).find(Query.all()).items();
            assertEquals(2, records.size());

            MigrationResult r3 = tava.migrations().rollback(m3);
            assertTrue(r3.applied());
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            MigrationResult r2 = tava.migrations().rollback(m2);
            assertTrue(r2.applied());

            MigrationResult r1 = tava.migrations().rollback(m1);
            assertTrue(r1.applied());
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());
        }
    }

    @Test
    void migrationWithEmptyUpStepsThrows() {
        String entity = uniqueEntity("emptyup");
        assertThrows(IllegalArgumentException.class, () -> Migration.builder(entity + "_empty_up")
                .down("Delete", ctx -> ctx.delete(entity, Predicate.all()))
                .build());
    }

    @Test
    void migrationWithEmptyDownStepsAllowsIrreversible() {
        String entity = uniqueEntity("emptydown");
        Migration migration = Migration.builder(entity + "_empty_down")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "Test").build()))
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(migration);
            assertFalse(migration.reversible());

            assertThrows(IllegalArgumentException.class, () -> tava.migrations().rollback(migration));
        }
    }

    @Test
    void migrationAppliedRolledBackMultipleTimes() {
        String entity = uniqueEntity("multi");
        Migration migration = Migration.builder(entity + "_multi")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "Test").build()))
                .down("Delete", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .downSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(migration);
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            tava.migrations().rollback(migration);
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());

            tava.migrations().apply(migration);
            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            tava.migrations().rollback(migration);
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());

            List<String> applied = tava.migrations().applied();
            assertTrue(applied.isEmpty() || applied.stream().noneMatch(id -> id.equals(migration.id())));
        }
    }

    @Test
    void pendingMigrationsDetectionWithGaps() {
        String entity = uniqueEntity("gaps");
        Migration m1 = Migration.builder(entity + "_m1")
                .upSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .up("Insert 1", ctx -> ctx.insert(entity, EntityRecord.builder().set("id", "1").build()))
                .down("Delete 1", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .downSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .build();

        Migration m2 = Migration.builder(entity + "_m2")
                .up("Insert 2", ctx -> ctx.insert(entity, EntityRecord.builder().set("id", "2").build()))
                .down("Delete 2", ctx -> ctx.delete(entity, Predicate.eq("id", "2")))
                .build();

        Migration m3 = Migration.builder(entity + "_m3")
                .up("Insert 3", ctx -> ctx.insert(entity, EntityRecord.builder().set("id", "3").build()))
                .down("Delete 3", ctx -> ctx.delete(entity, Predicate.eq("id", "3")))
                .build();

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(m1);
            tava.migrations().apply(m3);

            List<Migration> pending = tava.migrations().pending(List.of(m1, m2, m3));
            assertEquals(1, pending.size());
            assertEquals(m2.id(), pending.getFirst().id());
        }
    }

    @Test
    void migrationIdCollisionHandling() {
        String entity = uniqueEntity("collision");
        Migration m1 = Migration.builder(entity + "_same")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert first", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "1").set("name", "First").build()))
                .down("Delete first", ctx -> ctx.delete(entity, Predicate.eq("id", "1")))
                .downSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .build();

        Migration m2 = Migration.builder(entity + "_same")
                .upSchema(Schema.builder().entity(entity, e -> e.string("extra").field(100)).build())
                .up("Insert second", ctx -> ctx.insert(entity, EntityRecord.builder()
                        .set("id", "2").set("name", "Second").set("extra", "extra").build()))
                .down("Delete second", ctx -> ctx.delete(entity, Predicate.eq("id", "2")))
                .downSchema(Schema.builder().entity(entity, e -> e.string("extra").field(100)).build())
                .build();

        try (Tava tava = openTava(entity)) {
            List<MigrationResult> first = tava.migrations().apply(m1, m2);
            assertEquals(2, first.size());
            assertTrue(first.getFirst().applied());
            assertFalse(first.get(1).applied());
            assertTrue(first.get(1).operations().isEmpty());

            assertEquals(1, tava.records(entity).find(Query.all()).items().size());

            List<MigrationResult> second = tava.migrations().apply(m2, m1);
            assertEquals(2, second.size());
            assertFalse(second.getFirst().applied());
            assertFalse(second.get(1).applied());
        }
    }

    @Test
    void rollbackNonAppliedMigrationReturnsNotApplied() {
        String entity = uniqueEntity("notapplied");
        Migration migration = Migration.builder(entity + "_not_applied")
                .upSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .down("Delete", ctx -> ctx.delete(entity, Predicate.all()))
                .downSchema(Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build())
                .build();

        try (Tava tava = openTava(entity)) {
            MigrationResult result = tava.migrations().rollback(migration);
            assertFalse(result.applied());
            assertTrue(result.operations().isEmpty());
        }
    }

    @Test
    void applyEmptyMigrationListReturnsEmpty() {
        String entity = uniqueEntity("emptylist");
        try (Tava tava = openTava(entity)) {
            List<MigrationResult> results = tava.migrations().apply(List.of());
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void migrationOperationsTrackSchemaChangesAndRowCounts() {
        String entity = uniqueEntity("ops");
        Migration migration = Migration.builder(entity + "_ops")
                .upSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .up("Insert multiple", ctx -> {
                    ctx.insert(entity, EntityRecord.builder().set("id", "1").set("name", "A").build());
                    ctx.insert(entity, EntityRecord.builder().set("id", "2").set("name", "B").build());
                    ctx.insert(entity, EntityRecord.builder().set("id", "3").set("name", "C").build());
                })
                .upSchema(Schema.builder().entity(entity, e -> e.integer("value")).build())
                .up("Update all", ctx -> ctx.forEach(entity, Query.all(), record ->
                        ctx.update(entity, Predicate.eq("id", record.get("id")),
                                Mutation.builder().set("value", 42).build())))
                .down("Clear values", ctx -> ctx.forEach(entity, Query.all(), record ->
                        ctx.update(entity, Predicate.eq("id", record.get("id")),
                                Mutation.builder().set("value", null).build())))
                .downSchema(Schema.builder().entity(entity, e -> e.integer("value")).build())
                .down("Delete all", ctx -> ctx.delete(entity, Predicate.all()))
                .downSchema(Schema.builder().entity(entity, e -> {
                    e.string("id").identity().field(36);
                    e.string("name").required().field(100);
                }).build())
                .build();

        try (Tava tava = openTava(entity)) {
            MigrationResult result = tava.migrations().apply(migration).getFirst();
            assertTrue(result.applied());

            long insertOps = result.operations().stream()
                    .filter(op -> op.description().startsWith("Insert into " + entity))
                    .count();
            assertEquals(3, insertOps);

            long updateOps = result.operations().stream()
                    .filter(op -> op.description().startsWith("Update " + entity))
                    .count();
            assertEquals(3, updateOps);

            MigrationResult rollback = tava.migrations().rollback(migration);
            assertTrue(rollback.applied());

            long deleteOps = rollback.operations().stream()
                    .filter(op -> op.description().startsWith("Delete from " + entity))
                    .count();
            assertEquals(1, deleteOps);
        }
    }

    @Test
    void migrationWithComplexNestedSchemaChanges() {
        String entity = uniqueEntity("nested");
        Migration migration = Migration.builder(entity + "_nested")
                .up("Complex schema change", ctx -> {
                    ctx.apply("Create base", Schema.builder().entity(entity, e -> {
                        e.string("id").identity().field(36);
                        e.string("data").required().field(1000);
                    }).build());

                    ctx.insert(entity, EntityRecord.builder()
                            .set("id", "1").set("data", "initial").build());

                    ctx.apply("Add index field", Schema.builder().entity(entity, e -> e.integer("idx")).build());

                    ctx.update(entity, Predicate.eq("id", "1"),
                            Mutation.builder().set("idx", 1).build());

                    ctx.apply("Add metadata", Schema.builder().entity(entity, e -> e.string("meta").field(50)).build());
                })
                .down("Reverse complex change", ctx -> ctx.delete(entity, Predicate.all()))
                .build();

        try (Tava tava = openTava(entity)) {
            MigrationResult result = tava.migrations().apply(migration).getFirst();
            assertTrue(result.applied());

            var records = tava.records(entity).find(Query.all()).items();
            assertEquals(1, records.size());
            assertEquals("initial", records.getFirst().get("data"));
            assertEquals(1, records.getFirst().get("idx"));
            assertNull(records.getFirst().get("meta"));

            MigrationResult rollback = tava.migrations().rollback(migration);
            assertTrue(rollback.applied());
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());
        }
    }

    @Test
    void migrationAppliedIdsOrderPreserved() {
        String entity = uniqueEntity("order");
        Migration m1 = Migration.up(entity + "_1", Schema.builder().entity(entity, e -> e.string("id").identity().field(36)).build());
        Migration m2 = Migration.up(entity + "_2", Schema.builder().entity(entity, e -> e.string("name").field(100)).build());
        Migration m3 = Migration.up(entity + "_3", Schema.builder().entity(entity, e -> e.integer("value")).build());

        try (Tava tava = openTava(entity)) {
            tava.migrations().apply(m1, m2, m3);
            List<String> applied = tava.migrations().applied();
            assertEquals(List.of(m1.id(), m2.id(), m3.id()), applied);
        }
    }
}
