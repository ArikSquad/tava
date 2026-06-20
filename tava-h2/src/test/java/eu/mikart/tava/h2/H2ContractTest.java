package eu.mikart.tava.h2;

import eu.mikart.tava.Tava;
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

    record Account(
            @Identity UUID id,
            @Required @Unique @Field(length = 200) String email,
            int score,
            @Generated(GeneratedValue.NOW) @H2Type("TIMESTAMP WITH TIME ZONE") Instant createdAt
    ) {
    }
}
