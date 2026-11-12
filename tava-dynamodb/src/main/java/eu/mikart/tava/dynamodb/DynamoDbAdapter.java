package eu.mikart.tava.dynamodb;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.*;
import eu.mikart.tava.data.ConflictDetail;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.QueryDiagnostics;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.*;
import eu.mikart.tava.schema.plan.*;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.spi.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

final class DynamoDbAdapter implements Adapter {
    private enum IndexType {
        GLOBAL,
        LOCAL
    }

    private final DynamoDbClient client;
    private final boolean owned;
    private final boolean consistentRead;
    private final Map<String, EntityDefinition> definitions = new ConcurrentHashMap<>();
    private final EntityStore store = new Store();
    private final SchemaManager schemas = new Schemas();

    DynamoDbAdapter(DynamoDbClient client, boolean owned) {
        this(client, owned, true);
    }

    DynamoDbAdapter(DynamoDbClient client, boolean owned, boolean consistentRead) {
        Objects.requireNonNull(client);
        this.client = (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(), new Class<?>[] {DynamoDbClient.class}, (proxy, method, args) -> {
                    long remaining = OperationContext.remainingNanos();
                    if (remaining > 0 && args != null && args.length == 1 && args[0] instanceof AwsRequest request) {
                        var configuration = request.overrideConfiguration()
                                .map(AwsRequestOverrideConfiguration::toBuilder)
                                .orElseGet(AwsRequestOverrideConfiguration::builder);
                        Duration timeout = request.overrideConfiguration()
                                .flatMap(AwsRequestOverrideConfiguration::apiCallTimeout)
                                .filter(value -> value.toNanos() < remaining)
                                .orElse(Duration.ofNanos(remaining));
                        configuration.apiCallTimeout(timeout);
                        args[0] = request.toBuilder()
                                .overrideConfiguration(configuration.build())
                                .build();
                    }
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        this.owned = owned;
        this.consistentRead = consistentRead;
    }

    @Override
    public @NotNull String name() {
        return "dynamodb";
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.builder(name())
                .supported(
                        Feature.CONDITIONAL_WRITES,
                        Feature.PAGINATION,
                        Feature.PROJECTION,
                        Feature.JSON,
                        Feature.BINARY,
                        Feature.BULK_READ,
                        Feature.BULK_WRITE,
                        Feature.ATOMIC_BULK_WRITE)
                .set(Feature.TTL, SupportLevel.NATIVE_ONLY, "Configure TTL through DynamoDbClient")
                .set(Feature.GENERATED_VALUES, SupportLevel.NATIVE_ONLY, "Provide generated values in the application")
                .set(
                        Feature.SECONDARY_INDEXES,
                        SupportLevel.SUPPORTED,
                        "Global indexes at table creation; existing index changes require native access")
                .set(Feature.SCHEMA_ENFORCEMENT, SupportLevel.EMULATED, "Key and index schema only")
                .set(Feature.UNIQUE_CONSTRAINTS, SupportLevel.UNSUPPORTED, "Only primary keys are unique")
                .set(Feature.FOREIGN_KEYS, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.TRANSACTIONS, SupportLevel.NATIVE_ONLY, "Use DynamoDB transaction API")
                .set(Feature.SORTING, SupportLevel.NATIVE_ONLY, "Only Query supports sort-key order")
                .set(Feature.JOINS, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.AGGREGATION, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.FULL_TEXT, SupportLevel.UNSUPPORTED, "Not supported")
                .limit(Feature.BULK_WRITE, "batchSize", 100)
                .build();
    }

    @Override
    public @NotNull SchemaManager schemas() {
        return schemas;
    }

    @Override
    public @NotNull EntityStore entities() {
        return store;
    }

    @Override
    public @NotNull NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            @SuppressWarnings("deprecation")
            public @NotNull <T> T nativeHandle(@NotNull Class<T> type) {
                if (type != DynamoDbClient.class) throw new IllegalArgumentException("Adapter exposes DynamoDbClient");
                return type.cast(client);
            }
        };
    }

    @Override
    public void close() {
        if (owned) client.close();
    }

    private static void metrics(int evaluated, int returned, ConsumedCapacity capacity) {
        OperationContext.metric("evaluatedRecords", evaluated);
        OperationContext.metric("returnedRecords", returned);
        if (capacity != null && capacity.capacityUnits() != null)
            OperationContext.metric("consumedCapacity", capacity.capacityUnits());
    }

    private final class Store implements EntityStore {
        private final ThreadLocal<Long> counting = new ThreadLocal<>();

        private long countMatching(String entity, Predicate predicate) {
            counting.set(0L);
            try {
                String cursor = null;
                do {
                    Query query =
                            Query.builder().where(predicate).cursor(cursor).build();
                    cursor = find(entity, query).nextCursor();
                } while (cursor != null);
                return counting.get();
            } finally {
                counting.remove();
            }
        }

