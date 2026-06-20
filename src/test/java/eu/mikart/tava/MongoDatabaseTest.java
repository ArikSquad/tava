package eu.mikart.tava;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import eu.mikart.tava.mongo.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class MongoDatabaseTest {
	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

	@Test
	void documentCrudAndIndexes() {
		try (MongoDatabase database = MongoDatabase.connect(MONGO.getConnectionString(), "tava")) {
			database.createIndex("accounts", Indexes.ascending("email"), true);
			database.insert("accounts", new Document("email", "ada@example.com").append("score", 7));

			assertEquals(1, database.find("accounts", new MongoDatabase.Query()
					.eq("email", "ada@example.com")
					.descending("score")
					.limit(10)).size());
			assertEquals(1, database.update(
					"accounts",
					Filters.eq("email", "ada@example.com"),
					Updates.set("score", 8)
			));
			assertEquals(1, database.delete("accounts", Filters.eq("email", "ada@example.com")));
		}
	}
}
