package eu.mikart.tava.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import eu.mikart.tava.capability.*;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.*;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class MongoAdapter implements Adapter {
    private final MongoClient client;
    private final MongoDatabase database;
    private final boolean owned;
    private final EntityStore entities = new Store();
    private final SchemaManager schemas = new Schemas();

    MongoAdapter(MongoClient client, String database, boolean owned) {
        this.client = Objects.requireNonNull(client);
        this.database = client.getDatabase(Objects.requireNonNull(database));
        this.owned = owned;
    }

    @Override
    public String name() {
        return "mongodb";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder(name())
                .supported(Feature.SECONDARY_INDEXES, Feature.TRANSACTIONS, Feature.CONDITIONAL_WRITES,
                        Feature.SORTING, Feature.PAGINATION, Feature.PROJECTION, Feature.AGGREGATION,
                        Feature.TTL, Feature.FULL_TEXT, Feature.GENERATED_VALUES, Feature.JSON,
                        Feature.BINARY, Feature.BULK_READ, Feature.BULK_WRITE)
                .set(Feature.SCHEMA_ENFORCEMENT, SupportLevel.EMULATED, "MongoDB JSON Schema validators")
                .set(Feature.UNIQUE_CONSTRAINTS, SupportLevel.EMULATED, "Unique indexes")
                .set(Feature.FOREIGN_KEYS, SupportLevel.UNSUPPORTED, "MongoDB does not enforce foreign keys")
                .set(Feature.JOINS, SupportLevel.NATIVE_ONLY, "Use aggregation $lookup")
                .build();
    }

    @Override
    public SchemaManager schemas() {
        return schemas;
    }

    @Override
    public EntityStore entities() {
        return entities;
    }

    @Override
    public NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            public <T> T nativeHandle(Class<T> type) {
                if (type == MongoClient.class) return type.cast(client);
                if (type == MongoDatabase.class) return type.cast(database);
                throw new IllegalArgumentException("MongoDB adapter exposes MongoClient and MongoDatabase");
            }
        };
    }

    @Override
    public void close() {
        if (owned) client.close();
    }

    private MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    private final class Store implements EntityStore {
        @Override
        public EntityRecord insert(String entity, EntityRecord record) {
            Document document = new Document(record.values());
            collection(entity).insertOne(document);
            return EntityRecord.of(document);
        }

        @Override
        public Page<EntityRecord> find(String entity, Query query) {
            var result = collection(entity).find(filter(query.predicate()));
            if (!query.projection().isEmpty()) {
                Document projection = new Document();
                query.projection().forEach(field -> projection.append(field, 1));
                result = result.projection(projection);
            }
            if (!query.sorting().isEmpty()) {
                Document sort = new Document();
                query.sorting().forEach(value -> sort.append(value.field(),
                        value.direction() == eu.mikart.tava.query.Sort.Direction.ASC ? 1 : -1));
                result = result.sort(sort);
            }
            int offset = decode(query.cursor());
            int requested = query.limit() == 0 ? 500 : query.limit();
            List<Document> documents = result.skip(offset).limit(requested + 1).into(new ArrayList<>());
            boolean more = documents.size() > requested;
            if (more) documents.removeLast();
            return new Page<>(documents.stream().map(EntityRecord::of).toList(),
                    more ? encode(offset + requested) : null);
        }

        @Override
        public long update(String entity, Predicate predicate, Mutation mutation) {
            if (mutation.values().isEmpty()) return 0;
            List<Bson> updates = mutation.values().entrySet().stream()
                    .map(entry -> Updates.set(entry.getKey(), entry.getValue())).map(Bson.class::cast).toList();
            return collection(entity).updateMany(filter(predicate), Updates.combine(updates)).getModifiedCount();
        }

        @Override
        public long delete(String entity, Predicate predicate) {
            return collection(entity).deleteMany(filter(predicate)).getDeletedCount();
        }
    }

    private final class Schemas implements SchemaManager {
        @Override
        public Schema inspect() {
            List<EntityDefinition> entities = new ArrayList<>();
            for (String name : database.listCollectionNames()) {
                entities.add(new EntityDefinition(name, List.of(), List.of(), Map.of("mongodb.schema", "dynamic")));
            }
            return new Schema(entities);
        }

        @Override
        public SchemaPlan plan(Schema desired) {
            Set<String> current = new HashSet<>(database.listCollectionNames().into(new ArrayList<>()));
            List<SchemaChange> changes = new ArrayList<>();
            List<Runnable> operations = new ArrayList<>();
            for (var entity : desired.entities()) {
                if (!current.contains(entity.name())) {
                    changes.add(new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, null));
                    operations.add(() -> database.createCollection(entity.name()));
                }
                for (var field : entity.fields()) {
                    if (field.unique()) {
                        String index = entity.name() + "_" + field.name() + "_unique";
                        changes.add(new SchemaChange("Create unique index " + index, ChangeRisk.SAFE, null));
                        operations.add(() -> collection(entity.name()).createIndex(
                                new Document(field.name(), 1), new IndexOptions().name(index).unique(true)));
                    }
                }
                for (var index : entity.indexes()) {
                    Document keys = new Document();
                    index.fields().forEach(field -> keys.append(field, 1));
                    changes.add(new SchemaChange("Create index " + index.name(), ChangeRisk.SAFE, null));
                    operations.add(() -> collection(entity.name()).createIndex(keys,
                            new IndexOptions().name(index.name()).unique(index.unique())));
                }
            }
            return new SchemaPlan(changes, () -> operations.forEach(Runnable::run));
        }
    }

    private Bson filter(Predicate predicate) {
        if (predicate == null || predicate instanceof Predicate.All) return new Document();
        if (predicate instanceof Predicate.Comparison(String field, Predicate.Operator operator, Object value1)) {
            return switch (operator) {
                case EQ -> new Document(field, value1);
                case NE -> new Document(field, new Document("$ne", value1));
                case LT -> new Document(field, new Document("$lt", value1));
                case LTE -> new Document(field, new Document("$lte", value1));
                case GT -> new Document(field, new Document("$gt", value1));
                case GTE -> new Document(field, new Document("$gte", value1));
                case IN -> new Document(field, new Document("$in", value1));
                case CONTAINS -> new Document(field, new Document("$regex",
                        java.util.regex.Pattern.quote(value1.toString())));
                case STARTS_WITH -> new Document(field, new Document("$regex",
                        "^" + java.util.regex.Pattern.quote(value1.toString())));
            };
        }
        Predicate.Junction junction = (Predicate.Junction) predicate;
        return new Document(junction.and() ? "$and" : "$or",
                junction.predicates().stream().map(this::filter).toList());
    }

    private static int decode(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid cursor", failure);
        }
    }

    private static String encode(int value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(value).getBytes(StandardCharsets.UTF_8));
    }
}
