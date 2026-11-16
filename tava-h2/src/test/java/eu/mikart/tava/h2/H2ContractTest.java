package eu.mikart.tava.h2;

import static org.junit.jupiter.api.Assertions.*;

import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.migration.MigrationContext;
import eu.mikart.tava.migration.SchemaHistory;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.annotation.*;
import eu.mikart.tava.testkit.AdapterContractTest;
import eu.mikart.tava.transfer.DataTransfer;
import eu.mikart.tava.transfer.TransferOptions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class H2ContractTest extends AdapterContractTest {
    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(H2.memory(namespace));
    }

    @Override
    protected boolean supportsIsolatedTransferTargets() {
        return true;
    }

    @Test
    void schemaCrudNativeAndTransfer() {
        Schema schema = Schema.builder().record(Account.class).build();
        try (Tava source = Tava.open(H2.memory("source-" + UUID.randomUUID()));
                Tava target = Tava.open(H2.memory("target-" + UUID.randomUUID()))) {
            source.plan(schema).apply();
            UUID id = UUID.randomUUID();
            source.entity(Account.class).insert(new Account(id, "ada@example.com", 7, null));

            var found = source.entity(Account.class)
                    .find(Query.builder().where(Predicate.eq("id", id)).limit(1).build())
                    .items();
            assertEquals(1, found.size());
            assertEquals("ada@example.com", found.getFirst().email());

            assertEquals(
                    1,
                    source.entity(Account.class)
                            .update(
                                    Predicate.eq("id", id),
                                    Mutation.builder().set("score", 8).build()));

            var report = DataTransfer.copy(source, target, schema, TransferOptions.defaults());
            assertTrue(report.complete());
            assertEquals(1L, report.transferred().get("account"));
            assertEquals(
                    1, target.entity(Account.class).find(Query.all()).items().size());

            source.nativeAccess().withNative(Connection.class, connection -> {
                assertNotNull(connection);
                return null;
            });
        }
    }

    @Test
    void rollsBackPartialTransactionalBatchAfterConflict() {
        Schema schema = Schema.builder()
                .entity("items", entity -> {
                    entity.integer("id").identity().add().string("details");
                })
                .build();

        try (Tava tava = Tava.open(H2.memory("batch-savepoint-" + UUID.randomUUID()))) {
            tava.plan(schema).apply();

            tava.transaction(transaction -> {
                assertThrows(
                        TavaException.Conflict.class,
                        () -> transaction
                                .records("items")
                                .insert(List.of(
                                        EntityRecord.builder()
                                                .set("id", 1)
                                                .set("details", "first statement group")
                                                .build(),
                                        EntityRecord.builder().set("id", 1).build())));
                assertTrue(
                        transaction.records("items").find(Query.all()).items().isEmpty());
                transaction
                        .records("items")
                        .insert(EntityRecord.builder().set("id", 2).build());
                return null;
            });

            var records = tava.records("items").find(Query.all()).items();
            assertEquals(1, records.size());
            assertEquals(2, records.getFirst().get("id"));
        }
    }

    @Test
    void historyMovesSchemaAndDataForwardAndBackward() {
        Schema first = Schema.builder()
                .entity("profiles", entity -> {
                    entity.uuid("id").identity();
                    entity.string("name").required().length(120);
                })
                .build();
        Schema second = Schema.builder()
                .entity("profiles", entity -> {
                    entity.uuid("id").identity();
                    entity.string("name").required().length(120);
                    entity.string("email").length(255);
                })
                .build();
        UUID id = UUID.randomUUID();
        var history = SchemaHistory.builder()
                .version(
                        "profiles",
                        first,
                        change -> change.up(context -> context.apply(context.next()))
                                .up(context -> context.records("profiles")
                                        .insert(EntityRecord.builder()
                                                .set("id", id)
                                                .set("name", "Ada Lovelace")
                                                .build()))
                                .down(context -> sql(context, "DROP TABLE IF EXISTS \"profiles\"")))
                .version(
                        "email",
                        second,
                        change -> change.up(context -> context.apply(context.next()))
                                .up(context -> context.records("profiles")
                                        .update(
                                                Predicate.all(),
                                                Mutation.builder()
                                                        .set("email", "ada@example.com")
                                                        .build()))
                                .down(context ->
                                        sql(context, "ALTER TABLE \"profiles\" DROP COLUMN IF EXISTS \"email\"")))
                .build();
        try (Tava tava = Tava.open(H2.memory("history-" + UUID.randomUUID()))) {
            history.up(tava);
            history.up(tava);
            assertEquals(1, tava.records("profiles").count(Predicate.all()));
            assertEquals(
                    "ada@example.com",
                    tava.records("profiles")
                            .findOne(Predicate.all())
                            .orElseThrow()
                            .get("email"));
            history.to(tava, "profiles");
            assertEquals("profiles", history.current(tava).orElseThrow());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tava.schema().inspect().entity("profiles").field("email"));
            history.to(tava, "email");
            history.down(tava);
            history.down(tava);
            assertTrue(history.current(tava).isEmpty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tava.schema().inspect().entity("profiles"));
        }
    }

    private static void sql(MigrationContext context, String statement) {
        context.nativeAccess().withNative(Connection.class, connection -> {
            try (var query = connection.createStatement()) {
                query.execute(statement);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });
    }

    record Account(
            @Identity UUID id,
            @Required @Unique @Field(length = 200) String email,
            int score,

            @Generated(GeneratedValue.NOW) @H2Type("TIMESTAMP WITH TIME ZONE")
            Instant createdAt) {}
}
