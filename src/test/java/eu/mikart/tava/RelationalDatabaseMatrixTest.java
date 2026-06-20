package eu.mikart.tava;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
class RelationalDatabaseMatrixTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.4");

	@Test
	void postgres() throws Exception {
		verify(Dialects.jdbc("postgres", POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	@Test
	void mysql() throws Exception {
		verify(Dialects.jdbc("mysql", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
	}

	private void verify(Dialect dialect) throws Exception {
		Database database = Database.connect(dialect);
		database.createTable("account", table -> {
			table.uuid("id").primary();
			table.string("name", 120).unique();
			table.integer("score");
			table.bool("active");
			table.instant("created_at").defaultNow();
		});

		UUID id = UUID.randomUUID();
		assertEquals(1, database.insert("account", row -> {
			row.set("id", id);
			row.set("name", "Ada");
			row.set("score", 7);
			row.set("active", true);
		}));

		List<Account> rows = database.select("account", Account.class, query ->
				query.eq("id", id).orderByAsc("name").limit(1));
		assertEquals(1, rows.size());
		assertEquals("Ada", rows.getFirst().name());
		assertNotNull(rows.getFirst().created_at());

		assertEquals(1, database.update("account", row -> row.set("score", 8).eq("id", id)));
		assertEquals(1, database.delete("account", query -> query.eq("id", id)));
	}

	record Account(UUID id, String name, int score, boolean active, Instant created_at) {}
}
