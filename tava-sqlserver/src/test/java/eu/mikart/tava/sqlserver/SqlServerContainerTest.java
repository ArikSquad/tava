package eu.mikart.tava.sqlserver;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.Schema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SqlServerContainerTest {
    @Container
    static final MSSQLServerContainer<?> SQL_SERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @Test
    void runsCrudPredicatesAndOffsetPagination() {
        try (Tava tava = Tava.open(SqlServer.connect(SQL_SERVER.getJdbcUrl(),
                SQL_SERVER.getUsername(), SQL_SERVER.getPassword()))) {
            tava.plan(Schema.builder().entity("accounts", entity -> {
                entity.string("id").identity().field(36);
                entity.string("email").required().unique().field(255);
                entity.integer("score").required();
                entity.string("note").field(255);
            }).build()).apply();

            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", "a")
                    .set("email", "ada@example.com")
                    .set("score", 7)
                    .set("note", null)
                    .build());
            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", "g")
                    .set("email", "grace@example.com")
                    .set("score", 9)
                    .set("note", "compiler")
                    .build());

            assertEquals(1, tava.records("accounts").find(Query.builder()
                    .where(Predicate.ne("note", null)).build()).items().size());
            assertEquals(1, tava.records("accounts").update(Predicate.eq("id", "a"),
                    Mutation.builder().set("score", 8).build()));

            var page = tava.records("accounts").find(Query.builder()
                    .sort(Sort.asc("email")).limit(1).build());
            assertEquals(List.of("ada@example.com"), page.items().stream()
                    .map(record -> record.get("email")).toList());
            assertNotNull(page.nextCursor());
            assertEquals(1, tava.records("accounts").delete(Predicate.eq("id", "g")));
        }
    }
}
