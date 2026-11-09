package eu.mikart.tava.dynamodb;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.ExceptionTranslatingAdapter;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

/** Factory methods for adapters backed by Amazon DynamoDB. */
public final class DynamoDb {
    private DynamoDb() {}

    /**
     * Wraps an application-managed DynamoDB client.
     *
     * @param client client whose lifecycle remains owned by the application
     * @return adapter backed by the supplied client
     */
    public static @NotNull Adapter use(final @NotNull DynamoDbClient client) {
        return translate(new DynamoDbAdapter(client, false));
    }

    /**
     * Creates an adapter with an internally managed default DynamoDB client.
     *
     * @return adapter that owns and closes its client
     */
    public static @NotNull Adapter createDefault() {
        return translate(new DynamoDbAdapter(DynamoDbClient.create(), true));
    }

    private static Adapter translate(Adapter adapter) {
        return new ExceptionTranslatingAdapter(adapter, failure -> {
            if (failure instanceof ConditionalCheckFailedException)
                return new TavaException.Conflict("DynamoDB conditional write conflict", failure);
            if (failure instanceof SdkClientException)
                return new TavaException.Connection("DynamoDB connection or timeout failure", failure);
            if (failure instanceof DynamoDbException)
                return new TavaException.Data("DynamoDB operation failed", failure);
            return failure;
        });
    }
}
