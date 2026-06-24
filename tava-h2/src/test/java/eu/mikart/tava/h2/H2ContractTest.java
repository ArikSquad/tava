package eu.mikart.tava.h2;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.migration.Migration;
import eu.mikart.tava.migration.MigrationResult;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.annotation.*;
import eu.mikart.tava.transfer.DataTransfer;
import eu.mikart.tava.transfer.TransferOptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class H2ContractTest {
    @Test
    void schemaCrudNativeAndTransfer() {
        Schema schema = Schema.builder().record(Account.class).build();
        try (Tava source = Tava.open(H2.memory("source-" + UUID.randomUUID()));
             Tava target = Tava.open(H2.memory("target-" + UUID.randomUUID()))) {
            source.plan(schema).apply();
            UUID id = UUID.randomUUID();
            source.entity(Account.class).insert(new Account(id, "ada@example.com", 7, null));

            var found = source.entity(Account.class).find(Query.builder()
                    .where(Predicate.eq("id", id)).limit(1).build()).items();
            assertEquals(1, found.size());
            assertEquals("ada@example.com", found.getFirst().email());

            assertEquals(1, source.entity(Account.class).update(Predicate.eq("id", id),
                    Mutation.builder().set("score", 8).build()));

            var report = DataTransfer.copy(source, target, schema, TransferOptions.defaults());
            assertTrue(report.complete());
            assertEquals(1L, report.transferred().get("account"));
            assertEquals(1, target.entity(Account.class).find(Query.all()).items().size());

            try (var connection = source.nativeHandle(java.sql.Connection.class)) {
                assertNotNull(connection);
            } catch (java.sql.SQLException failure) {
                fail(failure);
            }
        }
    }

    @Test
    void appliesMigrationsOnceAndTracksIds() {
        final Migration authentication = Migration.up("2026-01-authentication",
                Schema.builder().entity("accounts", entity -> {
                    entity.uuid("id").identity();
                    entity.string("email").required().unique().field(255);
                }).build());
        final Migration score = Migration.up("2026-02-score",
                Schema.builder().entity("accounts", entity -> entity.integer("score")).build());

        try (Tava tava = Tava.open(H2.memory("migration-" + UUID.randomUUID()))) {
            final var first = tava.migrations().apply(authentication, score);
            assertEquals(2, first.stream().filter(MigrationResult::applied).count());

            final var second = tava.migrations().apply(authentication, score);
            assertEquals(0, second.stream().filter(MigrationResult::applied).count());
            assertEquals(List.of("2026-01-authentication", "2026-02-score"), tava.migrations().applied());

            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", UUID.randomUUID())
                    .set("email", "grace@example.com")
                    .set("score", 9)
                    .build());
            assertEquals(1, tava.records("accounts").find(Query.all()).items().size());
        }
    }

    @Test
    void migrationStepsCanMutateSchemaAndDataInOrder() {
        final UUID id = UUID.randomUUID();
        final Migration migration = Migration.builder("2026-03-backfill-email")
                .upSchema(Schema.builder().entity("profiles", entity -> {
                    entity.uuid("id").identity();
                    entity.string("name").required().field(120);
                }).build())
                .up("Seed profile", context -> context.insert("profiles", EntityRecord.builder()
                        .set("id", id)
                        .set("name", "Ada Lovelace")
                        .build()))
                .upSchema(Schema.builder().entity("profiles", entity -> entity.string("email").field(255)).build())
                .up("Backfill email", context -> context.forEach("profiles", Query.all(), record ->
                        context.update("profiles", Predicate.eq("id", record.get("id")),
                                Mutation.builder().set("email", "ada@example.com").build())))
                .down("Remove seeded profile", context -> context.delete("profiles", Predicate.eq("id", id)))
                .build();

        try (Tava tava = Tava.open(H2.memory("migration-toolkit-" + UUID.randomUUID()))) {
            final var applied = tava.migrations().apply(migration).getFirst();
            assertTrue(applied.applied());
            assertEquals(5, applied.operations().size());

            final var records = tava.records("profiles").find(Query.all()).items();
            assertEquals(1, records.size());
            assertEquals("ada@example.com", records.getFirst().get("email"));

            final var rollback = tava.migrations().rollback(migration);
            assertTrue(rollback.applied());
            assertTrue(tava.records("profiles").find(Query.all()).items().isEmpty());
        }
    }

    record Account(
            @Identity UUID id,
            @Required @Unique @Field(length = 200) String email,
            int score,
            @Generated(GeneratedValue.NOW) @H2Type("TIMESTAMP WITH TIME ZONE") Instant createdAt
    ) {
    }
}
