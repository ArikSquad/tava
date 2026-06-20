package eu.mikart.tava.sqlite;

import eu.mikart.tava.Tava;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.annotation.Identity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteMemoryTest {
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
}
