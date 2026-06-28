package eu.mikart.tava.dynamodb;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

@Testcontainers
class DynamoDbContainerTest {
    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
                    .withServices(DYNAMODB);

    @Test
    void runsSchemaCrudProjectionAndCursorPaging() {
        try (DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(DYNAMODB))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();
             Tava tava = Tava.open(DynamoDb.use(client))) {
            tava.plan(Schema.builder().entity("accounts", entity -> {
                entity.string("id").identity().field(36);
                entity.string("email").required().field(255);
                entity.integer("score").required();
            }).build()).apply();

            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", "a")
                    .set("email", "ada@example.com")
                    .set("score", 7)
                    .build());
            tava.records("accounts").insert(EntityRecord.builder()
                    .set("id", "g")
                    .set("email", "grace@example.com")
                    .set("score", 9)
                    .build());

            assertEquals(1, tava.records("accounts").find(Query.builder()
                    .where(Predicate.gte("score", 9)).build()).items().size());
            assertEquals(1, tava.records("accounts").update(Predicate.eq("id", "a"),
                    Mutation.builder().set("score", 8).build()));
            assertEquals(0, tava.records("accounts").update(Predicate.eq("id", "a"),
                    Mutation.builder().build()));

            var projected = tava.records("accounts").find(Query.builder()
                    .where(Predicate.eq("id", "a")).project("email").build()).items().getFirst();
            assertEquals("ada@example.com", projected.get("email"));
            assertFalse(projected.values().containsKey("score"));

            var firstPage = tava.records("accounts").find(Query.builder().limit(1).build());
            assertEquals(1, firstPage.items().size());
            assertNotNull(firstPage.nextCursor());
            var secondPage = tava.records("accounts").find(Query.builder()
                    .limit(1).cursor(firstPage.nextCursor()).build());
            assertEquals(1, secondPage.items().size());
            assertEquals(List.of(new BigDecimal("8"), new BigDecimal("9")), tava.records("accounts")
                    .find(Query.all()).items().stream()
                    .map(record -> (BigDecimal) record.get("score"))
                    .sorted()
                    .toList());

            assertEquals(1, tava.records("accounts").delete(Predicate.eq("id", "g")));
        }
    }
}
