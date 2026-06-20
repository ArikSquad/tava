package eu.mikart.tava.dynamodb;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.*;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.*;
import eu.mikart.tava.schema.plan.*;
import eu.mikart.tava.spi.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.*;

final class DynamoDbAdapter implements Adapter {
    private final DynamoDbClient client;
    private final boolean owned;
    private final Map<String, EntityDefinition> definitions = new java.util.concurrent.ConcurrentHashMap<>();
    private final EntityStore store = new Store();
    private final SchemaManager schemas = new Schemas();

    DynamoDbAdapter(DynamoDbClient client, boolean owned) {
        this.client = Objects.requireNonNull(client);
        this.owned = owned;
    }

    @Override
    public String name() {
        return "dynamodb";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder(name())
                .supported(Feature.SECONDARY_INDEXES, Feature.CONDITIONAL_WRITES, Feature.PAGINATION,
                        Feature.PROJECTION, Feature.TTL, Feature.GENERATED_VALUES, Feature.JSON,
                        Feature.BINARY, Feature.BULK_READ, Feature.BULK_WRITE)
                .set(Feature.SCHEMA_ENFORCEMENT, SupportLevel.EMULATED, "Key and index schema only")
                .set(Feature.UNIQUE_CONSTRAINTS, SupportLevel.UNSUPPORTED, "Only primary keys are unique")
                .set(Feature.FOREIGN_KEYS, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.TRANSACTIONS, SupportLevel.NATIVE_ONLY, "Use DynamoDB transaction API")
                .set(Feature.SORTING, SupportLevel.NATIVE_ONLY, "Only Query supports sort-key order")
                .set(Feature.JOINS, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.AGGREGATION, SupportLevel.UNSUPPORTED, "Not supported")
                .set(Feature.FULL_TEXT, SupportLevel.UNSUPPORTED, "Not supported")
                .limit(Feature.BULK_WRITE, "batchSize", 25)
                .limit(Feature.BULK_READ, "batchSize", 100)
                .build();
    }

    @Override
    public SchemaManager schemas() {
        return schemas;
    }

    @Override
    public EntityStore entities() {
        return store;
    }

