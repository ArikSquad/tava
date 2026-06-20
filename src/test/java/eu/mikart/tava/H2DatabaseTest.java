package eu.mikart.tava;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2DatabaseTest {
	@Test
	void schemaAndCrud() throws Exception {
		Database database = Database.connect(Dialects.h2("test-" + UUID.randomUUID()));
		database.createTable("item", table -> {
			table.uuid("id").primary();
			table.string("name", 80);
		});
		UUID id = UUID.randomUUID();
		assertEquals(1, database.insert("item", row -> row.set("id", id).set("name", "one")));
		assertEquals("one", database.select("item", Item.class, query -> query.eq("id", id)).getFirst().name());
	}

	record Item(UUID id, String name) {}
}
