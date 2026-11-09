package eu.mikart.tava.mongodb;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationAlternate;
import com.mongodb.client.model.CollationCaseFirst;
import com.mongodb.client.model.CollationMaxVariable;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.*;
import eu.mikart.tava.data.ConflictDetail;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.*;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.jetbrains.annotations.NotNull;

final class MongoAdapter implements Adapter {
    private final MongoClient client;
    private final MongoDatabase database;
    private final boolean owned;
    private final EntityStore entities = new Store(null);
    private final SchemaManager schemas = new Schemas();
    private final Map<String, EntityDefinition> definitions = new ConcurrentHashMap<>();

    MongoAdapter(MongoClient client, String database, boolean owned) {
        this.client = Objects.requireNonNull(client);
        this.database = client.getDatabase(Objects.requireNonNull(database));
        this.owned = owned;
    }

    @Override
    public @NotNull String name() {
        return "mongodb";
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.builder(name())
                .supported(
                        Feature.SECONDARY_INDEXES,
                        Feature.TRANSACTIONS,
                        Feature.CONDITIONAL_WRITES,
                        Feature.SORTING,
                        Feature.PAGINATION,
                        Feature.PROJECTION,
                        Feature.GENERATED_VALUES,
                        Feature.JSON,
                        Feature.BINARY,
                        Feature.BULK_READ)
                .set(
                        Feature.BULK_WRITE,
                        SupportLevel.SUPPORTED,
                        "Atomic batch inserts require a replica set or sharded cluster")
                .set(Feature.TTL, SupportLevel.NATIVE_ONLY, "Configure TTL indexes through MongoDatabase")
                .set(
                        Feature.FULL_TEXT,
                        SupportLevel.NATIVE_ONLY,
                        "Configure and query text indexes through MongoDatabase")
                .set(Feature.AGGREGATION, SupportLevel.NATIVE_ONLY, "Use MongoDB aggregation pipelines")
                .set(Feature.SCHEMA_ENFORCEMENT, SupportLevel.EMULATED, "Strict MongoDB JSON Schema validators")
                .set(Feature.UNIQUE_CONSTRAINTS, SupportLevel.EMULATED, "Unique indexes")
                .set(Feature.FOREIGN_KEYS, SupportLevel.UNSUPPORTED, "MongoDB does not enforce foreign keys")
                .set(Feature.JOINS, SupportLevel.NATIVE_ONLY, "Use aggregation $lookup")
                .build();
    }

    @Override
    public @NotNull SchemaManager schemas() {
        return schemas;
    }

    @Override
    public @NotNull EntityStore entities() {
        return entities;
    }

    @Override
    public @NotNull TransactionManager transactions() {
        return new TransactionManager() {
            @Override
            public <R> R transaction(@NotNull Function<EntityStore, R> callback) {
                try (ClientSession session = client.startSession()) {
                    return session.withTransaction(() -> callback.apply(new Store(session)));
                }
            }
        };
    }

    @Override
    public @NotNull NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            @SuppressWarnings("deprecation")
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
        private final ClientSession session;

        private Store(ClientSession session) {
            this.session = session;
        }

