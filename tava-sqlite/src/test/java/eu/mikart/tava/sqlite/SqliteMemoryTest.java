package eu.mikart.tava.sqlite;

import eu.mikart.tava.Tava;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.FieldRef;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.annotation.Entity;
import eu.mikart.tava.schema.annotation.Identity;
import eu.mikart.tava.testkit.AdapterContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMemoryTest extends AdapterContractTest {
    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(Sqlite.memory(namespace));
    }

    @Override
    protected boolean supportsIsolatedTransferTargets() {
        return true;
    }

    @Test
    void memoryDatabaseSurvivesPerOperationConnections() {
        try (Tava tava = Tava.open(Sqlite.memory("contract"))) {
            tava.plan(Schema.builder().record(Item.class).build()).apply();
            tava.entity(Item.class).insert(new Item(1, "one"));
            assertEquals(1, tava.entity(Item.class).find(Query.builder()
                    .where(Predicate.eq("id", 1)).build()).items().size());
        }
    }

    record Item(@Identity int id, String name) {
    }

    enum Rank { BRONZE, GOLD }
    @Entity("players")
    record Player(@Identity UUID id, Rank rank, Set<UUID> emblems) { }
    @SuppressWarnings("unchecked")
    private static final FieldRef<java.util.Collection<UUID>> EMBLEMS =
            FieldRef.of("emblems", (Class<java.util.Collection<UUID>>) (Class<?>) java.util.Collection.class);

    @Test
    void typedCollectionsEnumsFindOneAndAtomicMutationsRoundTrip() {
        UUID id = UUID.randomUUID(); UUID emblem = UUID.randomUUID();
        try (Tava tava = Tava.open(Sqlite.memory("typed-collections"))) {
            tava.plan(Schema.builder().record(Player.class).build()).apply();
            var players = tava.entity(Player.class);
            players.insert(new Player(id, Rank.BRONZE, Set.of()));
            assertEquals(Rank.BRONZE, players.findOne(Predicate.eq("id", id)).orElseThrow().rank());
            assertEquals(id, players.findOne(Predicate.eq("rank", Rank.BRONZE)).orElseThrow().id());

            Mutation add = Mutation.builder().appendIfAbsent(EMBLEMS, emblem).build();
            assertEquals(1, players.updateById(id, add).changedRows());
            players.updateById(id, add);
            assertEquals(Set.of(emblem), players.findById(id).orElseThrow().emblems());

            players.updateById(id, Mutation.builder().remove(EMBLEMS, emblem).build());
            assertEquals(Set.of(), players.findById(id).orElseThrow().emblems());
        }
    }

    @Test
    void upsertIsAtomicAndInsertIfAbsentReturnsConflictDetails() throws Exception {
        UUID id = UUID.randomUUID();
        try (Tava tava = Tava.open(Sqlite.memory("atomic-upsert"));
             var executor = Executors.newFixedThreadPool(4)) {
            tava.plan(Schema.builder().record(Player.class).build()).apply();
            var futures = IntStream.range(0, 8).mapToObj(i -> executor.submit(() ->
                    tava.entity(Player.class).upsert(new Player(id, Rank.GOLD, Set.of())))).toList();
            for (var future : futures) assertNotNull(future.get());
            assertEquals(1, tava.entity(Player.class).find(Query.all()).items().size());

            MutationResult conflict = tava.entity(Player.class)
                    .insertIfAbsent(new Player(id, Rank.BRONZE, Set.of()));
            assertTrue(conflict.conflicted());
            assertEquals(1, conflict.conflicts());
            assertFalse(conflict.conflictDetails().isEmpty());
        }
    }

    @Test
    void transactionRollsBackAndRenameMappingsAdoptExistingSchema() {
        try (Tava tava = Tava.open(Sqlite.memory("rename-and-transaction"))) {
            tava.plan(Schema.builder().entity("old_players", entity -> {
                entity.string("id").identity().field(36);
                entity.string("badge_csv").field(255);
            }).build()).apply();
            tava.records("old_players").insert(EntityRecord.builder().set("id", "one").set("badge_csv", "gold").build());

            assertThrows(IllegalStateException.class, () -> tava.transaction(tx -> {
                tx.records("old_players").insert(EntityRecord.builder().set("id", "two").set("badge_csv", "silver").build());
                throw new IllegalStateException("rollback");
            }));
            assertTrue(tava.records("old_players").findOne(Predicate.eq("id", "two")).isEmpty());

            Schema desired = Schema.builder().entity("players_v2", entity -> {
                entity.string("id").identity().field(36);
                entity.string("badge").field(255);
            }).build();
            SchemaRenames renames = SchemaRenames.builder().entity("old_players", "players_v2")
                    .field("players_v2", "badge_csv", "badge").build();
            tava.plan(desired, renames).apply();
            assertEquals("gold", tava.records("players_v2").findById(eu.mikart.tava.data.Identity.of("id", "one")).orElseThrow().get("badge"));
        }
    }

    @Test
    void appliesPragmasAndCreatesFlatFileBackup(@TempDir Path directory) {
        Path source = directory.resolve("source.db"); Path backup = directory.resolve("backup.db");
        SqliteOptions options = SqliteOptions.builder().foreignKeys(true)
                .synchronous(SqliteOptions.Synchronous.FULL)
                .journalMode(SqliteOptions.JournalMode.WAL).busyTimeout(Duration.ofSeconds(2)).build();
        try (Tava tava = Tava.open(Sqlite.file(source, options))) {
            tava.plan(Schema.builder().record(Item.class).build()).apply();
            tava.entity(Item.class).insert(new Item(1, "one"));
            tava.nativeAccess().withNative(Connection.class, connection -> {
                try (var statement = connection.createStatement()) {
                    assertEquals(1, statement.executeQuery("PRAGMA foreign_keys").getInt(1));
                    assertEquals(2000, statement.executeQuery("PRAGMA busy_timeout").getInt(1));
                } catch (Exception failure) { throw new RuntimeException(failure); }
                return null;
            });
        }
        Sqlite.backup(source, backup, options);
        try (Tava tava = Tava.open(Sqlite.file(backup, options))) {
            assertEquals("one", tava.entity(Item.class).findById(1).orElseThrow().name());
        }
    }
}
