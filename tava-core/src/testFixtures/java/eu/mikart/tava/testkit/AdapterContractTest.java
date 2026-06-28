package eu.mikart.tava.testkit;

import eu.mikart.tava.Tava;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.migration.Migration;
import eu.mikart.tava.migration.MigrationResult;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.transfer.DataTransfer;
import eu.mikart.tava.transfer.TransferOptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public abstract class AdapterContractTest {
    protected abstract Tava openTava(String namespace);

    protected boolean supportsGlobalSorting() {
        return true;
    }

    protected boolean supportsInPredicate() {
        return true;
    }

    protected boolean supportsIsolatedTransferTargets() {
        return false;
    }

    @Test
    void adapterReportsIdentityAndCoreCapabilities() {
        String namespace = uniqueEntity("caps");

        try (Tava tava = openTava(namespace)) {
            assertFalse(tava.adapterName().isBlank());
            assertEquals(tava.adapterName(), tava.capabilities().adapter());
            assertTrue(tava.capabilities().supports(Feature.SCHEMA_ENFORCEMENT));
            assertTrue(tava.capabilities().supports(Feature.PAGINATION));
            assertTrue(tava.capabilities().supports(Feature.PROJECTION));
        }
    }

    @Test
    void schemaPlanCreatesEntityAndInspectReportsIt() {
        String entity = uniqueEntity("schema");
        Schema schema = accountSchema(entity);

        try (Tava tava = openTava(entity)) {
            var plan = tava.plan(schema);
            assertFalse(plan.changes().isEmpty(), "new entity should produce schema changes");
            assertTrue(plan.changes().stream().anyMatch(change -> change.description().contains(entity)));

            plan.apply();

            var inspected = tava.schema().inspect();
            assertTrue(inspected.entities().stream()
                            .anyMatch(definition -> definition.name().equalsIgnoreCase(entity)),
                    "inspected schema should contain " + entity);
        }
    }

    @Test
    void recordsCrudPredicatesProjectionUpdateDeleteAndPaging() {
        String entity = uniqueEntity("acct");

        try (Tava tava = openTava(entity)) {
            tava.plan(accountSchema(entity)).apply();
            insertAccounts(tava, entity);

            assertEquals(3, tava.records(entity).find(Query.all()).items().size());
            assertEquals(1, find(tava, entity, Predicate.eq("note", null)).size());
            assertEquals(2, find(tava, entity, Predicate.ne("note", null)).size());
            assertEquals(1, find(tava, entity, Predicate.isNull("note")).size());
            assertEquals(2, find(tava, entity, Predicate.isNotNull("note")).size());
            assertEquals(1, find(tava, entity, Predicate.lt("score", 8)).size());
            assertEquals(2, find(tava, entity, Predicate.lte("score", 8)).size());
            assertEquals(2, find(tava, entity, Predicate.gte("score", 8)).size());
            assertEquals(1, find(tava, entity, Predicate.gt("score", 8)).size());
            assertEquals(3, find(tava, entity, Predicate.contains("email", "example")).size());
            assertEquals(1, find(tava, entity, Predicate.startsWith("email", "grace")).size());
            assertEquals(1, find(tava, entity, Predicate.and(
                    Predicate.gte("score", 9), Predicate.startsWith("email", "grace"))).size());
            assertEquals(2, find(tava, entity, Predicate.or(
                    Predicate.eq("id", "a"), Predicate.eq("id", "l"))).size());
            if (supportsInPredicate()) {
                assertEquals(2, find(tava, entity, Predicate.in("id", List.of("a", "l"))).size());
            }

            EntityRecord projected = tava.records(entity).find(Query.builder()
                    .where(Predicate.eq("id", "a")).project("email").build()).items().getFirst();
            assertEquals("ada@example.com", projected.get("email"));
            assertFalse(projected.values().containsKey("score"));

            assertEquals(0, tava.records(entity).update(Predicate.eq("id", "a"), Mutation.builder().build()));
            assertEquals(1, tava.records(entity).update(Predicate.eq("id", "a"),
                    Mutation.builder().set("score", 10).set("note", "math").build()));
            assertEquals(10, number(tava.records(entity).find(Query.builder()
                    .where(Predicate.eq("id", "a")).build()).items().getFirst().get("score")).intValue());

            if (supportsGlobalSorting()) {
                var firstPage = tava.records(entity).find(Query.builder()
                        .sort(Sort.asc("email")).limit(1).build());
                assertEquals(List.of("ada@example.com"), emails(firstPage.items()));
                assertNotNull(firstPage.nextCursor());

                var secondPage = tava.records(entity).find(Query.builder()
                        .sort(Sort.asc("email")).limit(1).cursor(firstPage.nextCursor()).build());
                assertEquals(List.of("grace@example.com"), emails(secondPage.items()));
            } else {
                var firstPage = tava.records(entity).find(Query.builder().limit(1).build());
                assertEquals(1, firstPage.items().size());
                assertNotNull(firstPage.nextCursor());
                assertEquals(1, tava.records(entity).find(Query.builder()
                        .limit(1).cursor(firstPage.nextCursor()).build()).items().size());
            }

            assertEquals(1, tava.records(entity).delete(Predicate.eq("id", "l")));
            assertTrue(find(tava, entity, Predicate.eq("id", "l")).isEmpty());
            assertEquals(2, tava.records(entity).find(Query.all()).items().size());
        }
    }

    @Test
    void migrationsApplyOnceTrackPendingRunPagedDataStepsAndRollback() {
        String entity = uniqueEntity("mig");
        String createId = entity + "_001_create";
        String backfillId = entity + "_002_backfill";
        Migration create = Migration.builder(createId)
                .upSchema(Schema.builder().entity(entity, builder -> {
                    builder.string("id").identity().field(36);
                    builder.string("email").required().field(255);
                    builder.integer("score").required();
                    builder.string("note").field(255);
                    builder.bool("active").required();
                }).build())
                .up("Seed migration rows", context -> {
                    context.insert(entity, EntityRecord.builder()
                            .set("id", "a")
                            .set("email", "ada@example.com")
                            .set("score", 1)
                            .set("note", null)
                            .set("active", true)
                            .build());
                    context.insert(entity, EntityRecord.builder()
                            .set("id", "g")
                            .set("email", "grace@example.com")
                            .set("score", 2)
                            .set("note", null)
                            .set("active", true)
                            .build());
                    context.insert(entity, EntityRecord.builder()
                            .set("id", "l")
                            .set("email", "linus@example.com")
                            .set("score", 3)
                            .set("note", null)
                            .set("active", false)
                            .build());
                })
                .down("Delete seeded migration rows", context ->
                        List.of("a", "g", "l").forEach(id -> context.delete(entity, Predicate.eq("id", id))))
                .build();
        Migration backfill = Migration.builder(backfillId)
                .up("Backfill notes in pages", context -> {
                    long seen = context.forEach(entity, migrationPageQuery(), record ->
                            context.update(entity, Predicate.eq("id", record.get("id")),
                                    Mutation.builder().set("note", "migrated-" + record.get("id")).build()));
                    assertEquals(3, seen);
                })
                .down("Clear backfilled notes", context ->
                        context.forEach(entity, migrationPageQuery(), record ->
                                context.update(entity, Predicate.eq("id", record.get("id")),
                                        Mutation.builder().set("note", null).build())))
                .build();
        Migration irreversible = Migration.up(entity + "_003_irreversible", context -> {
        });

        try (Tava tava = openTava(entity)) {
            assertEquals(List.of(create, backfill), tava.migrations().pending(List.of(create, backfill)));

            List<MigrationResult> first = tava.migrations().apply(create, backfill);
            assertEquals(2, first.size());
            assertTrue(first.stream().allMatch(MigrationResult::applied));
            assertEquals(createId, first.getFirst().id());
            assertEquals(backfillId, first.get(1).id());
            assertTrue(first.getFirst().changes().stream()
                    .anyMatch(change -> change.description().contains(entity)));
            assertEquals(3, first.getFirst().operations().stream()
                    .filter(operation -> operation.description().startsWith("Insert into " + entity))
                    .mapToLong(operation -> operation.affectedRows()).sum());
            assertEquals(3, first.get(1).operations().stream()
                    .filter(operation -> operation.description().startsWith("Update " + entity))
                    .mapToLong(operation -> operation.affectedRows()).sum());
            assertTrue(first.get(1).operations().stream()
                    .anyMatch(operation -> operation.description().equals("Iterate " + entity)
                            && operation.affectedRows() == 3));

            assertAppliedIds(tava, createId, backfillId);
            assertTrue(tava.migrations().pending(List.of(create, backfill)).isEmpty());
            assertEquals(List.of("migrated-a", "migrated-g", "migrated-l"),
                    tava.records(entity).find(Query.all()).items().stream()
                            .map(record -> record.get("note"))
                            .sorted()
                            .toList());

            List<MigrationResult> second = tava.migrations().apply(create, backfill);
            assertEquals(2, second.size());
            assertTrue(second.stream().noneMatch(MigrationResult::applied));
            assertTrue(second.stream().allMatch(result -> result.operations().isEmpty()));

            MigrationResult rollbackBackfill = tava.migrations().rollback(backfill);
            assertTrue(rollbackBackfill.applied());
            assertEquals(3, rollbackBackfill.operations().stream()
                    .filter(operation -> operation.description().startsWith("Update " + entity))
                    .mapToLong(operation -> operation.affectedRows()).sum());
            assertAppliedIds(tava, createId);
            assertEquals(3, tava.records(entity).find(Query.builder()
                    .where(Predicate.eq("note", null)).build()).items().size());

            MigrationResult rollbackCreate = tava.migrations().rollback(create);
            assertTrue(rollbackCreate.applied());
            assertTrue(tava.records(entity).find(Query.all()).items().isEmpty());
            assertAppliedIds(tava);

            assertFalse(tava.migrations().rollback(create).applied());
            assertThrows(IllegalArgumentException.class, () -> tava.migrations().rollback(irreversible));
        }
    }

    @Test
    void dataTransferCopiesSchemaRecordsAndReportsProgress() {
        assumeTrue(supportsIsolatedTransferTargets());
        String entity = uniqueEntity("copy");
        Schema schema = accountSchema(entity);
        List<Long> progress = new ArrayList<>();

        try (Tava source = openTava(entity + "source"); Tava target = openTava(entity + "target")) {
            source.plan(schema).apply();
            insertAccounts(source, entity);

            var report = DataTransfer.copy(source, target, schema,
                    new TransferOptions(1, false, true, record -> EntityRecord.of(record.values()),
                            (name, count) -> progress.add(count)));

            assertTrue(report.complete());
            assertEquals(3L, report.transferred().get(entity));
            assertEquals(List.of(1L, 2L, 3L), progress);
            assertEquals(3, target.records(entity).find(Query.all()).items().size());
        }
    }

    private static Schema accountSchema(String entity) {
        return Schema.builder().entity(entity, builder -> {
            builder.string("id").identity().field(36);
            builder.string("email").required().field(255);
            builder.integer("score").required();
            builder.string("note").field(255);
            builder.bool("active").required();
        }).build();
    }

    private static void insertAccounts(Tava tava, String entity) {
        tava.records(entity).insert(EntityRecord.builder()
                .set("id", "a")
                .set("email", "ada@example.com")
                .set("score", 7)
                .set("note", null)
                .set("active", true)
                .build());
        tava.records(entity).insert(EntityRecord.builder()
                .set("id", "g")
                .set("email", "grace@example.com")
                .set("score", 9)
                .set("note", "compiler")
                .set("active", true)
                .build());
        tava.records(entity).insert(EntityRecord.builder()
                .set("id", "l")
                .set("email", "linus@example.com")
                .set("score", 8)
                .set("note", "kernel")
                .set("active", false)
                .build());
    }

    private static List<EntityRecord> find(Tava tava, String entity, Predicate predicate) {
        return tava.records(entity).find(Query.builder().where(predicate).build()).items();
    }

    private static List<Object> emails(List<EntityRecord> records) {
        return records.stream().map(record -> record.get("email")).toList();
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        return new BigDecimal(value.toString());
    }

    private void assertAppliedIds(Tava tava, String... expected) {
        List<String> applied = tava.migrations().applied();
        if (supportsGlobalSorting()) {
            assertEquals(List.of(expected), applied);
        } else {
            assertEquals(List.of(expected).stream().sorted().toList(), applied.stream().sorted().toList());
        }
    }

    private Query migrationPageQuery() {
        Query.Builder builder = Query.builder().limit(1);
        if (supportsGlobalSorting()) builder.sort(Sort.asc("id"));
        return builder.build();
    }

    private static String uniqueEntity(String prefix) {
        return (prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .toLowerCase(Locale.ROOT);
    }
}