        @Override
        public @NotNull List<EntityRecord> insert(@NotNull String entity, @NotNull List<EntityRecord> records) {
            if (records.isEmpty()) return List.of();
            if (records.size() > 100)
                throw new TavaException.Capability("DynamoDB atomic batches are limited to 100 records");
            String partition = partitionKey(definition(entity)).name();
            List<TransactWriteItem> writes = records.stream()
                    .map(record -> TransactWriteItem.builder()
                            .put(Put.builder()
                                    .tableName(entity)
                                    .item(encode(record.values()))
                                    .conditionExpression("attribute_not_exists(#pk)")
                                    .expressionAttributeNames(Map.of("#pk", partition))
                                    .build())
                            .build())
                    .toList();
            try {
                client.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(writes)
                        .clientRequestToken(UUID.randomUUID().toString())
                        .build());
            } catch (TransactionCanceledException failure) {
                if (failure.cancellationReasons().stream()
                        .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code())))
                    throw new TavaException.Conflict("Batch insert conflicts with an existing identity", failure);
                throw failure;
            }
            return List.copyOf(records);
        }

        @Override
        public @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record) {
            PutItemRequest.Builder request =
                    PutItemRequest.builder().tableName(entity).item(encode(record.values()));
            EntityDefinition definition = definition(entity);
            request.conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(
                            Map.of("#pk", partitionKey(definition).name()));
            client.putItem(request.build());
            return record;
        }

        @Override
        public @NotNull MutationResult insertResult(@NotNull String entity, @NotNull EntityRecord record) {
            try {
                insert(entity, record);
                return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
            } catch (ConditionalCheckFailedException failure) {
                return new MutationResult(
                        0,
                        0,
                        1,
                        MutationResult.Outcome.CONFLICT,
                        List.of(new ConflictDetail(
                                ConflictDetail.Kind.UNIQUE,
                                null,
                                failure.awsErrorDetails() == null
                                        ? null
                                        : failure.awsErrorDetails().errorCode(),
                                failure.getMessage())));
            }
        }

        @Override
        public QueryDiagnostics explain(String entity, Query query) {
            EntityDefinition definition = definition(entity);
            String partition = partitionKey(definition).name();
            FieldDefinition sort = sortKey(definition);
            QueryAccess access = queryAccess(query.predicate(), partition, sort == null ? null : sort.name());
            boolean scan = access == null;
            return new QueryDiagnostics(
                    scan ? "dynamodb-scan" : "dynamodb-query",
                    scan || sort == null ? List.of() : List.of(Sort.asc(sort.name())),
                    scan,
                    scan
                            ? "Evaluates table records before filtering; scan policy applies on execution"
                            : "Partition-key access with optional sort-key condition");
        }

        @Override
        public boolean exists(String entity, Predicate predicate) {
            counting.set(0L);
            try {
                String cursor = null;
                do {
                    cursor = find(
                                    entity,
                                    Query.builder()
                                            .where(predicate)
                                            .limit(1)
                                            .cursor(cursor)
                                            .build())
                            .nextCursor();
                    if (counting.get() > 0) return true;
                } while (cursor != null);
                return false;
            } finally {
                counting.remove();
            }
        }

        @Override
        public long count(String entity, Predicate predicate) {
            return countMatching(entity, predicate);
        }

        @Override
        public @NotNull Page<EntityRecord> find(@NotNull String entity, @NotNull Query query) {
            if (!query.sorting().isEmpty())
                throw new TavaException.Capability("DynamoDB portable queries do not support sorting");
            final EntityDefinition definition = definition(entity);
            if (definition != null) {
                final String partitionField = partitionKey(definition).name();
                final FieldDefinition sort = sortKey(definition);
                QueryAccess access = queryAccess(query.predicate(), partitionField, sort == null ? null : sort.name());
                if (access != null)
                    return query(entity, query, partitionField, sort == null ? null : sort.name(), access);
            }
            if (!OperationContext.options().allowScans())
                throw new TavaException.Capability("DynamoDB scan rejected by operation policy");
            OperationContext.metric("scan", 1);
            Expression expression = expression(query.predicate());
            ScanRequest.Builder request = ScanRequest.builder()
                    .tableName(entity)
                    .consistentRead(consistentRead)
                    .limit(query.limit() == 0 ? OperationContext.options().defaultPageSize() : query.limit());
            if (expression.text != null) {
                request.filterExpression(expression.text).expressionAttributeNames(expression.names);
                if (!expression.values.isEmpty()) request.expressionAttributeValues(expression.values);
            }
            if (!query.projection().isEmpty()) {
                Map<String, String> names = new HashMap<>(expression.names);
                List<String> projected = new ArrayList<>(query.projection().size());
                for (int i = 0; i < query.projection().size(); i++) {
                    String key = "#p" + i;
                    names.put(key, query.projection().get(i));
                    projected.add(key);
                }
                request.projectionExpression(String.join(",", projected)).expressionAttributeNames(names);
            }
            if (query.cursor() != null) request.exclusiveStartKey(decodeCursor(query.cursor(), entity, query, "scan"));
            request.returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
            if (counting.get() != null) request.select(Select.COUNT);
            ScanResponse response = client.scan(request.build());
            metrics(response.scannedCount(), response.count(), response.consumedCapacity());
            if (counting.get() != null) counting.set(counting.get() + response.count());
            return new Page<>(
                    response.items().stream().map(this::decode).toList(),
                    response.lastEvaluatedKey().isEmpty()
                            ? null
                            : encodeCursor(response.lastEvaluatedKey(), entity, query, "scan"));
        }

        private Page<EntityRecord> query(
                final String entity,
                final Query query,
                final String partitionField,
                final String sortField,
                final QueryAccess access) {
            final Map<String, String> names = new LinkedHashMap<>();
            final Map<String, AttributeValue> values = new LinkedHashMap<>();
            names.put("#pk", partitionField);
            values.put(":pk", encode(access.partition()));
            final StringBuilder keyCondition = new StringBuilder("#pk = :pk");
            if (access.sort() != null) {
                names.put("#sk", sortField);
                values.put(":sk", encode(access.sort().value()));
                String operator = switch (access.sort().operator()) {
                    case EQ -> "=";
                    case LT -> "<";
                    case LTE -> "<=";
                    case GT -> ">";
                    case GTE -> ">=";
                    case STARTS_WITH -> null;
                    default -> throw new IllegalStateException("Invalid sort key condition");
                };
                if (operator == null) keyCondition.append(" AND begins_with(#sk, :sk)");
                else keyCondition.append(" AND #sk ").append(operator).append(" :sk");
            }
            Expression filter = expression(access.filter());
            names.putAll(filter.names());
            values.putAll(filter.values());
            final QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(entity)
                    .consistentRead(consistentRead)
                    .keyConditionExpression(keyCondition.toString())
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .limit(query.limit() == 0 ? OperationContext.options().defaultPageSize() : query.limit());
            if (filter.text() != null) request.filterExpression(filter.text());
            if (!query.projection().isEmpty()) {
                final List<String> projected =
                        new ArrayList<>(query.projection().size());
                for (int i = 0; i < query.projection().size(); i++) {
                    final String key = "#p" + i;
                    names.put(key, query.projection().get(i));
                    projected.add(key);
                }
                request.projectionExpression(String.join(",", projected));
            }
            request.expressionAttributeNames(names).expressionAttributeValues(values);
            if (query.cursor() != null) request.exclusiveStartKey(decodeCursor(query.cursor(), entity, query, "query"));
            request.returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
            if (counting.get() != null) request.select(Select.COUNT);
            final QueryResponse response = client.query(request.build());
            metrics(response.scannedCount(), response.count(), response.consumedCapacity());
            if (counting.get() != null) counting.set(counting.get() + response.count());
            return new Page<>(
                    response.items().stream().map(this::decode).toList(),
                    response.lastEvaluatedKey().isEmpty()
                            ? null
                            : encodeCursor(response.lastEvaluatedKey(), entity, query, "query"));
        }

        @Override
        public long update(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            if (mutation.values().isEmpty() && mutation.operations().isEmpty()) return 0;
            Map<String, AttributeValue> key = key(entity, predicate);
            Map<String, String> names = new HashMap<>();
            Map<String, AttributeValue> values = new HashMap<>();
            List<String> updates = new ArrayList<>();
            int i = 0;
            for (var entry : mutation.values().entrySet()) {
                names.put("#u" + i, entry.getKey());
                values.put(":u" + i, encode(entry.getValue()));
                updates.add("#u" + i + " = :u" + i);
                i++;
            }
            for (var entry : mutation.operations().entrySet()) {
                if (entry.getValue().kind() != Mutation.Kind.INCREMENT)
                    throw new TavaException.Capability("DynamoDB currently supports INCREMENT database-side mutations");
                names.put("#u" + i, entry.getKey());
                values.put(":u" + i, encode(entry.getValue().value()));
                values.put(":zero" + i, encode(0));
                updates.add("#u" + i + " = if_not_exists(#u" + i + ", :zero" + i + ") + :u" + i);
                i++;
            }
            Expression condition = expression(predicate);
            names.putAll(condition.names());
            values.putAll(condition.values());
            names.put("#exists", key.keySet().iterator().next());
            try {
                client.updateItem(UpdateItemRequest.builder()
                        .tableName(entity)
                        .key(key)
                        .conditionExpression("attribute_exists(#exists) AND (" + condition.text() + ")")
                        .updateExpression("SET " + String.join(", ", updates))
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build());
                return 1;
            } catch (ConditionalCheckFailedException missing) {
                return 0;
            }
        }

        @Override
        public @NotNull MutationResult upsert(
                @NotNull String entity,
                @NotNull Predicate identity,
                @NotNull EntityRecord insert,
                @NotNull Mutation update) {
            for (Mutation.Operation operation : update.operations().values()) {
                if (operation.kind() != Mutation.Kind.INCREMENT)
                    throw new TavaException.Capability(
                            "DynamoDB upsert supports INCREMENT operations only; list append and removal cannot be represented atomically");
            }
            for (String field : update.operations().keySet()) {
                if (update.values().containsKey(field))
                    throw new TavaException.Capability(
                            "DynamoDB upsert cannot apply both a value and an operation to field " + field);
            }

            Map<String, AttributeValue> key = key(entity, identity);
            Set<String> keyFields = key.keySet();
            for (String field : update.values().keySet()) {
                if (keyFields.contains(field))
                    throw new TavaException.Capability("DynamoDB upsert cannot mutate identity field " + field);
            }
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, AttributeValue> values = new LinkedHashMap<>();
            List<String> sets = new ArrayList<>();
            int i = 0;

            // Insert values are defaults: if_not_exists makes them apply only when this
            // atomic UpdateItem creates the item. Update values and operations take precedence.
            Set<String> updatedFields = new HashSet<>(update.values().keySet());
            updatedFields.addAll(update.operations().keySet());
            for (var entry : insert.values().entrySet()) {
                if (keyFields.contains(entry.getKey()) || updatedFields.contains(entry.getKey())) continue;
                String name = "#u" + i;
                String value = ":u" + i++;
                names.put(name, entry.getKey());
                values.put(value, encode(entry.getValue()));
                sets.add(name + " = if_not_exists(" + name + ", " + value + ")");
            }
            for (var entry : update.values().entrySet()) {
                if (keyFields.contains(entry.getKey())) continue;
                String name = "#u" + i;
                String value = ":u" + i++;
                names.put(name, entry.getKey());
                values.put(value, encode(entry.getValue()));
                sets.add(name + " = " + value);
            }
            for (var entry : update.operations().entrySet()) {
                if (keyFields.contains(entry.getKey()))
                    throw new TavaException.Capability(
                            "DynamoDB upsert cannot mutate identity field " + entry.getKey());
                if (!(entry.getValue().value() instanceof Number))
                    throw new TavaException.Capability(
                            "DynamoDB INCREMENT requires a numeric amount for field " + entry.getKey());
                String name = "#u" + i;
                String amount = ":u" + i;
                String zero = ":zero" + i++;
                names.put(name, entry.getKey());
                values.put(amount, encode(entry.getValue().value()));
                values.put(zero, encode(0));
                sets.add(name + " = if_not_exists(" + name + ", " + zero + ") + " + amount);
            }
            if (sets.isEmpty())
                throw new TavaException.Capability(
                        "DynamoDB cannot atomically upsert an item without writable non-key attributes");

            UpdateItemResponse response = client.updateItem(UpdateItemRequest.builder()
                    .tableName(entity)
                    .key(key)
                    .updateExpression("SET " + String.join(", ", sets))
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_OLD)
                    .build());
            boolean inserted = response.attributes().isEmpty();
            return MutationResult.changed(
                    1, inserted ? MutationResult.Outcome.INSERTED : MutationResult.Outcome.UPDATED);
        }

        @Override
        public long delete(@NotNull String entity, @NotNull Predicate predicate) {
            Expression condition = expression(predicate);
            DeleteItemRequest.Builder request = DeleteItemRequest.builder()
                    .tableName(entity)
                    .key(key(entity, predicate))
                    .returnValues(ReturnValue.ALL_OLD)
                    .conditionExpression(condition.text())
                    .expressionAttributeNames(condition.names());
            if (!condition.values().isEmpty()) request.expressionAttributeValues(condition.values());
            try {
                DeleteItemResponse response = client.deleteItem(request.build());
                return response.attributes().isEmpty() ? 0 : 1;
            } catch (ConditionalCheckFailedException mismatch) {
                return 0;
            }
        }

        private EntityRecord decode(Map<String, AttributeValue> item) {
            Map<String, Object> values = new LinkedHashMap<>();
            item.forEach((key, value) -> values.put(key, DynamoDbAdapter.this.decode(value)));
            return EntityRecord.of(values);
        }
    }

    private EntityDefinition definition(String entity) {
        return definitions.computeIfAbsent(entity, this::describe);
    }

    private EntityDefinition describe(String name) {
        TableDescription table = client.describeTable(
                        DescribeTableRequest.builder().tableName(name).build())
                .table();
        Map<String, ScalarAttributeType> types = new TreeMap<>();
        table.attributeDefinitions().forEach(value -> types.put(value.attributeName(), value.attributeType()));
        Set<String> identities = new HashSet<>();
        table.keySchema().forEach(value -> identities.add(value.attributeName()));
        String partition = table.keySchema().stream()
                .filter(value -> value.keyType() == KeyType.HASH)
                .map(KeySchemaElement::attributeName)
                .findFirst()
                .orElseThrow();
        List<FieldDefinition> fields = new ArrayList<>();
        types.forEach((field, type) -> fields.add(new FieldDefinition(
                field,
                FieldType.of(
                        switch (type) {
                            case S -> LogicalType.STRING;
                            case N -> LogicalType.DECIMAL;
                            case B -> LogicalType.BINARY;
                            default -> throw new TavaException.Schema("Unknown DynamoDB key type " + type);
                        }),
                !identities.contains(field),
                identities.contains(field),
                false,
                GeneratedValue.NONE,
                Map.of())));
        List<IndexDefinition> indexes = new ArrayList<>();
        table.globalSecondaryIndexes()
                .forEach(index -> indexes.add(new IndexDefinition(
                        index.indexName(),
                        index.keySchema().stream()
                                .sorted(Comparator.comparing(key -> key.keyType() == KeyType.HASH ? 0 : 1))
                                .map(KeySchemaElement::attributeName)
                                .toList(),
                        false,
                        Map.of(
                                "dynamodb.indexType",
                                "GLOBAL",
                                "dynamodb.projection",
                                index.projection().projectionTypeAsString()))));
        table.localSecondaryIndexes()
                .forEach(index -> indexes.add(new IndexDefinition(
                        index.indexName(),
                        index.keySchema().stream()
                                .sorted(Comparator.comparing(key -> key.keyType() == KeyType.HASH ? 0 : 1))
                                .map(KeySchemaElement::attributeName)
                                .toList(),
                        false,
                        Map.of(
                                "dynamodb.indexType",
                                "LOCAL",
                                "dynamodb.projection",
                                index.projection().projectionTypeAsString()))));
        indexes.sort(Comparator.comparing(IndexDefinition::name));
        return new EntityDefinition(
                name, fields, indexes, Map.of("dynamodb.partitionKey", partition, "dynamodb.schema", "keys-only"));
    }

    private boolean sameKeys(EntityDefinition desired, EntityDefinition actual) {
        FieldDefinition desiredSort = sortKey(desired);
        FieldDefinition actualSort = sortKey(actual);
        return partitionKey(desired).name().equals(partitionKey(actual).name())
                && scalarType(partitionKey(desired)) == scalarType(partitionKey(actual))
                && (desiredSort == null
                        ? actualSort == null
                        : actualSort != null
                                && desiredSort.name().equals(actualSort.name())
                                && scalarType(desiredSort) == scalarType(actualSort));
    }

    private final class Schemas implements SchemaManager {
        @Override
        public @NotNull Schema inspect() {
            List<EntityDefinition> entities = new ArrayList<>();
            for (String table : client.listTablesPaginator().tableNames()) {
                EntityDefinition actual = describe(table);
                definitions.put(table, actual);
                entities.add(actual);
            }
            entities.sort(Comparator.comparing(EntityDefinition::name));
            return new Schema(entities);
        }

        @Override
        public @NotNull SchemaPlan plan(@NotNull Schema desired) {
            Schema actual = inspect();
            Map<String, EntityDefinition> existing = new HashMap<>();
            actual.entities().forEach(entity -> existing.put(entity.name(), entity));
            List<SchemaChange> changes = new ArrayList<>();
            List<Runnable> operations = new ArrayList<>();
            for (EntityDefinition entity : desired.entities()) {
                validateEntity(entity);
                EntityDefinition current = existing.get(entity.name());
                if (current == null) {
                    changes.add(new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, null));
                    operations.add(() -> create(entity));
                    continue;
                }
                if (!sameKeys(entity, current))
                    changes.add(
                            new SchemaChange("Replace key schema of " + entity.name(), ChangeRisk.UNSUPPORTED, null));
                for (IndexDefinition index : entity.indexes()) {
                    IndexDefinition previous = current.indexes().stream()
                            .filter(value -> value.name().equals(index.name()))
                            .findFirst()
                            .orElse(null);
                    if (previous == null
                            || !previous.fields().equals(index.fields())
                            || !"GLOBAL".equals(previous.settings().get("dynamodb.indexType"))
                            || !"ALL".equals(previous.settings().get("dynamodb.projection")))
                        changes.add(new SchemaChange(
                                "Change index " + entity.name() + "." + index.name(), ChangeRisk.UNSUPPORTED, null));
                    else
                        for (String field : index.fields()) {
                            if (scalarType(entity.field(field)) != scalarType(current.field(field)))
                                changes.add(new SchemaChange(
                                        "Change index key type " + entity.name() + "." + field,
                                        ChangeRisk.UNSUPPORTED,
                                        null));
                        }
                }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                operations.forEach(Runnable::run);
            });
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
                if (target == null)
                    changes.add(new SchemaChange("Remove entity " + current.name(), ChangeRisk.UNSUPPORTED, null));
                else
                    for (IndexDefinition index : current.indexes()) {
                        if (target.indexes().stream()
                                .noneMatch(value -> value.name().equals(index.name())))
                            changes.add(new SchemaChange(
                                    "Remove index " + current.name() + "." + index.name(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                    }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                additive.apply(new ApplyOptions(true, true));
            });
        }
    }

    private void create(EntityDefinition entity) {
        FieldDefinition partition = partitionKey(entity);
        FieldDefinition sort = sortKey(entity);
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(AttributeDefinition.builder()
                .attributeName(partition.name())
                .attributeType(scalarType(partition))
                .build());
        List<KeySchemaElement> keys = new ArrayList<>();
        keys.add(KeySchemaElement.builder()
                .attributeName(partition.name())
                .keyType(KeyType.HASH)
                .build());
        if (sort != null) {
            attributes.add(AttributeDefinition.builder()
                    .attributeName(sort.name())
                    .attributeType(scalarType(sort))
                    .build());
            keys.add(KeySchemaElement.builder()
                    .attributeName(sort.name())
                    .keyType(KeyType.RANGE)
                    .build());
        }
        List<GlobalSecondaryIndex> indexes = new ArrayList<>();
        for (IndexDefinition index : entity.indexes()) {
            List<KeySchemaElement> indexKeys = new ArrayList<>();
            for (int position = 0; position < index.fields().size(); position++) {
                String field = index.fields().get(position);
                if (attributes.stream().noneMatch(value -> value.attributeName().equals(field)))
                    attributes.add(AttributeDefinition.builder()
                            .attributeName(field)
                            .attributeType(scalarType(entity.field(field)))
                            .build());
                indexKeys.add(KeySchemaElement.builder()
                        .attributeName(field)
                        .keyType(position == 0 ? KeyType.HASH : KeyType.RANGE)
                        .build());
            }
            indexes.add(GlobalSecondaryIndex.builder()
                    .indexName(index.name())
                    .keySchema(indexKeys)
                    .projection(Projection.builder()
                            .projectionType(ProjectionType.ALL)
                            .build())
                    .build());
        }
        CreateTableRequest.Builder request = CreateTableRequest.builder()
                .tableName(entity.name())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributes)
                .keySchema(keys);
        if (!indexes.isEmpty()) request.globalSecondaryIndexes(indexes);
        try {
            client.createTable(request.build());
        } catch (ResourceInUseException alreadyCreating) {
            // A concurrent schema runner may have created the table after planning.
        }
        try (DynamoDbWaiter waiter = client.waiter()) {
            waiter.waitUntilTableExists(DescribeTableRequest.builder()
                            .tableName(entity.name())
                            .build())
                    .matched()
                    .exception()
                    .ifPresent(failure -> {
                        throw new TavaException.Schema("Table did not become active: " + entity.name(), failure);
                    });
        }
        EntityDefinition actual = describe(entity.name());
        if (!sameKeys(entity, actual))
            throw new TavaException.Schema("Created table key schema differs from plan: " + entity.name());
        for (IndexDefinition expected : entity.indexes()) {
            if (actual.indexes().stream()
                    .noneMatch(index -> index.name().equals(expected.name())
                            && index.fields().equals(expected.fields())
                            && "GLOBAL".equals(index.settings().get("dynamodb.indexType"))
                            && "ALL".equals(index.settings().get("dynamodb.projection"))))
                throw new TavaException.Schema("Created table index schema differs from plan: " + expected.name());
        }
        definitions.put(entity.name(), actual);
    }

    private void validateEntity(EntityDefinition entity) {
        FieldDefinition partition = partitionKey(entity);
        validateKeyType(partition);
        FieldDefinition sort = sortKey(entity);
        if (sort != null) validateKeyType(sort);
        for (IndexDefinition index : entity.indexes()) {
            if (index.unique()) throw new TavaException.Schema("DynamoDB cannot enforce unique secondary indexes");
            if (index.fields().size() > 2)
                throw new TavaException.Schema("DynamoDB indexes support partition and optional sort fields");
            IndexType indexType = indexType(index);
            if (indexType != IndexType.GLOBAL)
                throw new TavaException.Capability("DynamoDB schema plans only create global secondary indexes");
            ProjectionType projection = projectionType(index);
            if (projection != ProjectionType.ALL || index.settings().containsKey("dynamodb.nonKeyAttributes"))
                throw new TavaException.Capability("DynamoDB schema plans only support ALL index projection");
            for (String field : index.fields()) validateKeyType(entity.field(field));
        }
        if (entity.fields().stream().anyMatch(FieldDefinition::unique))
            throw new TavaException.Schema("DynamoDB cannot enforce unique non-key fields");
        if (entity.fields().stream().filter(FieldDefinition::identity).count() > 2)
            throw new TavaException.Schema("DynamoDB supports at most partition and sort identity fields");
    }

    private static IndexType indexType(IndexDefinition index) {
        Object configured = index.settings().get("dynamodb.indexType");
        if (configured == null) return IndexType.GLOBAL;
        if (!(configured instanceof String value))
            throw new TavaException.Capability("DynamoDB index type must be a string");
        try {
            return IndexType.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw new TavaException.Capability("Unsupported DynamoDB index type: " + value, failure);
        }
    }

    private static ProjectionType projectionType(IndexDefinition index) {
        Object configured = index.settings().get("dynamodb.projection");
        if (configured == null) return ProjectionType.ALL;
        if (!(configured instanceof String value))
            throw new TavaException.Capability("DynamoDB index projection must be a string");
        try {
            ProjectionType projection = ProjectionType.fromValue(value);
            if (projection == ProjectionType.UNKNOWN_TO_SDK_VERSION)
                throw new TavaException.Capability("Unsupported DynamoDB index projection: " + value);
            return projection;
        } catch (IllegalArgumentException failure) {
            throw new TavaException.Capability("Unsupported DynamoDB index projection: " + value, failure);
        }
    }

    private void validateKeyType(FieldDefinition field) {
        if (!Set.of(
                        LogicalType.STRING,
                        LogicalType.TEXT,
                        LogicalType.UUID,
                        LogicalType.INT32,
                        LogicalType.INT64,
                        LogicalType.DECIMAL,
                        LogicalType.FLOAT64,
                        LogicalType.BINARY)
                .contains(field.type().logicalType()))
            throw new TavaException.Schema("Unsupported DynamoDB key type for " + field.name());
    }

    private FieldDefinition sortKey(EntityDefinition entity) {
        FieldDefinition partition = partitionKey(entity);
        return entity.fields().stream()
                .filter(FieldDefinition::identity)
                .filter(field -> !field.name().equals(partition.name()))
                .findFirst()
                .orElse(null);
    }

    private FieldDefinition partitionKey(EntityDefinition entity) {
        String configured = Objects.toString(entity.settings().get("dynamodb.partitionKey"), null);
        if (configured != null) return entity.field(configured);
        return entity.fields().stream()
                .filter(FieldDefinition::identity)
                .findFirst()
                .orElseThrow(() -> new TavaException.Schema(
                        "DynamoDB entity " + entity.name() + " requires an identity field or dynamodb.partitionKey"));
    }

    private ScalarAttributeType scalarType(FieldDefinition field) {
        return switch (field.type().logicalType()) {
            case INT32, INT64, FLOAT64, DECIMAL -> ScalarAttributeType.N;
            case BINARY -> ScalarAttributeType.B;
            default -> ScalarAttributeType.S;
        };
    }

    private Map<String, AttributeValue> key(String entity, Predicate predicate) {
        EntityDefinition definition = definition(entity);
        Set<String> required = new LinkedHashSet<>();
        required.add(partitionKey(definition).name());
        if (sortKey(definition) != null) required.add(sortKey(definition).name());
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        collectKey(predicate, required, result);
        if (result.keySet().equals(required)) return result;
        throw new TavaException.Capability("DynamoDB keyed write requires equality on " + required);
    }

    private void collectKey(Predicate predicate, Set<String> required, Map<String, AttributeValue> result) {
        if (predicate instanceof Predicate.Comparison value
                && value.operator() == Predicate.Operator.EQ
                && required.contains(value.field())) result.put(value.field(), encode(value.value()));
        else if (predicate instanceof Predicate.Junction junction && junction.and())
            junction.predicates().forEach(child -> collectKey(child, required, result));
    }

    private record QueryAccess(Object partition, Predicate.Comparison sort, Predicate filter) {}

    private static QueryAccess queryAccess(Predicate predicate, String partition, String sort) {
        List<Predicate> terms = new ArrayList<>();
        conjuncts(predicate, terms);
        Predicate.Comparison partitionCondition = null;
        Predicate.Comparison sortCondition = null;
        List<Predicate> filters = new ArrayList<>();
        for (Predicate term : terms) {
            if (term instanceof Predicate.All) continue;
            if (term instanceof Predicate.Comparison comparison
                    && comparison.field().equals(partition)) {
                if (partitionCondition != null
                        || comparison.operator() != Predicate.Operator.EQ
                        || comparison.value() == null) return null;
                partitionCondition = comparison;
            } else if (term instanceof Predicate.Comparison comparison
                    && comparison.field().equals(sort)) {
                if (sortCondition != null
                        || comparison.value() == null
                        || !Set.of(
                                        Predicate.Operator.EQ,
                                        Predicate.Operator.LT,
                                        Predicate.Operator.LTE,
                                        Predicate.Operator.GT,
                                        Predicate.Operator.GTE,
                                        Predicate.Operator.STARTS_WITH)
                                .contains(comparison.operator())) return null;
                sortCondition = comparison;
            } else {
                // DynamoDB forbids key attributes in Query filters. Preserve such predicates using Scan.
                if (referencesKey(term, partition, sort)) return null;
                filters.add(term);
            }
        }
        if (partitionCondition == null) return null;
        Predicate filter = filters.isEmpty() ? Predicate.all() : new Predicate.Junction(true, filters);
        return new QueryAccess(partitionCondition.value(), sortCondition, filter);
    }

    private static void conjuncts(Predicate predicate, List<Predicate> terms) {
        if (predicate instanceof Predicate.Junction junction && junction.and())
            junction.predicates().forEach(child -> conjuncts(child, terms));
        else terms.add(predicate);
    }

    private static boolean referencesKey(Predicate predicate, String partition, String sort) {
        if (predicate instanceof Predicate.Comparison comparison)
            return comparison.field().equals(partition) || comparison.field().equals(sort);
        if (predicate instanceof Predicate.Junction junction)
            return junction.predicates().stream().anyMatch(child -> referencesKey(child, partition, sort));
        return false;
    }

    private record Expression(String text, Map<String, String> names, Map<String, AttributeValue> values) {}

    private Expression expression(Predicate predicate) {
        if (predicate == null || predicate instanceof Predicate.All) return new Expression(null, Map.of(), Map.of());
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        int[] sequence = {0};
        String text = expression(predicate, names, values, sequence);
        return new Expression(text, names, values);
    }

    private String expression(
            Predicate predicate, Map<String, String> names, Map<String, AttributeValue> values, int[] sequence) {
        if (predicate instanceof Predicate.Comparison value) {
            int i = sequence[0]++;
            names.put("#f" + i, value.field());
            if (value.operator() == Predicate.Operator.IS_NULL) {
                values.put(":nullType" + i, AttributeValue.builder().s("NULL").build());
                return "attribute_not_exists(#f" + i + ") OR attribute_type(#f" + i + ", :nullType" + i + ")";
            }
            if (value.operator() == Predicate.Operator.IS_NOT_NULL) {
                values.put(":nullType" + i, AttributeValue.builder().s("NULL").build());
                return "attribute_exists(#f" + i + ") AND NOT attribute_type(#f" + i + ", :nullType" + i + ")";
            }
            values.put(":v" + i, encode(value.value()));
            if (value.operator() == Predicate.Operator.CONTAINS) return "contains(#f" + i + ", :v" + i + ")";
            if (value.operator() == Predicate.Operator.STARTS_WITH) return "begins_with(#f" + i + ", :v" + i + ")";
            String operator = switch (value.operator()) {
                case EQ -> "=";
                case NE -> "<>";
                case LT -> "<";
                case LTE -> "<=";
                case GT -> ">";
                case GTE -> ">=";
                default ->
                    throw new TavaException.Capability("DynamoDB predicate does not support " + value.operator());
            };
            return "#f" + i + " " + operator + " :v" + i;
        }
        Predicate.Junction junction = (Predicate.Junction) predicate;
        return junction.predicates().stream()
                .map(value -> "(" + expression(value, names, values, sequence) + ")")
                .collect(Collectors.joining(junction.and() ? " AND " : " OR "));
    }

    private static Map<String, AttributeValue> encode(Map<String, Object> values) {
        Map<String, AttributeValue> encoded = new LinkedHashMap<>();
        values.forEach((key, value) -> encoded.put(key, encode(value)));
        return encoded;
    }

    private static AttributeValue encode(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof Enum<?> item)
            return AttributeValue.builder().s(item.name()).build();
        if (value instanceof Number number)
            return AttributeValue.builder().n(number.toString()).build();
        if (value instanceof Boolean bool)
            return AttributeValue.builder().bool(bool).build();
        if (value instanceof byte[] bytes)
            return AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
        if (value instanceof Collection<?> collection)
            return AttributeValue.builder()
                    .l(collection.stream().map(DynamoDbAdapter::encode).toList())
                    .build();
        if (value instanceof Map<?, ?> map) {
            Map<String, AttributeValue> encoded = new LinkedHashMap<>();
            map.forEach((key, child) -> encoded.put(key.toString(), encode(child)));
            return AttributeValue.builder().m(encoded).build();
        }
        return AttributeValue.builder().s(value.toString()).build();
    }

    private static Object decode(AttributeValue value) {
        if (Boolean.TRUE.equals(value.nul())) return null;
        if (value.s() != null) return value.s();
        if (value.n() != null) return new BigDecimal(value.n());
        if (value.bool() != null) return value.bool();
        if (value.b() != null) return value.b().asByteArray();
        if (value.hasL()) return value.l().stream().map(DynamoDbAdapter::decode).toList();
        if (value.hasM()) {
            Map<String, Object> map = new LinkedHashMap<>();
            value.m().forEach((key, child) -> map.put(key, decode(child)));
            return map;
        }
        return null;
    }

    private static final List<Sort> CURSOR_ORDER = List.of(Sort.asc("dynamodbKey"));

    private static String encodeCursor(Map<String, AttributeValue> key, String entity, Query query, String access) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(key.size());
                for (var entry : new TreeMap<>(key).entrySet()) {
                    output.writeUTF(entry.getKey());
                    output.writeUTF(scalar(entry.getValue()));
                }
            }
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            return KeysetCursor.encode("dynamodb:" + entity + ":" + access, query, CURSOR_ORDER, List.of(payload));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode DynamoDB cursor", failure);
        }
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor, String entity, Query query, String access) {
        try {
            Object encoded = KeysetCursor.decode(cursor, "dynamodb:" + entity + ":" + access, query, CURSOR_ORDER)
                    .getFirst();
            byte[] bytes = Base64.getUrlDecoder().decode((String) encoded);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int count = input.readInt();
                if (count < 1 || count > 10) throw new IllegalArgumentException("Invalid cursor key count");
                Map<String, AttributeValue> key = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String field = input.readUTF();
                    if (key.putIfAbsent(field, parseScalar(input.readUTF())) != null)
                        throw new IllegalArgumentException("Duplicate cursor key");
                }
                if (input.available() != 0) throw new IllegalArgumentException("Unexpected cursor data");
                return key;
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Invalid DynamoDB cursor", failure);
        }
    }

    private static String scalar(AttributeValue value) {
        if (value.s() != null) return "S:" + value.s();
        if (value.n() != null) return "N:" + value.n();
        if (value.b() != null)
            return "B:" + Base64.getEncoder().encodeToString(value.b().asByteArray());
        throw new IllegalArgumentException("Cursor key contains unsupported value");
    }

    private static AttributeValue parseScalar(String value) {
        return switch (value.substring(0, 2)) {
            case "S:" -> AttributeValue.builder().s(value.substring(2)).build();
            case "N:" -> AttributeValue.builder().n(value.substring(2)).build();
            case "B:" ->
                AttributeValue.builder()
                        .b(SdkBytes.fromByteArray(Base64.getDecoder().decode(value.substring(2))))
                        .build();
            default -> throw new IllegalArgumentException("Unsupported cursor value");
        };
    }
}
