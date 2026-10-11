package eu.mikart.tava.transfer;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import eu.mikart.tava.spi.SchemaManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataTransferTest {
    @Test
    void stopsWhenSourceRepeatsCursor() {
        EntityRecord record = EntityRecord.builder().set("id", 1).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record), "same-cursor");
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(1, report.transferred().get("things"));
            assertEquals(TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
        }
    }

    @Test
    void reportsErrorWhenInsertFails() {
        EntityRecord record = EntityRecord.builder().set("id", 1).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override public EntityRecord insert(String entity, EntityRecord record) {
                throw new RuntimeException("insert failed");
            }
        };
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(0, report.transferred().get("things"));
            assertEquals(1, report.issues().size());
            assertEquals(TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
            assertEquals("insert failed", report.issues().getFirst().detail());
        }
    }

    @Test
    void reportsErrorWhenSourceFindFails() {
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                throw new RuntimeException("source find failed");
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(0, report.transferred().get("things"));
            assertEquals(1, report.issues().size());
            assertEquals(TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
            assertEquals("source find failed", report.issues().getFirst().detail());
        }
    }

    @Test
    void progressCallbackCalledForEachRecord() {
        List<String> progressEvents = new ArrayList<>();
        EntityRecord record1 = EntityRecord.builder().set("id", 1).build();
        EntityRecord record2 = EntityRecord.builder().set("id", 2).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                if (query.cursor() == null) {
                    return new Page<>(List.of(record1, record2), "next-cursor");
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, (entity, count) -> {
            progressEvents.add(entity + ":" + count);
        });

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(2, report.transferred().get("things"));
            assertEquals(List.of("things:1", "things:2"), progressEvents);
        }
    }

    @Test
    void progressCallbackCalledAcrossMultipleBatches() {
        List<String> progressEvents = new ArrayList<>();
        EntityRecord record1 = EntityRecord.builder().set("id", 1).build();
        EntityRecord record2 = EntityRecord.builder().set("id", 2).build();
        EntityRecord record3 = EntityRecord.builder().set("id", 3).build();
        EntityRecord record4 = EntityRecord.builder().set("id", 4).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                if (query.cursor() == null) {
                    return new Page<>(List.of(record1, record2), "cursor1");
                } else if ("cursor1".equals(query.cursor())) {
                    return new Page<>(List.of(record3, record4), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(2, false, false, null, (entity, count) -> {
            progressEvents.add(entity + ":" + count);
        });

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(4, report.transferred().get("things"));
            assertEquals(List.of("things:1", "things:2", "things:3", "things:4"), progressEvents);
        }
    }

    @Test
    void transformFunctionAppliedToRecords() {
        List<EntityRecord> insertedRecords = new ArrayList<>();
        EntityRecord record1 = EntityRecord.builder().set("id", 1).set("name", "alice").build();
        EntityRecord record2 = EntityRecord.builder().set("id", 2).set("name", "bob").build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record1, record2), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override public EntityRecord insert(String entity, EntityRecord record) {
                insertedRecords.add(record);
                return record;
            }
        };
        Schema schema = Schema.builder().entity("things", e -> e.integer("id").add().string("name")).build();
        UnaryOperator<EntityRecord> transform = r -> EntityRecord.builder()
                .set("id", r.get("id"))
                .set("name", r.get("name").toString().toUpperCase())
                .build();
        TransferOptions options = new TransferOptions(10, false, false, transform, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(2, report.transferred().get("things"));
            assertEquals("ALICE", insertedRecords.get(0).get("name"));
            assertEquals("BOB", insertedRecords.get(1).get("name"));
        }
    }

    @Test
    void allowsLossySchemaChangesWhenEnabled() {
        AtomicBoolean schemaPlanApplied = new AtomicBoolean();
        EntityStore sourceStore = new StubStore();
        EntityStore targetStore = new StubStore();
        SchemaPlan lossyPlan = new SchemaPlan(
                List.of(new SchemaChange("Narrow a field", ChangeRisk.LOSSY, null)),
                () -> schemaPlanApplied.set(true));
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        TransferOptions options = new TransferOptions(10, true, true, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
             Tava target = Tava.open(adapter(targetStore, schemaManager(lossyPlan)))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertTrue(schemaPlanApplied.get());
        }
    }

    @Test
    void rejectsLossySchemaChangesWhenDisabled() {
        AtomicBoolean schemaPlanApplied = new AtomicBoolean();
        EntityStore sourceStore = new StubStore();
        EntityStore targetStore = new StubStore();
        SchemaPlan lossyPlan = new SchemaPlan(
                List.of(new SchemaChange("Narrow a field", ChangeRisk.LOSSY, null)),
                () -> schemaPlanApplied.set(true));
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, true, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
             Tava target = Tava.open(adapter(targetStore, schemaManager(lossyPlan)))) {
            assertThrows(IllegalStateException.class, () -> DataTransfer.copy(source, target, schema, options));
            assertFalse(schemaPlanApplied.get());
        }
    }

    @Test
    void batchSizeControlsPageSize() {
        List<Integer> batchSizes = new ArrayList<>();
        EntityRecord record1 = EntityRecord.builder().set("id", 1).build();
        EntityRecord record2 = EntityRecord.builder().set("id", 2).build();
        EntityRecord record3 = EntityRecord.builder().set("id", 3).build();
        EntityRecord record4 = EntityRecord.builder().set("id", 4).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                batchSizes.add(query.limit());
                if (query.cursor() == null) {
                    return new Page<>(List.of(record1, record2), "cursor1");
                } else if ("cursor1".equals(query.cursor())) {
                    return new Page<>(List.of(record3, record4), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        TransferOptions options = new TransferOptions(2, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(4, report.transferred().get("things"));
            assertEquals(List.of(2, 2), batchSizes);
        }
    }

    @Test
    void transfersMultipleEntities() {
        List<String> insertedEntities = new ArrayList<>();
        EntityRecord user1 = EntityRecord.builder().set("id", 1).set("name", "alice").build();
        EntityRecord user2 = EntityRecord.builder().set("id", 2).set("name", "bob").build();
        EntityRecord post1 = EntityRecord.builder().set("id", 1).set("userId", 1).set("title", "hello").build();
        EntityRecord post2 = EntityRecord.builder().set("id", 2).set("userId", 2).set("title", "world").build();

        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                if ("users".equals(entity)) {
                    return new Page<>(List.of(user1, user2), null);
                } else if ("posts".equals(entity)) {
                    return new Page<>(List.of(post1, post2), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override public EntityRecord insert(String entity, EntityRecord record) {
                insertedEntities.add(entity);
                return record;
            }
        };
        Schema schema = Schema.builder()
                .entity("users", e -> e.integer("id").add().string("name"))
                .entity("posts", e -> e.integer("id").add().integer("userId").add().string("title"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(2, report.transferred().get("users"));
            assertEquals(2, report.transferred().get("posts"));
            assertEquals(4, insertedEntities.size());
            assertEquals(List.of("users", "users", "posts", "posts"), insertedEntities);
        }
    }

    @Test
    void transfersMultipleEntitiesWithProgressPerEntity() {
        List<String> progressEvents = new ArrayList<>();
        EntityRecord user1 = EntityRecord.builder().set("id", 1).build();
        EntityRecord post1 = EntityRecord.builder().set("id", 1).build();

        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                if ("users".equals(entity)) {
                    return new Page<>(List.of(user1), null);
                } else if ("posts".equals(entity)) {
                    return new Page<>(List.of(post1), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder()
                .entity("users", e -> e.integer("id"))
                .entity("posts", e -> e.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, (entity, count) -> {
            progressEvents.add(entity + ":" + count);
        });

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(1, report.transferred().get("users"));
            assertEquals(1, report.transferred().get("posts"));
            assertEquals(List.of("users:1", "posts:1"), progressEvents);
        }
    }

    @Test
    void emptyEntityTransferredWithZeroCount() {
        EntityStore sourceStore = new StubStore();
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("empty", e -> e.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(0, report.transferred().get("empty"));
            assertTrue(report.issues().isEmpty());
        }
    }

    @Test
    void schemaNotAppliedWhenApplySchemaFalse() {
        AtomicBoolean schemaPlanApplied = new AtomicBoolean();
        EntityStore sourceStore = new StubStore();
        EntityStore targetStore = new StubStore();
        SchemaPlan schemaPlan = new SchemaPlan(List.of(), () -> schemaPlanApplied.set(true));
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
             Tava target = Tava.open(adapter(targetStore, schemaManager(schemaPlan)))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertFalse(schemaPlanApplied.get());
        }
    }

    private static Adapter adapter(EntityStore store) {
        return adapter(store, null);
    }

    private static Adapter adapter(EntityStore store, SchemaManager schemaManager) {
        return new Adapter() {
            public String name() { return "stub"; }
            public eu.mikart.tava.capability.Capabilities capabilities() { throw new UnsupportedOperationException(); }
            public SchemaManager schemas() {
                if (schemaManager == null) throw new UnsupportedOperationException();
                return schemaManager;
            }
            public EntityStore entities() { return store; }
            public eu.mikart.tava.spi.NativeAccess nativeAccess() { throw new UnsupportedOperationException(); }
            public void close() { }
        };
    }

    private static SchemaManager schemaManager(SchemaPlan schemaPlan) {
        return new SchemaManager() {
            @Override public Schema inspect() { return Schema.builder().build(); }
            @Override public SchemaPlan plan(Schema desired) { return schemaPlan; }
            @Override public SchemaPlan plan(Schema desired, SchemaRenames renames) { return schemaPlan; }
        };
    }

    private static class StubStore implements EntityStore {
        public EntityRecord insert(String entity, EntityRecord record) { return record; }
        public Page<EntityRecord> find(String entity, Query query) { return new Page<>(List.of(), null); }
        public long update(String entity, Predicate predicate, Mutation mutation) { return 0; }
        public long delete(String entity, Predicate predicate) { return 0; }
    }
}
