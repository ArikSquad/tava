package eu.mikart.tava.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.h2.H2;
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
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataTransferTest {
    @Test
    void namedOptionsPreserveDefaultsAndValidatePageSize() {
        var options = TransferOptions.builder().build();
        assertEquals(500, options.batchSize());
        assertFalse(options.allowLossy());
        assertTrue(options.applySchema());
        assertThrows(
                IllegalArgumentException.class,
                () -> TransferOptions.builder().batchSize(0).build());
        var custom = TransferOptions.builder()
                .batchSize(42)
                .allowLossy(true)
                .applySchema(false)
                .build();
        assertEquals(42, custom.batchSize());
        assertTrue(custom.allowLossy());
        assertFalse(custom.applySchema());
    }

    @Test
    void readsLegacyVersionOneCheckpoints(@TempDir Path directory) throws Exception {
        var store = new FileTransferCheckpointStore(directory);
        var checkpoint = new TransferCheckpoint(
                "legacy", "source", "target", "digest", 10, 0, null, Map.of("things", 2L), false);
        try (var lease = store.acquire("legacy")) {
            lease.save(checkpoint);
        }
        Path file;
        try (var files = Files.list(directory)) {
            file = files.filter(path -> path.toString().endsWith(".checkpoint"))
                    .findFirst()
                    .orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(1);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - Integer.BYTES));
        try (var lease = store.acquire("legacy")) {
            assertEquals(checkpoint, lease.load().orElseThrow());
        }
    }

    @Test
    void cancellationAndDeadlineStopBeforeReading() {
        AtomicInteger reads = new AtomicInteger();
        StubStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                reads.incrementAndGet();
                return new Page<>(List.of(), null);
            }
        };
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(new StubStore()))) {
            for (TransferControl control : List.of(
                    new TransferControl(Duration.ZERO, () -> true, 4, 4),
                    new TransferControl(Duration.ofNanos(1), () -> false, 4, 4))) {
                TransferReport report = DataTransfer.copy(
                        source, target, schema, new TransferOptions(10, false, false, null, null), control);
                assertFalse(report.complete());
                assertEquals(
                        TransferIssue.Severity.ERROR, report.issues().getLast().severity());
            }
            assertEquals(0, reads.get());
        }
    }

    @Test
    void boundsCursorMemoryAndRetainsTerminalError() {
        AtomicInteger pages = new AtomicInteger();
        StubStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                int page = pages.incrementAndGet();
                return new Page<>(List.of(EntityRecord.of(Map.of("id", page))), "page-" + page);
            }
        };
        Schema schema = Schema.builder().entity("things", e -> e.integer("id")).build();
        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(new StubStore()))) {
            TransferReport report = DataTransfer.copy(
                    source,
                    target,
                    schema,
                    new TransferOptions(1, false, false, null, null),
                    new TransferControl(Duration.ZERO, () -> false, 2, 1));
            assertFalse(report.complete());
            assertEquals(1L, report.transferred().get("things"));
            assertTrue(report.issues().getLast().detail().contains("tracking limit"));
        }
    }

    @Test
    void rollsBackFailedBulkWritesBeforeIndividualRetryWithoutRepeatingTransforms() {
        Schema schema = Schema.builder()
                .entity("things", e -> e.integer("id").identity())
                .build();
        AtomicInteger transforms = new AtomicInteger();
        try (Tava source = Tava.open(H2.memory("transfer_source_" + UUID.randomUUID()));
                Tava target = Tava.open(H2.memory("transfer_target_" + UUID.randomUUID()))) {
            source.plan(schema).apply();
            target.plan(schema).apply();
            source.records("things")
                    .insert(List.of(EntityRecord.of(Map.of("id", 1)), EntityRecord.of(Map.of("id", 2))));
            target.records("things").insert(EntityRecord.of(Map.of("id", 2)));
            TransferReport report = DataTransfer.copy(
                    source,
                    target,
                    schema,
                    new TransferOptions(
                            10,
                            false,
                            false,
                            record -> {
                                transforms.incrementAndGet();
                                return record;
                            },
                            null));
            assertFalse(report.complete());
            assertEquals(1L, report.transferred().get("things"));
            assertEquals(2, target.records("things").find(Query.all()).items().size());
            assertEquals(2, transforms.get());
        }
    }

    @Test
    void persistsDeduplicatedWarningsInFileCheckpoints(@TempDir Path directory) {
        Schema sourceSchema = Schema.builder()
                .entity("things", e -> {
                    e.integer("id").identity();
                    e.string("omitted");
                })
                .build();
        Schema targetSchema = Schema.builder()
                .entity("things", e -> e.integer("id").identity())
                .build();
        var checkpoints = new FileTransferCheckpointStore(directory);
        try (Tava source = Tava.open(H2.memory("warning_source_" + UUID.randomUUID()));
                Tava target = Tava.open(H2.memory("warning_target_" + UUID.randomUUID()))) {
            source.plan(sourceSchema).apply();
            source.records("things")
                    .insert(List.of(
                            EntityRecord.of(Map.of("id", 1, "omitted", "a")),
                            EntityRecord.of(Map.of("id", 2, "omitted", "b"))));
            TransferOptions options = new TransferOptions(1, true, true, null, null);
            TransferReport report =
                    ResumableTransfer.copy(source, target, targetSchema, options, "warnings", checkpoints);
            assertTrue(report.complete());
            assertEquals(1, report.issues().size());
            TransferReport resumed =
                    ResumableTransfer.copy(source, target, targetSchema, options, "warnings", checkpoints);
            assertEquals(report, resumed);
        }
    }

    @Test
    void boundsAndDeduplicatesDiagnosticsWithoutDroppingFailure() {
        List<TransferIssue> issues = new TransferIssues(3);
        var first = new TransferIssue("things", "one", TransferIssue.Severity.LOSSY, "omitted");
        issues.add(first);
        issues.add(first);
        issues.add(new TransferIssue("things", "two", TransferIssue.Severity.LOSSY, "omitted"));
        issues.add(new TransferIssue("things", "three", TransferIssue.Severity.LOSSY, "omitted"));
        assertEquals(3, issues.size());
        assertEquals(TransferIssue.Severity.WARNING, issues.getLast().severity());
        issues.add(new TransferIssue("things", null, TransferIssue.Severity.ERROR, "failed"));
        assertEquals(3, issues.size());
        assertEquals("failed", issues.getLast().detail());
    }

    @Test
    void reconcilesShortBulkWriteBeforeSavingCheckpoint() {
        EntityRecord first =
                EntityRecord.builder().set("id", 1).set("name", "one").build();
        EntityRecord second =
                EntityRecord.builder().set("id", 2).set("name", "two").build();
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(first, second), null);
            }
        };
        PartialBulkStore targetStore = new PartialBulkStore();
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        Schema schema = Schema.builder()
                .entity("things", entity -> {
                    entity.integer("id").identity().add().string("name").add();
                })
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter("source", sourceStore));
                Tava target = Tava.open(adapter("target", targetStore))) {
            TransferReport report = ResumableTransfer.copy(source, target, schema, options, "transfer-1", checkpoints);

            assertTrue(report.complete());
            assertEquals(2, report.transferred().get("things"));
            assertEquals(List.of(first, second), new ArrayList<>(targetStore.records.values()));
            assertTrue(checkpoints.checkpoint.complete());

            assertTrue(ResumableTransfer.copy(source, target, schema, options, "transfer-1", checkpoints)
                    .complete());
            TransferCheckpoint completed = checkpoints.checkpoint;
            checkpoints.checkpoint = new TransferCheckpoint(
                    completed.transferId(),
                    completed.sourceAdapter(),
                    completed.targetAdapter(),
                    completed.schemaFingerprint(),
                    completed.batchSize(),
                    0,
                    null,
                    completed.counts(),
                    true);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResumableTransfer.copy(source, target, schema, options, "transfer-1", checkpoints));
        }
    }

    @Test
    void stopsWhenSourceRepeatsCursor() {
        EntityRecord record = EntityRecord.builder().set("id", 1).build();
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record), "same-cursor");
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder()
                .entity("things", entity -> entity.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(1, report.transferred().get("things"));
            assertEquals(
                    TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
        }
    }

    @Test
    void reportsErrorWhenInsertFails() {
        EntityRecord record = EntityRecord.builder().set("id", 1).build();
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override
            public EntityRecord insert(String entity, EntityRecord record) {
                throw new RuntimeException("insert failed");
            }
        };
        Schema schema = Schema.builder()
                .entity("things", entity -> entity.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(0, report.transferred().get("things"));
            assertEquals(1, report.issues().size());
            assertEquals(
                    TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
            assertEquals("insert failed", report.issues().getFirst().detail());
        }
    }

    @Test
    void reportsErrorWhenSourceFindFails() {
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                throw new RuntimeException("source find failed");
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder()
                .entity("things", entity -> entity.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(0, report.transferred().get("things"));
            assertEquals(1, report.issues().size());
            assertEquals(
                    TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
            assertEquals("source find failed", report.issues().getFirst().detail());
        }
    }

    @Test
    void progressCallbackCalledForEachRecord() {
        List<String> progressEvents = new ArrayList<>();
        EntityRecord record1 = EntityRecord.builder().set("id", 1).build();
        EntityRecord record2 = EntityRecord.builder().set("id", 2).build();
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                if (query.cursor() == null) {
                    return new Page<>(List.of(record1, record2), "next-cursor");
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder()
                .entity("things", entity -> entity.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, (entity, count) -> {
            progressEvents.add(entity + ":" + count);
        });

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
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
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                if (query.cursor() == null) {
                    return new Page<>(List.of(record1, record2), "cursor1");
                } else if ("cursor1".equals(query.cursor())) {
                    return new Page<>(List.of(record3, record4), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder()
                .entity("things", entity -> entity.integer("id"))
                .build();
        TransferOptions options = new TransferOptions(2, false, false, null, (entity, count) -> {
            progressEvents.add(entity + ":" + count);
        });

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(4, report.transferred().get("things"));
            assertEquals(List.of("things:1", "things:2", "things:3", "things:4"), progressEvents);
        }
    }

    @Test
    void transformFunctionAppliedToRecords() {
        List<EntityRecord> insertedRecords = new ArrayList<>();
        EntityRecord record1 =
                EntityRecord.builder().set("id", 1).set("name", "alice").build();
        EntityRecord record2 =
                EntityRecord.builder().set("id", 2).set("name", "bob").build();
        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record1, record2), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override
            public EntityRecord insert(String entity, EntityRecord record) {
                insertedRecords.add(record);
                return record;
            }
        };
        Schema schema = Schema.builder()
                .entity("things", e -> e.integer("id").add().string("name"))
                .build();
        UnaryOperator<EntityRecord> transform = r -> EntityRecord.builder()
                .set("id", r.get("id"))
                .set("name", r.get("name").toString().toUpperCase())
                .build();
        TransferOptions options = new TransferOptions(10, false, false, transform, null);

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
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
                List.of(new SchemaChange("Narrow a field", ChangeRisk.LOSSY, null)), () -> schemaPlanApplied.set(true));
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
                List.of(new SchemaChange("Narrow a field", ChangeRisk.LOSSY, null)), () -> schemaPlanApplied.set(true));
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
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
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

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertTrue(report.complete());
            assertEquals(4, report.transferred().get("things"));
            assertEquals(List.of(2, 2), batchSizes);
        }
    }

    @Test
    void transfersMultipleEntities() {
        List<String> insertedEntities = new ArrayList<>();
        EntityRecord user1 =
                EntityRecord.builder().set("id", 1).set("name", "alice").build();
        EntityRecord user2 =
                EntityRecord.builder().set("id", 2).set("name", "bob").build();
        EntityRecord post1 = EntityRecord.builder()
                .set("id", 1)
                .set("userId", 1)
                .set("title", "hello")
                .build();
        EntityRecord post2 = EntityRecord.builder()
                .set("id", 2)
                .set("userId", 2)
                .set("title", "world")
                .build();

        EntityStore sourceStore = new StubStore() {
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                if ("users".equals(entity)) {
                    return new Page<>(List.of(user1, user2), null);
                } else if ("posts".equals(entity)) {
                    return new Page<>(List.of(post1, post2), null);
                }
                return new Page<>(List.of(), null);
            }
        };
        EntityStore targetStore = new StubStore() {
            @Override
            public EntityRecord insert(String entity, EntityRecord record) {
                insertedEntities.add(entity);
                return record;
            }
        };
        Schema schema = Schema.builder()
                .entity("users", e -> e.integer("id").add().string("name"))
                .entity(
                        "posts",
                        e -> e.integer("id").add().integer("userId").add().string("title"))
                .build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
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
            @Override
            public Page<EntityRecord> find(String entity, Query query) {
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

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
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

        try (Tava source = Tava.open(adapter(sourceStore));
                Tava target = Tava.open(adapter(targetStore))) {
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
            public String name() {
                return "stub";
            }

            public Capabilities capabilities() {
                return Capabilities.builder(name()).build();
            }

            public SchemaManager schemas() {
                if (schemaManager == null) throw new UnsupportedOperationException();
                return schemaManager;
            }

            public EntityStore entities() {
                return store;
            }

            public NativeAccess nativeAccess() {
                throw new UnsupportedOperationException();
            }

            public void close() {}
        };
    }

    private static Adapter adapter(String name, EntityStore store) {
        return new Adapter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Capabilities capabilities() {
                return Capabilities.builder(name)
                        .supported(Feature.BULK_WRITE)
                        .limit(Feature.BULK_WRITE, "batchSize", 100)
                        .build();
            }

            @Override
            public SchemaManager schemas() {
                throw new UnsupportedOperationException();
            }

            @Override
            public EntityStore entities() {
                return store;
            }

            @Override
            public NativeAccess nativeAccess() {
                return new NativeAccess() {
                    @Override
                    @SuppressWarnings("deprecation")
                    public <T> T nativeHandle(Class<T> type) {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public void close() {}
        };
    }

    private static SchemaManager schemaManager(SchemaPlan schemaPlan) {
        return new SchemaManager() {
            @Override
            public Schema inspect() {
                return Schema.builder().build();
            }

            @Override
            public SchemaPlan plan(Schema desired) {
                return schemaPlan;
            }

            @Override
            public SchemaPlan plan(Schema desired, SchemaRenames renames) {
                return schemaPlan;
            }
        };
    }

    private static class StubStore implements EntityStore {
        public EntityRecord insert(String entity, EntityRecord record) {
            return record;
        }

        public Page<EntityRecord> find(String entity, Query query) {
            return new Page<>(List.of(), null);
        }

        public long update(String entity, Predicate predicate, Mutation mutation) {
            return 0;
        }

        public long delete(String entity, Predicate predicate) {
            return 0;
        }
    }

    private static final class PartialBulkStore extends StubStore {
        private final Map<Object, EntityRecord> records = new LinkedHashMap<>();

        @Override
        public List<EntityRecord> insert(String entity, List<EntityRecord> values) {
            records.putIfAbsent(values.getFirst().get("id"), values.getFirst());
            return List.of(values.getFirst());
        }

        @Override
        public EntityRecord insert(String entity, EntityRecord record) {
            if (records.putIfAbsent(record.get("id"), record) != null)
                throw new TavaException.Conflict("Duplicate identity", null);
            return record;
        }

        @Override
        public MutationResult insertResult(String entity, EntityRecord record) {
            if (records.putIfAbsent(record.get("id"), record) != null)
                return new MutationResult(0, 0, 1, MutationResult.Outcome.CONFLICT);
            return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
        }

        @Override
        public Page<EntityRecord> find(String entity, Query query) {
            if (query.predicate() instanceof Predicate.Comparison comparison
                    && comparison.operator() == Predicate.Operator.EQ
                    && "id".equals(comparison.field())) {
                EntityRecord found = records.get(comparison.value());
                return new Page<>(found == null ? List.of() : List.of(found), null);
            }
            return new Page<>(List.copyOf(records.values()), null);
        }
    }

    private static final class MemoryCheckpointStore implements TransferCheckpointStore {
        private TransferCheckpoint checkpoint;

        @Override
        public Lease acquire(String transferId) {
            return new Lease() {
                @Override
                public Optional<TransferCheckpoint> load() {
                    return Optional.ofNullable(checkpoint);
                }

                @Override
                public void save(TransferCheckpoint value) {
                    checkpoint = value;
                }

                @Override
                public void close() {}
            };
        }
    }
}