    @Override
    public NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            public <T> T nativeHandle(Class<T> type) {
                if (type != DynamoDbClient.class) throw new IllegalArgumentException("Adapter exposes DynamoDbClient");
                return type.cast(client);
            }
        };
    }

    @Override
    public void close() {
        if (owned) client.close();
    }

    private final class Store implements EntityStore {
        @Override
        public EntityRecord insert(String entity, EntityRecord record) {
            client.putItem(PutItemRequest.builder().tableName(entity).item(encode(record.values())).build());
            return record;
        }

        @Override
        public Page<EntityRecord> find(String entity, Query query) {
            Expression expression = expression(query.predicate());
            ScanRequest.Builder request = ScanRequest.builder().tableName(entity)
                    .limit(query.limit() == 0 ? 500 : query.limit());
            if (expression.text != null) request.filterExpression(expression.text)
                    .expressionAttributeNames(expression.names).expressionAttributeValues(expression.values);
            if (!query.projection().isEmpty()) {
                Map<String, String> names = new HashMap<>(expression.names);
                List<String> projected = new ArrayList<>();
                for (int i = 0; i < query.projection().size(); i++) {
                    String key = "#p" + i;
                    names.put(key, query.projection().get(i));
                    projected.add(key);
                }
                request.projectionExpression(String.join(",", projected)).expressionAttributeNames(names);
            }
            if (query.cursor() != null) request.exclusiveStartKey(decodeCursor(query.cursor()));
            ScanResponse response = client.scan(request.build());
            return new Page<>(response.items().stream().map(this::decode).toList(),
                    response.lastEvaluatedKey().isEmpty() ? null : encodeCursor(response.lastEvaluatedKey()));
        }

        @Override
        public long update(String entity, Predicate predicate, Mutation mutation) {
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
            client.updateItem(UpdateItemRequest.builder().tableName(entity).key(key)
                    .updateExpression("SET " + String.join(", ", updates))
                    .expressionAttributeNames(names).expressionAttributeValues(values).build());
            return 1;
        }

        @Override
        public long delete(String entity, Predicate predicate) {
            client.deleteItem(DeleteItemRequest.builder().tableName(entity).key(key(entity, predicate)).build());
            return 1;
        }

        private EntityRecord decode(Map<String, AttributeValue> item) {
            Map<String, Object> values = new LinkedHashMap<>();
            item.forEach((key, value) -> values.put(key, DynamoDbAdapter.this.decode(value)));
            return EntityRecord.of(values);
        }
    }

    private final class Schemas implements SchemaManager {
        @Override
        public Schema inspect() {
            List<EntityDefinition> entities = new ArrayList<>();
            for (String table : client.listTables().tableNames()) {
                EntityDefinition known = definitions.get(table);
                entities.add(known == null ? new EntityDefinition(table, List.of(), List.of(),
                        Map.of("dynamodb.schema", "keys-only")) : known);
            }
            return new Schema(entities);
        }

        @Override
        public SchemaPlan plan(Schema desired) {
            Set<String> current = new HashSet<>(client.listTables().tableNames());
            List<SchemaChange> changes = new ArrayList<>();
            List<Runnable> operations = new ArrayList<>();
            for (EntityDefinition entity : desired.entities()) {
                validateEntity(entity);
                definitions.put(entity.name(), entity);
                if (!current.contains(entity.name())) {
                    changes.add(new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, null));
                    operations.add(() -> create(entity));
                }
            }
            return new SchemaPlan(changes, () -> operations.forEach(Runnable::run));
        }
    }

    private void create(EntityDefinition entity) {
        FieldDefinition partition = partitionKey(entity);
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(entity.name())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName(partition.name())
                        .attributeType(scalarType(partition)).build())
                .keySchema(KeySchemaElement.builder().attributeName(partition.name()).keyType(KeyType.HASH).build())
                .build();
        client.createTable(request);
    }

    private void validateEntity(EntityDefinition entity) {
        FieldDefinition partition = partitionKey(entity);
        if (partition.type().logicalType() != LogicalType.STRING
                && partition.type().logicalType() != LogicalType.UUID
                && partition.type().logicalType() != LogicalType.INT32
                && partition.type().logicalType() != LogicalType.INT64
                && partition.type().logicalType() != LogicalType.BINARY) {
            throw new TavaException.Schema("DynamoDB partition key must be string, UUID, integer, or binary");
        }
        if (entity.fields().stream().anyMatch(FieldDefinition::unique))
            throw new TavaException.Schema("DynamoDB cannot enforce unique non-key fields");
    }

    private FieldDefinition partitionKey(EntityDefinition entity) {
        String configured = Objects.toString(entity.settings().get("dynamodb.partitionKey"), null);
        if (configured != null) return entity.field(configured);
        return entity.fields().stream().filter(FieldDefinition::identity).findFirst()
                .orElseThrow(() -> new TavaException.Schema(
                        "DynamoDB entity " + entity.name() + " requires an identity field or dynamodb.partitionKey"));
    }

    private ScalarAttributeType scalarType(FieldDefinition field) {
        return switch (field.type().logicalType()) {
            case INT32, INT64, DECIMAL -> ScalarAttributeType.N;
            case BINARY -> ScalarAttributeType.B;
            default -> ScalarAttributeType.S;
        };
    }

    private Map<String, AttributeValue> key(String entity, Predicate predicate) {
        EntityDefinition definition = definitions.get(entity);
        if (definition == null)
            throw new TavaException.Schema("Apply or inspect schema before keyed writes to " + entity);
        String key = partitionKey(definition).name();
        if (predicate instanceof Predicate.Comparison value
                && value.operator() == Predicate.Operator.EQ && value.field().equals(key)) {
            return Map.of(key, encode(value.value()));
        }
        throw new TavaException.Capability("DynamoDB update/delete requires equality on partition key " + key);
    }

    private record Expression(String text, Map<String, String> names, Map<String, AttributeValue> values) {
    }

    private Expression expression(Predicate predicate) {
        if (predicate == null || predicate instanceof Predicate.All) return new Expression(null, Map.of(), Map.of());
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        int[] sequence = {0};
        String text = expression(predicate, names, values, sequence);
        return new Expression(text, names, values);
    }

    private String expression(Predicate predicate, Map<String, String> names,
                              Map<String, AttributeValue> values, int[] sequence) {
        if (predicate instanceof Predicate.Comparison value) {
            int i = sequence[0]++;
            names.put("#f" + i, value.field());
            values.put(":v" + i, encode(value.value()));
            if (value.operator() == Predicate.Operator.CONTAINS)
                return "contains(#f" + i + ", :v" + i + ")";
            if (value.operator() == Predicate.Operator.STARTS_WITH)
                return "begins_with(#f" + i + ", :v" + i + ")";
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
        return junction.predicates().stream().map(value -> "(" + expression(value, names, values, sequence) + ")")
                .collect(java.util.stream.Collectors.joining(junction.and() ? " AND " : " OR "));
    }

    private static Map<String, AttributeValue> encode(Map<String, Object> values) {
        Map<String, AttributeValue> encoded = new LinkedHashMap<>();
        values.forEach((key, value) -> encoded.put(key, encode(value)));
        return encoded;
    }

    private static AttributeValue encode(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof Number number) return AttributeValue.builder().n(number.toString()).build();
        if (value instanceof Boolean bool) return AttributeValue.builder().bool(bool).build();
        if (value instanceof byte[] bytes) return AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
        if (value instanceof Collection<?> collection)
            return AttributeValue.builder().l(collection.stream().map(DynamoDbAdapter::encode).toList()).build();
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

    private static String encodeCursor(Map<String, AttributeValue> key) {
        String value = key.entrySet().stream().map(entry -> entry.getKey() + "\u001f" + scalar(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\u001e"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (String part : value.split("\u001e")) {
                String[] pair = part.split("\u001f", 2);
                key.put(pair[0], parseScalar(pair[1]));
            }
            return key;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid DynamoDB cursor", failure);
        }
    }

    private static String scalar(AttributeValue value) {
        if (value.s() != null) return "S:" + value.s();
        if (value.n() != null) return "N:" + value.n();
        if (value.b() != null) return "B:" + Base64.getEncoder().encodeToString(value.b().asByteArray());
        throw new IllegalArgumentException("Cursor key contains unsupported value");
    }

    private static AttributeValue parseScalar(String value) {
        return switch (value.substring(0, 2)) {
            case "S:" -> AttributeValue.builder().s(value.substring(2)).build();
            case "N:" -> AttributeValue.builder().n(value.substring(2)).build();
            case "B:" -> AttributeValue.builder().b(SdkBytes.fromByteArray(
                    Base64.getDecoder().decode(value.substring(2)))).build();
            default -> throw new IllegalArgumentException("Unsupported cursor value");
        };
    }
}
