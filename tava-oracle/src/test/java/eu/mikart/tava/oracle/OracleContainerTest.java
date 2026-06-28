package eu.mikart.tava.oracle;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.testkit.AdapterContractTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class OracleContainerTest extends AdapterContractTest {
    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
            .withUsername("tava")
            .withPassword("tava");

    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(Oracle.connect(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword()));
    }

    @Test
    void runsCrudPredicatesAndPagination() {
        try (Tava tava = Tava.open(Oracle.connect(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword()))) {
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
                    .where(Predicate.eq("note", null)).limit(10).build()).items().size());
            assertEquals(1, tava.records("accounts").update(Predicate.eq("id", "a"),
                    Mutation.builder().set("score", 8).build()));
            assertEquals(1, tava.records("accounts").find(Query.builder()
                    .where(Predicate.startsWith("email", "grace")).limit(1).build()).items().size());
            assertEquals(1, tava.records("accounts").delete(Predicate.eq("id", "g")));
        }
    }
}
