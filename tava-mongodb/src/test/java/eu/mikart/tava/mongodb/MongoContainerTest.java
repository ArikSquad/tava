package eu.mikart.tava.mongodb;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.testkit.AdapterContractTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MongoContainerTest extends AdapterContractTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(Mongo.connect(MONGO.getConnectionString(), namespace));
    }

    @Override
    protected boolean supportsIsolatedTransferTargets() {
        return true;
    }

    @Test
    void runsSchemaCrudProjectionAndPaging() {
        try (Tava tava = Tava.open(Mongo.connect(MONGO.getConnectionString(), "tava"))) {
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

            var page = tava.records("accounts").find(Query.builder()
                    .sort(Sort.asc("email")).project("email").limit(1).build());
            assertEquals(List.of("ada@example.com"), page.items().stream()
                    .map(record -> record.get("email")).toList());
            assertTrue(page.items().getFirst().values().containsKey("_id"));
            assertFalse(page.items().getFirst().values().containsKey("score"));
            assertEquals(1, tava.records("accounts").delete(Predicate.contains("email", "grace")));
        }
    }
}
