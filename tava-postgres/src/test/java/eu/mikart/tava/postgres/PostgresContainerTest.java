package eu.mikart.tava.postgres;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.Schema;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresContainerTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void runsCrudPredicatesPagingAndNativeAccess() {
        try (Tava tava = Tava.open(Postgres.connect(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()))) {
            Schema schema = accounts();
            tava.plan(schema).apply();
            UUID ada = UUID.fromString("018f2c20-0583-7c29-baa5-37734bb2d102");
            UUID grace = UUID.fromString("018f2c20-0583-7c29-baa5-37734bb2d103");

            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", ada)
                    .set("email", "ada@example.com")
                    .set("score", 7)
                    .set("note", null)
                    .build());
            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", grace)
                    .set("email", "grace@example.com")
                    .set("score", 9)
                    .set("note", "compiler")
                    .build());

            assertEquals(1, tava.records("accounts").find(Query.builder()
                    .where(Predicate.eq("note", null)).build()).items().size());
            assertEquals(1, tava.records("accounts").update(Predicate.eq("email", "ada@example.com"),
                    Mutation.builder().set("score", 8).build()));

            var firstPage = tava.records("accounts").find(Query.builder()
                    .sort(Sort.asc("email")).limit(1).build());
            assertEquals(List.of("ada@example.com"), firstPage.items().stream()
                    .map(record -> record.get("email")).toList());
            assertNotNull(firstPage.nextCursor());

            var secondPage = tava.records("accounts").find(Query.builder()
                    .sort(Sort.asc("email")).limit(1).cursor(firstPage.nextCursor()).build());
            assertEquals(List.of("grace@example.com"), secondPage.items().stream()
                    .map(record -> record.get("email")).toList());
            assertNull(secondPage.nextCursor());

            try (Connection connection = tava.nativeHandle(Connection.class)) {
                assertTrue(connection.isValid(2));
            }
            assertEquals(1, tava.records("accounts").delete(Predicate.startsWith("email", "grace")));
        } catch (Exception failure) {
            fail(failure);
        }
    }

    private static Schema accounts() {
        return Schema.builder().entity("accounts", entity -> {
            entity.uuid("id").identity();
            entity.string("email").required().unique().field(255);
            entity.integer("score").required();
            entity.string("note").field(255);
        }).build();
    }
}
