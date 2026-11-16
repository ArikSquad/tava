package eu.mikart.tava.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.testkit.AdapterContractTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

@Testcontainers(disabledWithoutDocker = true)
class DynamoDbContainerTest extends AdapterContractTest {
    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0")).withServices("dynamodb");

    @Override
    protected Tava openTava(String namespace) {
        return Tava.open(DynamoDb.use(DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build()));
    }

    @Override
    protected boolean supportsGlobalSorting() {
        return false;
    }

    @Override
    protected boolean supportsInPredicate() {
        return false;
    }

    @Test
    void queriesRangesAndResidualFiltersWithConfiguredConsistency() {
        List<SdkRequest> reads = new ArrayList<>();
        try (DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.of(LOCALSTACK.getRegion()))
                .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
                    @Override
                    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
                        if (context.request() instanceof QueryRequest || context.request() instanceof ScanRequest)
                            reads.add(context.request());
                    }
                }))
                .build()) {
            for (boolean consistent : List.of(true, false)) {
                String entity = "ranges_" + UUID.randomUUID().toString().replace("-", "");
                try (Tava tava = Tava.open(DynamoDb.use(client, consistent))) {
                    tava.plan(Schema.builder()
                                    .entity(entity, e -> {
                                        e.string("tenant").identity();
                                        e.string("position").identity();
                                        e.string("state");
                                    })
                                    .build())
                            .apply();
                    for (String position : List.of("a", "b", "c")) {
                        tava.records(entity)
                                .insert(EntityRecord.builder()
                                        .set("tenant", "one")
                                        .set("position", position)
                                        .set("state", "active")
                                        .build());
                    }
                    reads.clear();
                    var range = tava.records(entity)
                            .find(Query.builder()
                                    .where(Predicate.and(
                                            Predicate.eq("tenant", "one"),
                                            Predicate.gte("position", "b"),
                                            Predicate.eq("state", "active")))
                                    .build());
                    assertEquals(2, range.items().size());
                    QueryRequest request = assertInstanceOf(QueryRequest.class, reads.getLast());
                    assertEquals(consistent, request.consistentRead());
                    assertTrue(request.keyConditionExpression().contains(">="));
                    assertNotNull(request.filterExpression());
                    assertFalse(reads.stream().anyMatch(ScanRequest.class::isInstance));

                    assertEquals(
                            1,
                            tava.records(entity)
                                    .find(Query.builder()
                                            .where(Predicate.and(
                                                    Predicate.eq("tenant", "one"),
                                                    Predicate.startsWith("position", "b")))
                                            .build())
                                    .items()
                                    .size());
                    assertTrue(assertInstanceOf(QueryRequest.class, reads.getLast())
                            .keyConditionExpression()
                            .contains("begins_with"));

                    tava.records(entity)
                            .find(Query.builder()
                                    .where(Predicate.or(Predicate.eq("tenant", "one"), Predicate.eq("position", "b")))
                                    .build());
                    assertEquals(
                            consistent,
                            assertInstanceOf(ScanRequest.class, reads.getLast()).consistentRead());
                }
            }
        }
    }

    @Test
    void runsSchemaCrudProjectionAndCursorPaging() {
        try (DynamoDbClient client = DynamoDbClient.builder()
                        .endpointOverride(LOCALSTACK.getEndpoint())
                        .credentialsProvider(
                                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .region(Region.of(LOCALSTACK.getRegion()))
                        .build();
                Tava tava = Tava.open(DynamoDb.use(client))) {
            tava.plan(Schema.builder()
                            .entity("accounts", entity -> {
                                entity.string("id").identity().length(36);
                                entity.string("email").required().length(255);
                                entity.integer("score").required();
                            })
                            .build())
                    .apply();

            tava.records("accounts")
                    .insert(EntityRecord.builder()
                            .set("id", "a")
                            .set("email", "ada@example.com")
                            .set("score", 7)
                            .build());
            tava.records("accounts")
                    .insert(EntityRecord.builder()
                            .set("id", "g")
                            .set("email", "grace@example.com")
                            .set("score", 9)
                            .build());

            assertEquals(
                    1,
                    tava.records("accounts")
                            .find(Query.builder()
                                    .where(Predicate.gte("score", 9))
                                    .build())
                            .items()
                            .size());
            assertEquals(
                    1,
                    tava.records("accounts")
                            .update(
                                    Predicate.eq("id", "a"),
                                    Mutation.builder().set("score", 8).build()));
            assertEquals(
                    0,
                    tava.records("accounts")
                            .update(Predicate.eq("id", "a"), Mutation.builder().build()));

            var projected = tava.records("accounts")
                    .find(Query.builder()
                            .where(Predicate.eq("id", "a"))
                            .project("email")
                            .build())
                    .items()
                    .getFirst();
            assertEquals("ada@example.com", projected.get("email"));
            assertFalse(projected.values().containsKey("score"));

            var firstPage =
                    tava.records("accounts").find(Query.builder().limit(1).build());
            assertEquals(1, firstPage.items().size());
            assertNotNull(firstPage.nextCursor());
            var secondPage = tava.records("accounts")
                    .find(Query.builder()
                            .limit(1)
                            .cursor(firstPage.nextCursor())
                            .build());
            assertEquals(1, secondPage.items().size());
            assertEquals(
                    List.of(new BigDecimal("8"), new BigDecimal("9")),
                    tava.records("accounts").find(Query.all()).items().stream()
                            .map(record -> (BigDecimal) record.get("score"))
                            .sorted()
                            .toList());

            assertEquals(1, tava.records("accounts").delete(Predicate.eq("id", "g")));
        }
    }
}