        @Override
        public @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record) {
            Document document = prepare(entity, record);
            if (session == null) collection(entity).insertOne(document);
            else collection(entity).insertOne(session, document);
            return EntityRecord.of(readDocument(document));
        }

        private Document prepare(String entity, EntityRecord record) {
            Map<String, Object> values = new LinkedHashMap<>(record.values());
            for (FieldDefinition field : definition(entity).fields()) {
                if (values.containsKey(field.name())) continue;
                if (field.generated() == GeneratedValue.NOW)
                    values.put(field.name(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
                if (field.generated() == GeneratedValue.UUID) values.put(field.name(), UUID.randomUUID());
            }
            return document(values);
        }

        @Override
        public @NotNull List<EntityRecord> insert(@NotNull String entity, @NotNull List<EntityRecord> records) {
            if (records.isEmpty()) return List.of();
            List<Document> documents =
                    records.stream().map(record -> prepare(entity, record)).toList();
            if (session != null) {
                collection(entity).insertMany(session, documents);
            } else {
                try (ClientSession batchSession = client.startSession()) {
                    batchSession.withTransaction(() -> {
                        collection(entity).insertMany(batchSession, documents);
                        return null;
                    });
                }
            }
            return documents.stream()
                    .map(value -> EntityRecord.of(readDocument(value)))
                    .toList();
        }

        @Override
        public @NotNull MutationResult insertResult(@NotNull String entity, @NotNull EntityRecord record) {
            try {
                insert(entity, record);
                return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
            } catch (MongoWriteException failure) {
                if (ErrorCategory.fromErrorCode(failure.getCode()) != ErrorCategory.DUPLICATE_KEY) throw failure;
                return new MutationResult(
                        0,
                        0,
                        1,
                        MutationResult.Outcome.CONFLICT,
                        List.of(new ConflictDetail(
                                ConflictDetail.Kind.UNIQUE,
                                null,
                                Integer.toString(failure.getCode()),
                                failure.getError().getMessage())));
            }
        }

        @Override
        public @NotNull Page<EntityRecord> find(@NotNull String entity, @NotNull Query query) {
            List<Sort> order = KeysetPagination.ordering(query.sorting(), List.of("_id"));
            for (var sort : order) {
                if (sort.field().equals("_id")) continue;
                FieldDefinition field = definition(entity).fields().stream()
                        .filter(value -> value.name().equals(sort.field()))
                        .findFirst()
                        .orElseThrow(() -> new TavaException.Capability(
                                "Keyset sort requires a declared scalar field: " + sort.field()));
                if (field.type().logicalType() == LogicalType.JSON
                        || sort.field().contains("."))
                    throw new TavaException.Capability(
                            "Keyset sort does not support document or array fields: " + sort.field());
            }
            Bson original = filter(query.predicate());
            String filterSignature = original.toBsonDocument(Document.class, database.getCodecRegistry())
                    .toJson(JsonWriterSettings.builder()
                            .outputMode(JsonMode.EXTENDED)
                            .build());
            Query signature = new Query(
                    Predicate.eq("filter", filterSignature), query.projection(), query.sorting(), query.limit(), null);
            Bson match = original;
            if (query.cursor() != null) {
                List<Object> entries = KeysetCursor.decode(
                        query.cursor(), "mongodb:" + database.getName() + ":" + entity, signature, order);
                List<Object> values = entries.stream()
                        .map(value -> Document.parse((String) value).get("value"))
                        .toList();
                match = new Document("$and", List.of(original, continuation(order, values)));
            }
            var result = session == null
                    ? collection(entity).find(match)
                    : collection(entity).find(session, match);
            if (!query.projection().isEmpty()) {
                Document projection = new Document();
                query.projection().forEach(field -> projection.append(field, 1));
                order.forEach(sort -> projection.append(sort.field(), 1));
                result = result.projection(projection);
            }
            Document sort = new Document();
            order.forEach(value -> sort.append(value.field(), value.direction() == Sort.Direction.ASC ? 1 : -1));
            int requested = query.limit() == 0 ? 500 : query.limit();
            List<Document> documents = result.sort(sort).limit(requested + 1).into(new ArrayList<>());
            boolean more = documents.size() > requested;
            if (more) documents.removeLast();
            String cursor = null;
            if (more) {
                Document last = documents.getLast();
                List<String> values = order.stream()
                        .map(value -> new Document("value", last.get(value.field()))
                                .toJson(JsonWriterSettings.builder()
                                        .outputMode(JsonMode.EXTENDED)
                                        .build()))
                        .toList();
                cursor = KeysetCursor.encode("mongodb:" + database.getName() + ":" + entity, signature, order, values);
            }
            if (!query.projection().isEmpty()) {
                for (Document document : documents)
                    for (var field : order) {
                        if (!field.field().equals("_id") && !query.projection().contains(field.field()))
                            document.remove(field.field());
                    }
            }
            return new Page<>(
                    documents.stream()
                            .map(document -> EntityRecord.of(readDocument(document)))
                            .toList(),
                    cursor);
        }

        @Override
        public long update(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            List<Bson> updates = updateDocuments(mutation);
            if (updates.isEmpty()) return 0;
            return (session == null
                            ? collection(entity).updateMany(filter(predicate), Updates.combine(updates))
                            : collection(entity).updateMany(session, filter(predicate), Updates.combine(updates)))
                    .getModifiedCount();
        }

        @Override
        public @NotNull MutationResult updateResult(
                @NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            List<Bson> updates = updateDocuments(mutation);
            if (updates.isEmpty()) return new MutationResult(0, 0, 0, MutationResult.Outcome.UNCHANGED);
            var result = session == null
                    ? collection(entity).updateMany(filter(predicate), Updates.combine(updates))
                    : collection(entity).updateMany(session, filter(predicate), Updates.combine(updates));
            return new MutationResult(
                    result.getMatchedCount(),
                    result.getModifiedCount(),
                    0,
                    result.getModifiedCount() == 0 ? MutationResult.Outcome.UNCHANGED : MutationResult.Outcome.UPDATED);
        }

        @Override
        public long delete(@NotNull String entity, @NotNull Predicate predicate) {
            return (session == null
                            ? collection(entity).deleteMany(filter(predicate))
                            : collection(entity).deleteMany(session, filter(predicate)))
                    .getDeletedCount();
        }

        @Override
        public @NotNull MutationResult upsert(
                @NotNull String entity,
                @NotNull Predicate identity,
                @NotNull EntityRecord insert,
                @NotNull Mutation update) {
            MongoCollection<Document> target = collection(entity);
            Bson match = filter(identity);

            // A native upsert applies every update operator to the synthesized insert as well.
            // It is equivalent to the API's insert/update branches only when the update consists
            // of values already present in the insert record and those values are identical.
            boolean nativeUpsert = (!insert.values().isEmpty()
                            || !update.values().isEmpty())
                    && update.operations().isEmpty()
                    && update.values().entrySet().stream()
                            .allMatch(entry -> insert.values().containsKey(entry.getKey())
                                    && Objects.deepEquals(insert.values().get(entry.getKey()), entry.getValue()));
            if (nativeUpsert) {
                Document initial = prepare(entity, insert);
                List<Bson> changes =
                        new ArrayList<>(initial.size() + update.values().size());
                initial.forEach((field, value) -> {
                    if (!update.values().containsKey(field)) changes.add(Updates.setOnInsert(field, value));
                });
                update.values().forEach((field, value) -> changes.add(Updates.set(field, writeValue(value))));
                var options = new UpdateOptions().upsert(true);
                var result = session == null
                        ? target.updateOne(match, Updates.combine(changes), options)
                        : target.updateOne(session, match, Updates.combine(changes), options);
                boolean inserted = result.getUpsertedId() != null;
                return new MutationResult(
                        result.getMatchedCount(),
                        result.getModifiedCount(),
                        0,
                        inserted ? MutationResult.Outcome.INSERTED : MutationResult.Outcome.UPDATED);
            }

            // Keep update operators off the insert branch. Both writes are atomic, and the
            // duplicate-key retry handles another upsert winning the gap between them.
            if (!update.values().isEmpty() || !update.operations().isEmpty()) {
                var result = session == null
                        ? target.updateOne(match, Updates.combine(updateDocuments(update)))
                        : target.updateOne(session, match, Updates.combine(updateDocuments(update)));
                if (result.getMatchedCount() > 0) {
                    return new MutationResult(
                            result.getMatchedCount(), result.getModifiedCount(), 0, MutationResult.Outcome.UPDATED);
                }
            }

            Document document = prepare(entity, insert);
            try {
                if (session == null) target.insertOne(document);
                else target.insertOne(session, document);
                return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
            } catch (MongoWriteException failure) {
                if (ErrorCategory.fromErrorCode(failure.getCode()) != ErrorCategory.DUPLICATE_KEY) throw failure;
                // A duplicate-key error aborts a transaction; retry only outside a session.
                if (session == null
                        && (!update.values().isEmpty() || !update.operations().isEmpty())) {
                    var result = target.updateOne(match, Updates.combine(updateDocuments(update)));
                    if (result.getMatchedCount() > 0) {
                        return new MutationResult(
                                result.getMatchedCount(), result.getModifiedCount(), 0, MutationResult.Outcome.UPDATED);
                    }
                }
                throw failure;
            }
        }

        private List<Bson> updateDocuments(Mutation mutation) {
            List<Bson> changes = new ArrayList<>(
                    mutation.values().size() + mutation.operations().size());
            mutation.values().forEach((field, value) -> changes.add(Updates.set(field, writeValue(value))));
            mutation.operations()
                    .forEach((field, operation) -> changes.add(
                            switch (operation.kind()) {
                                case INCREMENT -> Updates.inc(field, (Number) operation.value());
                                case APPEND_IF_ABSENT, APPEND -> Updates.addToSet(field, writeValue(operation.value()));
                                case REMOVE -> Updates.pull(field, writeValue(operation.value()));
                            }));
            return changes;
        }
    }

    private EntityDefinition describe(Document info) {
        String name = info.getString("name");
        List<IndexDefinition> indexes = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Set<String> unique = new HashSet<>();
        for (Document index : collection(name).listIndexes()) {
            Document keys = index.get("key", Document.class);
            String indexName = index.getString("name");
            List<String> fields = List.copyOf(keys.keySet());
            boolean isUnique = index.getBoolean("unique", false) || "_id_".equals(indexName);
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("mongodb.keys", keys);
            index.forEach((option, value) -> {
                if (Set.of("key", "name", "unique", "v").contains(option)) return;
                Object normalized = normalizeIndexSetting(option, value);
                if (!(Boolean.FALSE.equals(normalized)
                        && Set.of("background", "hidden", "sparse").contains(option)))
                    settings.put("mongodb." + option, normalized);
            });
            indexes.add(new IndexDefinition(indexName, fields, isUnique, settings));
            if ((name + "_identity").equals(indexName) && isUnique) identities.addAll(fields);
            if (isUnique
                    && fields.size() == 1
                    && !index.getBoolean("sparse", false)
                    && index.get("partialFilterExpression") == null) unique.add(fields.getFirst());
        }
        indexes.sort(Comparator.comparing(IndexDefinition::name));
        Document options = info.get("options", Document.class);
        Document validator = options == null ? new Document() : options.get("validator", Document.class);
        if (validator == null) validator = new Document();
        Document json = validator.get("$jsonSchema", Document.class);
        List<FieldDefinition> fields = new ArrayList<>();
        if (json != null) {
            Document properties = json.get("properties", Document.class);
            List<String> required = json.getList("required", String.class, List.of());
            if (properties != null)
                properties.forEach((field, raw) -> {
                    Document property = (Document) raw;
                    String description = property.getString("description");
                    if (description == null || !description.startsWith("tava:")) return;
                    Document metadata = Document.parse(description.substring(5));
                    FieldType type = new FieldType(
                            LogicalType.valueOf(metadata.getString("type")),
                            metadata.getInteger("length"),
                            metadata.getInteger("precision"),
                            metadata.getInteger("scale"));
                    fields.add(new FieldDefinition(
                            field,
                            type,
                            !required.contains(field),
                            identities.contains(field),
                            unique.contains(field),
                            GeneratedValue.valueOf(metadata.getString("generated")),
                            Map.of()));
                });
        }
        fields.sort(Comparator.comparing(FieldDefinition::name));
        return new EntityDefinition(
                name,
                fields,
                indexes,
                Map.of(
                        "mongodb.validator",
                        validator,
                        "mongodb.validationLevel",
                        options == null
                                ? "strict"
                                : options.getString("validationLevel") == null
                                        ? "strict"
                                        : options.getString("validationLevel"),
                        "mongodb.validationAction",
                        options == null
                                ? "error"
                                : options.getString("validationAction") == null
                                        ? "error"
                                        : options.getString("validationAction")));
    }

    private EntityDefinition definition(String name) {
        return definitions.computeIfAbsent(name, key -> {
            Document info = database.listCollections()
                    .filter(new Document("name", name))
                    .first();
            return info == null ? new EntityDefinition(name, List.of(), List.of(), Map.of()) : describe(info);
        });
    }

    private Document property(FieldDefinition field) {
        List<String> bsonTypes = new ArrayList<>(
                switch (field.type().logicalType()) {
                    case STRING, TEXT, UUID, LOCAL_DATE, LOCAL_DATE_TIME -> List.of("string");
                    case INT32 -> List.of("int");
                    case INT64 -> List.of("long");
                    case FLOAT64 -> List.of("double");
                    case DECIMAL -> List.of("decimal");
                    case BOOLEAN -> List.of("bool");
                    case INSTANT -> List.of("date");
                    case BINARY -> List.of("binData");
                    case JSON -> List.of("object", "array");
                });
        if (field.nullable()) bsonTypes.add("null");
        Document metadata = new Document("type", field.type().logicalType().name())
                .append("generated", field.generated().name());
        if (field.type().length() != null)
            metadata.append("length", field.type().length());
        if (field.type().precision() != null)
            metadata.append("precision", field.type().precision());
        if (field.type().scale() != null) metadata.append("scale", field.type().scale());
        Document property = new Document("bsonType", bsonTypes).append("description", "tava:" + metadata.toJson());
        if (field.type().length() != null && field.type().logicalType() == LogicalType.STRING)
            property.append("maxLength", field.type().length());
        if (field.type().logicalType() == LogicalType.DECIMAL
                && field.type().precision() != null
                && field.type().precision() <= 34) {
            BigDecimal bound =
                    BigDecimal.TEN.pow(field.type().precision() - field.type().scale());
            property.append("minimum", new Decimal128(bound.negate()))
                    .append("exclusiveMinimum", true)
                    .append("maximum", new Decimal128(bound))
                    .append("exclusiveMaximum", true)
                    .append(
                            "multipleOf",
                            new Decimal128(BigDecimal.ONE.scaleByPowerOfTen(
                                    -field.type().scale())));
        }
        return property;
    }

    private final class Schemas implements SchemaManager {
        @Override
        public @NotNull Schema inspect() {
            definitions.clear();
            List<EntityDefinition> entities = new ArrayList<>();
            for (Document info : database.listCollections()) {
                if (!"collection".equals(info.getString("type"))) continue;
                EntityDefinition actual = describe(info);
                definitions.put(actual.name(), actual);
                entities.add(actual);
            }
            entities.sort(Comparator.comparing(EntityDefinition::name));
            return new Schema(entities);
        }

        @Override
        public @NotNull SchemaPlan planExact(@NotNull Schema desired) {
            Schema actual = inspect();
            SchemaPlan additive = plan(desired);
            List<SchemaChange> changes = new ArrayList<>(additive.changes());
            for (EntityDefinition current : actual.entities()) {
                EntityDefinition target = desired.entities().stream()
                        .filter(value -> value.name().equals(current.name()))
                        .findFirst()
                        .orElse(null);
                if (target == null) {
                    changes.add(new SchemaChange("Remove entity " + current.name(), ChangeRisk.UNSUPPORTED, null));
                    continue;
                }
                for (FieldDefinition field : current.fields()) {
                    if (target.fields().stream().noneMatch(value -> value.name().equals(field.name())))
                        changes.add(new SchemaChange(
                                "Remove field " + current.name() + "." + field.name(), ChangeRisk.UNSUPPORTED, null));
                }
                Set<String> wantedIndexes = new HashSet<>();
                wantedIndexes.add("_id_");
                if (target.fields().stream().anyMatch(FieldDefinition::identity))
                    wantedIndexes.add(target.name() + "_identity");
                target.fields().stream()
                        .filter(FieldDefinition::unique)
                        .forEach(field -> wantedIndexes.add(target.name() + "_" + field.name() + "_unique"));
                target.indexes().forEach(index -> wantedIndexes.add(index.name()));
                for (IndexDefinition index : current.indexes()) {
                    if (!wantedIndexes.contains(index.name()))
                        changes.add(new SchemaChange(
                                "Remove index " + current.name() + "." + index.name(), ChangeRisk.UNSUPPORTED, null));
                }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                additive.application().run();
            });
        }

        @Override
        public @NotNull SchemaPlan plan(@NotNull Schema desired) {
            Schema actual = inspect();
            List<SchemaChange> changes = new ArrayList<>();
            List<Runnable> operations = new ArrayList<>();
            for (EntityDefinition entity : desired.entities()) {
                EntityDefinition current = actual.entities().stream()
                        .filter(value -> value.name().equals(entity.name()))
                        .findFirst()
                        .orElse(null);
                if (current == null) {
                    changes.add(new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, null));
                    operations.add(() -> {
                        try {
                            database.createCollection(entity.name());
                        } catch (MongoCommandException failure) {
                            if (failure.getErrorCode() != 48) throw failure;
                        }
                    });
                }
                Document oldValidator = current == null
                        ? new Document()
                        : (Document) current.settings().get("mongodb.validator");
                Document json = oldValidator.get("$jsonSchema", Document.class);
                if (!oldValidator.isEmpty() && (json == null || oldValidator.size() != 1)) {
                    changes.add(new SchemaChange(
                            "Replace external validator of " + entity.name(), ChangeRisk.UNSUPPORTED, null));
                } else {
                    Document merged = json == null ? new Document("bsonType", "object") : new Document(json);
                    Document properties = json == null || json.get("properties") == null
                            ? new Document()
                            : new Document(json.get("properties", Document.class));
                    Set<String> required =
                            new TreeSet<>(json == null ? List.of() : json.getList("required", String.class, List.of()));
                    for (FieldDefinition field : entity.fields()) {
                        FieldDefinition previous = current == null
                                ? null
                                : current.fields().stream()
                                        .filter(value -> value.name().equals(field.name()))
                                        .findFirst()
                                        .orElse(null);
                        if (previous != null && previous.identity() != field.identity())
                            changes.add(new SchemaChange(
                                    "Change identity of " + entity.name() + "." + field.name(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                        if (field.type().logicalType() == LogicalType.DECIMAL
                                && field.type().precision() != null
                                && field.type().precision() > 34)
                            changes.add(new SchemaChange(
                                    "Decimal precision exceeds Decimal128 for " + entity.name() + "." + field.name(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                        if (field.generated() == GeneratedValue.IDENTITY)
                            changes.add(new SchemaChange(
                                    "Generate identity for " + entity.name() + "." + field.name(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                        properties.put(field.name(), property(field));
                        if (field.nullable()) required.remove(field.name());
                        else required.add(field.name());
                    }
                    merged.put("properties", properties);
                    if (required.isEmpty()) merged.remove("required");
                    else merged.put("required", List.copyOf(required));
                    Document validator = new Document("$jsonSchema", merged);
                    if (!validator.equals(oldValidator)
                            || current != null
                                    && (!"strict".equals(current.settings().get("mongodb.validationLevel"))
                                            || !"error"
                                                    .equals(current.settings().get("mongodb.validationAction")))) {
                        changes.add(
                                new SchemaChange("Validate schema of " + entity.name(), ChangeRisk.SAFE, validator));
                        operations.add(() -> {
                            if (collection(entity.name()).countDocuments(new Document("$nor", List.of(validator))) != 0)
                                throw new TavaException.Schema(
                                        "Existing records violate desired schema: " + entity.name());
                            database.runCommand(new Document("collMod", entity.name())
                                    .append("validator", validator)
                                    .append("validationLevel", "strict")
                                    .append("validationAction", "error"));
                            if (collection(entity.name()).countDocuments(new Document("$nor", List.of(validator))) != 0)
                                throw new TavaException.Schema(
                                        "Concurrent records violated schema during application: " + entity.name());
                        });
                    }
                }
                List<IndexDefinition> wanted = new ArrayList<>(entity.indexes());
                List<String> identities = entity.fields().stream()
                        .filter(FieldDefinition::identity)
                        .map(FieldDefinition::name)
                        .toList();
                if (!identities.isEmpty())
                    wanted.add(new IndexDefinition(entity.name() + "_identity", identities, true, Map.of()));
                for (FieldDefinition field : entity.fields())
                    if (field.unique())
                        wanted.add(new IndexDefinition(
                                entity.name() + "_" + field.name() + "_unique", List.of(field.name()), true, Map.of()));
                for (IndexDefinition index : wanted) {
                    Document keys = new Document();
                    index.fields().forEach(field -> keys.append(field, 1));
                    IndexDefinition previous = current == null
                            ? null
                            : current.indexes().stream()
                                    .filter(value -> value.name().equals(index.name()))
                                    .findFirst()
                                    .orElse(null);
                    if (previous == null) {
                        IndexOptions options;
                        try {
                            options = indexOptions(index);
                        } catch (TavaException unsupported) {
                            changes.add(new SchemaChange(
                                    "Create index " + index.name() + ": " + unsupported.getMessage(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                            continue;
                        }
                        changes.add(new SchemaChange("Create index " + index.name(), ChangeRisk.SAFE, keys));
                        operations.add(() -> collection(entity.name()).createIndex(keys, options));
                    } else if (!previous.fields().equals(index.fields())
                            || previous.unique() != index.unique()
                            || !expectedIndexSettings(index, keys).equals(previous.settings()))
                        changes.add(new SchemaChange("Replace index " + index.name(), ChangeRisk.UNSUPPORTED, null));
                }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                try {
                    operations.forEach(Runnable::run);
                } finally {
                    desired.entities().forEach(entity -> definitions.remove(entity.name()));
                }
            });
        }
    }

    private static Document continuation(List<Sort> order, List<Object> values) {
        List<Document> alternatives = new ArrayList<>();
        List<Document> equal = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            var sort = order.get(i);
            Object value = values.get(i);
            Document left = new Document("$ifNull", Arrays.asList("$" + sort.field(), null));
            Document compare = new Document("$cmp", List.of(left, new Document("$literal", value)));
            String operator = sort.direction() == Sort.Direction.ASC ? "$gt" : "$lt";
            List<Document> branch = new ArrayList<>(equal);
            branch.add(new Document(operator, List.of(compare, 0)));
            alternatives.add(new Document("$and", branch));
            equal.add(new Document("$eq", List.of(compare, 0)));
        }
        return new Document("$expr", new Document("$or", alternatives));
    }

    private static Document document(Map<String, Object> values) {
        Document document = new Document();
        values.forEach((key, value) -> document.put(key, writeValue(value)));
        return document;
    }

    private static Object writeValue(Object value) {
        if (value instanceof Enum<?> item) return item.name();
        if (value instanceof UUID || value instanceof LocalDate || value instanceof LocalDateTime)
            return value.toString();
        if (value instanceof Timestamp timestamp) return mongoDate(timestamp.toInstant());
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof Instant instant) return mongoDate(instant);
        if (value instanceof OffsetDateTime date) return mongoDate(date.toInstant());
        if (value instanceof Date date) return new Date(date.getTime());
        if (value instanceof BigDecimal decimal) return new Decimal128(decimal);
        if (value instanceof Map<?, ?> map) {
            Document document = new Document();
            map.forEach((key, child) -> document.put(key.toString(), writeValue(child)));
            return document;
        }
        if (value instanceof Collection<?> values)
            return values.stream().map(MongoAdapter::writeValue).toList();
        return value;
    }

    private static Map<String, Object> expectedIndexSettings(IndexDefinition index, Document keys) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("mongodb.keys", keys);
        index.settings().forEach((setting, value) -> {
            if (!setting.equals("mongodb.keys")) {
                String option = setting.startsWith("mongodb.") ? setting.substring("mongodb.".length()) : setting;
                Object normalized = normalizeIndexSetting(option, value);
                if (!(Boolean.FALSE.equals(normalized)
                        && Set.of("background", "hidden", "sparse").contains(option)))
                    expected.put("mongodb." + option, normalized);
            }
        });
        return expected;
    }

    private static IndexOptions indexOptions(IndexDefinition index) {
        IndexOptions options = new IndexOptions().name(index.name()).unique(index.unique());
        for (var setting : index.settings().entrySet()) {
            String key = setting.getKey();
            if (key.equals("mongodb.keys")) continue;
            String option = key.startsWith("mongodb.") ? key.substring("mongodb.".length()) : key;
            Object value = setting.getValue();
            switch (option) {
                case "background" -> options.background(requireBoolean(option, value));
                case "sparse" -> options.sparse(requireBoolean(option, value));
                case "hidden" -> options.hidden(requireBoolean(option, value));
                case "expireAfterSeconds" -> options.expireAfter(requireLong(option, value), TimeUnit.SECONDS);
                case "partialFilterExpression" -> options.partialFilterExpression(indexDocument(option, value));
                case "collation" -> options.collation(indexCollation(value));
                default -> throw new TavaException.Capability("Unsupported MongoDB index option: " + key);
            }
        }
        return options;
    }

    private static Object normalizeIndexSetting(String option, Object value) {
        if (option.equals("expireAfterSeconds") && value instanceof Number) return requireLong(option, value);
        if (option.equals("partialFilterExpression")) return indexDocument(option, value);
        if (option.equals("collation")) {
            Document collation = value instanceof Collation typed
                    ? Document.parse(typed.asDocument().toJson())
                    : indexDocument(option, value);
            collation.remove("version");
            return collation;
        }
        return value;
    }

    private static boolean requireBoolean(String option, Object value) {
        if (value instanceof Boolean bool) return bool;
        throw new TavaException.Capability("MongoDB index option " + option + " must be a boolean");
    }

    private static long requireLong(String option, Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException failure) {
                throw new TavaException.Capability("MongoDB index option " + option + " must be an integer", failure);
            }
        }
        throw new TavaException.Capability("MongoDB index option " + option + " must be a number");
    }

    private static Document indexDocument(String option, Object value) {
        if (value instanceof Document document) return document;
        if (value instanceof Map<?, ?> values) {
            Document document = new Document();
            values.forEach((key, item) -> {
                if (!(key instanceof String name))
                    throw new TavaException.Capability("MongoDB index option " + option + " requires string keys");
                document.put(name, item);
            });
            return document;
        }
        throw new TavaException.Capability("MongoDB index option " + option + " must be a document");
    }

    private static Collation indexCollation(Object value) {
        if (value instanceof Collation collation) return collation;
        Document document = indexDocument("collation", value);
        Set<String> supportedOptions = Set.of(
                "locale",
                "caseLevel",
                "caseFirst",
                "strength",
                "numericOrdering",
                "alternate",
                "maxVariable",
                "normalization",
                "backwards");
        for (String option : document.keySet()) {
            if (!supportedOptions.contains(option))
                throw new TavaException.Capability("Unsupported MongoDB collation option: " + option);
        }
        String locale = collationString(document, "locale");
        if (locale == null || locale.isBlank())
            throw new TavaException.Capability("MongoDB index collation requires a locale");
        Collation.Builder collation = Collation.builder().locale(locale);
        Boolean caseLevel = collationBoolean(document, "caseLevel");
        if (caseLevel != null) collation.caseLevel(caseLevel);
        String caseFirst = collationString(document, "caseFirst");
        if (caseFirst != null) collation.collationCaseFirst(CollationCaseFirst.fromString(caseFirst));
        Integer strength = collationInteger(document, "strength");
        if (strength != null) collation.collationStrength(CollationStrength.fromInt(strength));
        Boolean numericOrdering = collationBoolean(document, "numericOrdering");
        if (numericOrdering != null) collation.numericOrdering(numericOrdering);
        String alternate = collationString(document, "alternate");
        if (alternate != null) collation.collationAlternate(CollationAlternate.fromString(alternate));
        String maxVariable = collationString(document, "maxVariable");
        if (maxVariable != null) collation.collationMaxVariable(CollationMaxVariable.fromString(maxVariable));
        Boolean normalization = collationBoolean(document, "normalization");
        if (normalization != null) collation.normalization(normalization);
        Boolean backwards = collationBoolean(document, "backwards");
        if (backwards != null) collation.backwards(backwards);
        return collation.build();
    }

    private static String collationString(Document document, String option) {
        Object value = document.get(option);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new TavaException.Capability("MongoDB collation option " + option + " must be a string");
    }

    private static Boolean collationBoolean(Document document, String option) {
        Object value = document.get(option);
        if (value == null) return null;
        if (value instanceof Boolean setting) return setting;
        throw new TavaException.Capability("MongoDB collation option " + option + " must be a boolean");
    }

    private static Integer collationInteger(Document document, String option) {
        Object value = document.get(option);
        if (value == null) return null;
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException failure) {
                throw new TavaException.Capability(
                        "MongoDB collation option " + option + " must be an integer", failure);
            }
        }
        throw new TavaException.Capability("MongoDB collation option " + option + " must be a number");
    }

    private static Date mongoDate(Instant instant) {
        if (instant.getNano() % 1_000_000 != 0)
            throw new TavaException.Mapping("MongoDB dates cannot represent sub-millisecond precision", null);
        return Date.from(instant);
    }

    private static Map<String, Object> readDocument(Map<String, Object> document) {
        Map<String, Object> values = new LinkedHashMap<>();
        document.forEach((key, value) -> values.put(key, readValue(value)));
        return values;
    }

    private static Object readValue(Object value) {
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue();
        if (value instanceof Binary binary) return binary.getData();
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Document document) return readDocument(document);
        if (value instanceof Collection<?> values)
            return values.stream().map(MongoAdapter::readValue).toList();
        return value;
    }

    private Bson filter(Predicate predicate) {
        if (predicate == null || predicate instanceof Predicate.All) return new Document();
        if (predicate instanceof Predicate.Comparison(String field, Predicate.Operator operator, Object value1)) {
            value1 = writeValue(value1);
            return switch (operator) {
                case EQ -> new Document(field, value1);
                case NE -> new Document(field, new Document("$ne", value1));
                case LT -> new Document(field, new Document("$lt", value1));
                case LTE -> new Document(field, new Document("$lte", value1));
                case GT -> new Document(field, new Document("$gt", value1));
                case GTE -> new Document(field, new Document("$gte", value1));
                case IN -> new Document(field, new Document("$in", value1));
                case CONTAINS -> new Document(field, new Document("$regex", Pattern.quote(value1.toString())));
                case STARTS_WITH -> new Document(field, new Document("$regex", "^" + Pattern.quote(value1.toString())));
                case IS_NULL -> new Document(field, null);
                case IS_NOT_NULL -> new Document(field, new Document("$ne", null));
            };
        }
        Predicate.Junction junction = (Predicate.Junction) predicate;
        return new Document(
                junction.and() ? "$and" : "$or",
                junction.predicates().stream().map(this::filter).toList());
    }
}
