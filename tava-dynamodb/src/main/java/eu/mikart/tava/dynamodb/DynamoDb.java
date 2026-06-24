package eu.mikart.tava.dynamodb;

import eu.mikart.tava.spi.Adapter;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class DynamoDb {
    private DynamoDb() {
    }

    public static @NotNull Adapter use(final @NotNull DynamoDbClient client) {
        return new DynamoDbAdapter(client, false);
    }

    public static @NotNull Adapter createDefault() {
        return new DynamoDbAdapter(DynamoDbClient.create(), true);
    }
}
