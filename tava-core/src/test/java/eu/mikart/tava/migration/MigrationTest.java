package eu.mikart.tava.migration;

import static org.junit.jupiter.api.Assertions.*;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.h2.H2;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MigrationTest {
    private static final Schema EMPTY = new Schema(List.of());

    @Test
    void movesToTargetsInBothDirectionsAndOnlyRunsPendingVersions() {
        var calls = new ArrayList<String>();
        var history = SchemaHistory.builder()
                .version(
                        "z-first",
                        EMPTY,
                        change -> change.up(context -> calls.add("up1")).down(context -> calls.add("down1")))
                .version(
                        "a-second",
                        EMPTY,
                        change -> change.up(context -> calls.add("up2")).down(context -> calls.add("down2")))
                .version(
                        "third",
                        EMPTY,
                        change -> change.up(context -> calls.add("up3")).down(context -> calls.add("down3")))
                .build();
        try (Tava database = open()) {
            assertTrue(history.current(database).isEmpty());
            assertTrue(database.schema().inspect().entities().isEmpty());
            history.to(database, "a-second");
            assertEquals(List.of("up1", "up2"), calls);
            assertEquals("a-second", history.current(database).orElseThrow());
            history.up(database);
            history.up(database);
            history.to(database, "z-first");
            assertEquals(List.of("up1", "up2", "up3", "down3", "down2"), calls);
            history.down(database);
            history.down(database);
            assertEquals("down1", calls.getLast());
            assertTrue(history.current(database).isEmpty());
            history.to(database, "a-second");
            assertEquals(List.of("up1", "up2"), calls.subList(calls.size() - 2, calls.size()));
        }
    }

    @Test
    void rollbackRestoresRenamedSchemaAndPreservesRows() {
        Schema before = schema("name");
        Schema after = schema("display_name");
        var history = SchemaHistory.builder()
                .version(
                        "one",
                        before,
                        change -> change.up(context -> context.apply(context.next()))
                                .down(context -> sql(context, "DROP TABLE IF EXISTS \"accounts\"")))
                .version(
                        "two",
                        after,
                        change -> change.up(context -> context.apply(
                                        context.next(),
                                        SchemaRenames.builder()
                                                .field("accounts", "name", "display_name")
                                                .build()))
                                .down(context -> context.apply(
                                        context.previous(),
                                        SchemaRenames.builder()
                                                .field("accounts", "display_name", "name")
                                                .build())))
                .build();
        try (Tava database = open()) {
            history.to(database, "one");
            database.records("accounts")
                    .insert(EntityRecord.builder()
                            .set("id", "a")
                            .set("name", "Ada")
                            .build());
            history.up(database);
            assertEquals(
                    "Ada",
                    database.records("accounts")
                            .findOne(Predicate.all())
                            .orElseThrow()
                            .get("display_name"));
            history.down(database);
            assertEquals(
                    "Ada",
                    database.records("accounts")
                            .findOne(Predicate.all())
                            .orElseThrow()
                            .get("name"));
            history.down(database);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> database.schema().inspect().entity("accounts"));
        }
    }

    @Test
    void resumesFailedUpWithoutRepeatingCompletedStepsAfterReopeningDatabase() {
        String name = UUID.randomUUID().toString();
        var first = new AtomicInteger();
        var attempts = new AtomicInteger();
        var later = new AtomicInteger();
        var history = SchemaHistory.builder()
                .version(
                        "one",
                        EMPTY,
                        change -> change.up(context -> first.incrementAndGet())
                                .up(context -> {
                                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("interrupted");
                                })
                                .down(context -> {}))
                .version(
                        "two",
                        EMPTY,
                        change -> change.up(context -> later.incrementAndGet()).down(context -> {}))
                .build();
        try (Tava database = Tava.open(H2.memory(name))) {
            assertThrows(IllegalStateException.class, () -> history.up(database));
            assertEquals(1, first.get());
            assertEquals(0, later.get());
            assertThrows(IllegalStateException.class, () -> history.current(database));
            assertThrows(IllegalStateException.class, () -> history.down(database));
        }
        try (Tava database = Tava.open(H2.memory(name))) {
            history.up(database);
            assertEquals(1, first.get());
            assertEquals(2, attempts.get());
            assertEquals(1, later.get());
            assertEquals("two", history.current(database).orElseThrow());
        }
    }

    @Test
    void resumesFailedDownAndDoesNotAcceptDirectionChangeMidTransition() {
        var completed = new AtomicInteger();
        var attempts = new AtomicInteger();
        var history = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.up(context -> {}).down(context -> {}))
                .version(
                        "two",
                        EMPTY,
                        change -> change.up(context -> {})
                                .down(context -> completed.incrementAndGet())
                                .down(context -> {
                                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("interrupted");
                                }))
                .build();
        try (Tava database = open()) {
            history.up(database);
            assertThrows(IllegalStateException.class, () -> history.down(database));
            assertThrows(IllegalStateException.class, () -> history.up(database));
            history.down(database);
            assertEquals(1, completed.get());
            assertEquals(2, attempts.get());
            assertEquals("one", history.current(database).orElseThrow());
            history.up(database);
            assertEquals("two", history.current(database).orElseThrow());
        }
    }

    @Test
    void preflightsWholeRollbackRouteBeforeUndoingAnything() {
        var down = new AtomicInteger();
        var history = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.up(context -> {}))
                .version("two", EMPTY, change -> change.up(context -> {}))
                .version("three", EMPTY, change -> change.up(context -> {}).down(context -> down.incrementAndGet()))
                .build();
        try (Tava database = open()) {
            history.up(database);
            assertThrows(IllegalStateException.class, () -> history.to(database, "one"));
            assertEquals(0, down.get());
            assertEquals("three", history.current(database).orElseThrow());
            history.down(database);
            assertEquals(1, down.get());
            assertThrows(IllegalStateException.class, () -> history.down(database));
        }
    }

    @Test
    void finalVerificationIsRetriedWithoutRepeatingActionsAndCannotBeSkipped() {
        var calls = new AtomicInteger();
        Schema target = schema("name");
        var history = SchemaHistory.builder()
                .version(
                        "one",
                        target,
                        change -> change.up(context -> calls.incrementAndGet()).down(context -> {}))
                .build();
        try (Tava database = open()) {
            assertThrows(IllegalStateException.class, () -> history.up(database));
            assertThrows(IllegalStateException.class, () -> history.up(database));
            assertEquals(1, calls.get());
            database.schema().ensure(target);
            history.up(database);
            assertEquals("one", history.current(database).orElseThrow());
            assertThrows(IllegalStateException.class, () -> history.down(database));
            database.nativeAccess().withNative(Connection.class, connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP TABLE \"accounts\"");
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                return null;
            });
            history.down(database);
            assertTrue(history.current(database).isEmpty());
        }
    }

    @Test
    void refusesFakeRollbackThatOnlyAppliesThePreviousAdditiveSchema() {
        Schema first = schema("name");
        Schema second = Schema.builder()
                .entity("accounts", entity -> {
                    entity.string("id").identity();
                    entity.string("name");
                    entity.string("status");
                })
                .build();
        var history = SchemaHistory.builder()
                .version("one", first, change -> change.up(context -> context.apply(context.next())))
                .version(
                        "two",
                        second,
                        change -> change.up(context -> context.apply(context.next()))
                                .down(context -> context.apply(context.previous())))
                .build();
        try (Tava database = open()) {
            history.up(database);
            assertThrows(IllegalStateException.class, () -> history.down(database));
            assertThrows(IllegalStateException.class, () -> history.current(database));
        }
    }

    @Test
    void freezesSnapshotsAndSuppliesThemToBothDirections() {
        var nested = new ArrayList<Object>(List.of("original"));
        Schema source =
                new Schema(List.of(new EntityDefinition("items", List.of(), List.of(), Map.of("nested", nested))));
        var history = SchemaHistory.builder()
                .version("one", source, change -> change.up(context -> {}))
                .build();
        nested.add("changed");
        assertEquals(
                List.of("original"),
                history.versions().getFirst().next().entity("items").settings().get("nested"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaHistory.builder()
                        .version(
                                "one",
                                new Schema(List.of(new EntityDefinition(
                                        "items", List.of(), List.of(), Map.of("value", new StringBuilder())))),
                                change -> change.up(context -> {})));
        var seen = new ArrayList<Schema>();
        var executable = SchemaHistory.builder()
                .version(
                        "one",
                        EMPTY,
                        change -> change.up(context -> {
                                    seen.add(context.previous());
                                    seen.add(context.next());
                                })
                                .down(context -> {
                                    seen.add(context.previous());
                                    seen.add(context.next());
                                }))
                .build();
        try (Tava database = open()) {
            executable.up(database);
            executable.down(database);
            assertEquals(List.of(EMPTY, EMPTY, EMPTY, EMPTY), seen);
        }
    }

    @Test
    void rejectsChangedSnapshotsAndActionDigestsBeforeAnyNewAction() {
        var calls = new AtomicInteger();
        var original = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.checksum("source-v1").up(context -> {}))
                .build();
        var changedCode = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.checksum("source-v2").up(context -> {}))
                .version("two", EMPTY, change -> change.up(context -> calls.incrementAndGet()))
                .build();
        var changedSchema = SchemaHistory.builder()
                .version("one", schema("new_name"), change -> change.up(context -> {}))
                .build();
        try (Tava database = open()) {
            original.up(database);
            assertThrows(IllegalStateException.class, () -> changedCode.up(database));
            assertThrows(IllegalStateException.class, () -> changedSchema.down(database));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void keepsHistoricalChecksumsAfterRollback() {
        var original = SchemaHistory.builder()
                .version(
                        "one",
                        EMPTY,
                        change -> change.checksum("original").up(context -> {}).down(context -> {}))
                .build();
        var changed = SchemaHistory.builder()
                .version(
                        "one",
                        EMPTY,
                        change -> change.checksum("changed").up(context -> {}).down(context -> {}))
                .build();
        try (Tava database = open()) {
            original.up(database);
            original.down(database);
            assertTrue(original.current(database).isEmpty());
            assertThrows(IllegalStateException.class, () -> changed.up(database));
            original.up(database);
            assertEquals("one", original.current(database).orElseThrow());
        }
    }

    @Test
    void rejectsMissingOrUnknownRecordedVersionsAndInvalidCheckpoints() {
        var history = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.up(context -> {}))
                .version("two", EMPTY, change -> change.up(context -> {}).down(context -> {}))
                .build();
        try (Tava database = open()) {
            history.up(database);
            database.records("_tava_migrations").delete(Predicate.eq("id", "one"));
            assertThrows(IllegalStateException.class, () -> history.up(database));
        }
        try (Tava database = open()) {
            history.up(database);
            var missing = SchemaHistory.builder()
                    .version("one", EMPTY, change -> change.up(context -> {}))
                    .build();
            assertThrows(IllegalStateException.class, () -> missing.up(database));
        }
    }

    @Test
    void preservesUnrelatedStorageAndRefusesImplicitAdoption() {
        Schema target = schema("name");
        var history = SchemaHistory.builder()
                .version("one", target, change -> change.up(context -> context.apply(context.next())))
                .build();
        try (Tava database = open()) {
            database.schema()
                    .ensure(Schema.builder()
                            .entity("unrelated", entity -> entity.string("id").identity())
                            .build());
            history.up(database);
            assertNotNull(database.schema().inspect().entity("unrelated"));
        }
        try (Tava database = open()) {
            database.schema().ensure(target);
            assertThrows(IllegalStateException.class, () -> history.up(database));
        }
    }

    @Test
    void holdsLockAcrossCallbacksAndReleasesItAfterFailure() {
        String name = UUID.randomUUID().toString();
        var reference = new AtomicReference<SchemaHistory>();
        var fail = new AtomicBoolean(true);
        var history = SchemaHistory.builder()
                .version(
                        "one",
                        EMPTY,
                        change -> change.up(context -> {
                            try (Tava competitor = Tava.open(H2.memory(name))) {
                                assertThrows(
                                        IllegalStateException.class,
                                        () -> reference.get().up(competitor));
                            }
                            if (fail.getAndSet(false)) throw new IllegalStateException("failed action");
                        }))
                .build();
        reference.set(history);
        try (Tava database = Tava.open(H2.memory(name))) {
            assertThrows(IllegalStateException.class, () -> history.up(database));
            history.up(database);
            assertEquals("one", history.current(database).orElseThrow());
        }
    }

    @Test
    void validatesDefinitionsAndTargetsBeforeDatabaseWork() {
        assertThrows(
                IllegalArgumentException.class, () -> SchemaHistory.builder().build());
        assertThrows(
                IllegalArgumentException.class, () -> SchemaHistory.builder().version("one", EMPTY, change -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaHistory.builder()
                        .version(SchemaJournal.LOCK_ID, EMPTY, change -> change.up(context -> {})));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaHistory.builder()
                        .version("one", EMPTY, change -> change.up(context -> {}))
                        .version("one", EMPTY, change -> change.up(context -> {})));
        assertThrows(
                IllegalArgumentException.class, () -> SchemaHistory.builder().journal(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaHistory.builder()
                        .journal("accounts")
                        .version("one", schema("name"), change -> change.up(context -> {}))
                        .build());
        var history = SchemaHistory.builder()
                .version("one", EMPTY, change -> change.up(context -> {}))
                .build();
        try (Tava database = open()) {
            assertThrows(IllegalArgumentException.class, () -> history.to(database, "unknown"));
            assertTrue(database.schema().inspect().entities().isEmpty());
        }
    }

    @Test
    void computesAndLoadsOptionalSourceChecksums() throws Exception {
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(
                expected, MigrationChecksums.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
        assertEquals(
                expected,
                MigrationChecksums.fromManifest(
                        new ByteArrayInputStream(("001.java=" + expected + "\n").getBytes(StandardCharsets.ISO_8859_1)),
                        "001.java"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationChecksums.fromManifest(new ByteArrayInputStream(new byte[0]), "missing"));
    }

    private static Tava open() {
        return Tava.open(H2.memory(UUID.randomUUID().toString()));
    }

    private static Schema schema(String name) {
        return Schema.builder()
                .entity("accounts", entity -> {
                    entity.string("id").identity();
                    entity.string(name);
                })
                .build();
    }

    private static void sql(MigrationContext context, String sql) {
        context.nativeAccess().withNative(Connection.class, connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });
    }
}
