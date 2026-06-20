package eu.mikart.tava.mongo;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Small, explicit wrapper around the MongoDB synchronous driver.
 * Documents remain BSON {@link Document}s so the API does not hide MongoDB semantics.
 */
public final class MongoDatabase implements AutoCloseable {
	private final MongoClient client;
	private final com.mongodb.client.MongoDatabase database;

	private MongoDatabase(MongoClient client, String database) {
		this.client = client;
		this.database = client.getDatabase(database);
	}

	public static MongoDatabase connect(String connectionString, String database) {
		Objects.requireNonNull(connectionString, "connectionString");
		Objects.requireNonNull(database, "database");
		return new MongoDatabase(MongoClients.create(connectionString), database);
	}

	public MongoCollection<Document> collection(String name) {
		return database.getCollection(name);
	}

	public void insert(String collection, Document document) {
		collection(collection).insertOne(document);
	}

	public void insert(String collection, List<Document> documents) {
		if (!documents.isEmpty()) collection(collection).insertMany(documents);
	}

	public List<Document> find(String collection) {
		return find(collection, new Query());
	}

	public List<Document> find(String collection, Query query) {
		FindIterable<Document> result = collection(collection).find(query.filter);
		if (query.sort != null) result = result.sort(query.sort);
		if (query.skip > 0) result = result.skip(query.skip);
		if (query.limit > 0) result = result.limit(query.limit);
		return result.into(new ArrayList<>());
	}

	public long update(String collection, Bson filter, Bson update) {
		return collection(collection).updateMany(filter, update).getModifiedCount();
	}

	public long delete(String collection, Bson filter) {
		return collection(collection).deleteMany(filter).getDeletedCount();
	}

	public String createIndex(String collection, Bson keys, boolean unique) {
		return collection(collection).createIndex(keys, new IndexOptions().unique(unique));
	}

	@Override
	public void close() {
		client.close();
	}

	public static final class Query {
		private Bson filter = new Document();
		private Bson sort;
		private int skip;
		private int limit;

		public Query filter(Bson filter) {
			this.filter = Objects.requireNonNull(filter);
			return this;
		}

		public Query eq(String field, Object value) {
			return filter(Filters.eq(field, value));
		}

		public Query ascending(String... fields) {
			this.sort = Sorts.ascending(fields);
			return this;
		}

		public Query descending(String... fields) {
			this.sort = Sorts.descending(fields);
			return this;
		}

		public Query skip(int skip) {
			if (skip < 0) throw new IllegalArgumentException("skip must be >= 0");
			this.skip = skip;
			return this;
		}

		public Query limit(int limit) {
			if (limit < 1) throw new IllegalArgumentException("limit must be > 0");
			this.limit = limit;
			return this;
		}
	}
}
