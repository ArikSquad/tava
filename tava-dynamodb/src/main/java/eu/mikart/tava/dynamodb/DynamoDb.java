package eu.mikart.tava.dynamodb;

import eu.mikart.tava.spi.Adapter;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class DynamoDb {
    private DynamoDb() {
    }

    public static Adapter use(DynamoDbClient client) {
        return new DynamoDbAdapter(client, false);
    }

    public static Adapter createDefault() {
        return new DynamoDbAdapter(DynamoDbClient.create(), true);
    }
}
