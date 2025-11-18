package eu.mikart.tava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DatabaseFeatureTest {

	private Path dbFile;
	private Database db;

	@BeforeEach
	void setup() throws SQLException, IOException {
		dbFile = Files.createTempFile("tava_test", ".sqlite");
		db = Database.builder()
				.dialect(Dialects.sqlite(dbFile))
				.migration(new CreateChatMessages())
				.build();
		db.initialize();
	}

	@AfterEach
	void cleanup() throws IOException {
		Files.deleteIfExists(dbFile);
	}

	@Test
	void migrationApplied() throws SQLException {
		try (Connection c = db.connection(); ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM __metadata")) {
			Assertions.assertTrue(rs.next());
			int count = rs.getInt(1);
			Assertions.assertTrue(count >= 1);
		}
	}

	@Test
	void createTableDslAndInsertSelectUpdateDelete() throws SQLException {
		db.createTable("direct_table", t -> {
			t.uuid("id").primary().end();
			t.string("name", 100).nullAllowed(false).end();
		});
		UUID id = UUID.randomUUID();
		int inserted = db.insert("direct_table", ib -> {
			ib.uuid("id", id.toString());
			ib.string("name", "Alice");
		});
		Assertions.assertEquals(1, inserted);
		List<Direct> result = db.select("direct_table", Direct.class, sb -> sb.eq("name", "Alice"));
		Assertions.assertEquals(1, result.size());
		Assertions.assertEquals("Alice", result.get(0).name());
		int updated = db.update("direct_table", ub -> ub.set("name", "Bob").eq("id", id.toString()));
		Assertions.assertEquals(1, updated);
		List<Direct> afterUpdate = db.select("direct_table", Direct.class, sb -> sb.eq("id", id.toString()));
		Assertions.assertEquals("Bob", afterUpdate.get(0).name());
		int deleted = db.delete("direct_table", dbld -> dbld.eq("id", id.toString()));
		Assertions.assertEquals(1, deleted);
		List<Direct> afterDelete = db.select("direct_table", Direct.class, sb -> sb.eq("id", id.toString()));
		Assertions.assertEquals(0, afterDelete.size());
	}

	@Test
	void chatMessageDefaultNowAndOrdering() throws SQLException, InterruptedException {
		db.createTable("message", t -> {
			t.uuid("unique_id").primary().end();
			t.string("content", 100).nullAllowed(false).end();
			t.instant("created_at").defaultNow().nullAllowed(false).end();
		});
		UUID id1 = UUID.randomUUID();
		UUID id2 = UUID.randomUUID();
		db.insert("message", ib -> {
			ib.uuid("unique_id", id1.toString());
			ib.string("content", "First");
		});
		Thread.sleep(10);
		db.insert("message", ib -> {
			ib.uuid("unique_id", id2.toString());
			ib.string("content", "Second");
		});
		List<ChatMessage> ordered = db.select("message", ChatMessage.class, sb -> sb.orderByDesc("created_at"));
		Assertions.assertEquals(2, ordered.size());
		Assertions.assertNotNull(ordered.get(0).created_at());
		Assertions.assertTrue(ordered.get(0).created_at().isAfter(ordered.get(1).created_at()) || ordered.get(0).created_at().equals(ordered.get(1).created_at()));
	}

	@Test
	void limitAndEqFilter() throws SQLException {
		for (int i = 0; i < 5; i++) {
			db.insert("chat_message", ib -> {
				ib.uuid("unique_id", UUID.randomUUID().toString());
				ib.string("content", "FilterMe");
			});
		}
		List<ChatMessage> limited = db.select("chat_message", ChatMessage.class, sb -> {
			sb.eq("content", "FilterMe");
			sb.limit(3);
			sb.orderByAsc("created_at");
		});
		Assertions.assertEquals(3, limited.size());
	}

	@Test
	void notNullConstraintViolation() throws SQLException {
		db.createTable("nn_table", t -> {
			t.uuid("id").primary().end();
			t.string("name").nullAllowed(false).end();
		});
		UUID id = UUID.randomUUID();
		Assertions.assertThrows(SQLException.class, () -> db.insert("nn_table", ib -> {
			ib.uuid("id", id.toString());
			ib.string("name", null);
		}));
	}

	record ChatMessage(String unique_id, String content, Instant created_at) {
	}

	record Direct(String id, String name) {
	}
}
