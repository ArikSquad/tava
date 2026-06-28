package eu.mikart.tava.mysql;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.testkit.AdapterContractTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class MySqlContainerTest extends AdapterContractTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.2");

    @Container
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.8");

    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(MySql.connect(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void mysqlRunsSchemaAndCrudContract() {
        try (Tava tava = Tava.open(MySql.connect(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()))) {
            assertContract(tava);
        }
    }

    @Test
    void mariadbRunsSchemaAndCrudContract() {
        try (Tava tava = Tava.open(MySql.mariaDb(MARIADB.getJdbcUrl(),
                MARIADB.getUsername(), MARIADB.getPassword()))) {
            assertContract(tava);
        }
    }

    private static void assertContract(Tava tava) {
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
                .where(Predicate.eq("note", null)).build()).items().size());
        assertEquals(1, tava.records("accounts").update(Predicate.eq("id", "a"),
                Mutation.builder().set("score", 8).build()));
        assertEquals(1, tava.records("accounts").find(Query.builder()
                .where(Predicate.gte("score", 9)).build()).items().size());
        assertEquals(1, tava.records("accounts").delete(Predicate.contains("email", "grace")));
    }
}
