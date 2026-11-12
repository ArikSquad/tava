package eu.mikart.tava.mongodb;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.ExceptionTranslatingAdapter;
import org.jetbrains.annotations.NotNull;

/** Factory methods for adapters backed by MongoDB. */
public final class Mongo {
    private Mongo() {}

    /**
     * Creates an adapter and client from a MongoDB connection string.
     *
     * @param connectionString MongoDB connection string
     * @param database database to use
     * @return adapter that owns and closes the created client
     */
    public static @NotNull Adapter connect(final @NotNull String connectionString, final @NotNull String database) {
        return translate(new MongoAdapter(MongoClients.create(connectionString), database, true));
    }

    /**
     * Wraps an application-managed MongoDB client.
     *
     * @param client client whose lifecycle remains owned by the application
     * @param database database to use
     * @return adapter backed by the supplied client
     */
    public static @NotNull Adapter use(final @NotNull MongoClient client, final @NotNull String database) {
        return translate(new MongoAdapter(client, database, false));
    }

    private static Adapter translate(Adapter adapter) {
        return new ExceptionTranslatingAdapter(adapter, failure -> {
            if (!(failure instanceof MongoException mongo)) return failure;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof MongoCommandException command
                        && command.getErrorCode() == 20
                        && command.getErrorMessage().contains("Transaction numbers are only allowed"))
                    return new TavaException.Capability(
                            "MongoDB transactions require a replica set or sharded cluster", failure);
            }
            if (failure instanceof MongoSocketException
                    || failure instanceof MongoTimeoutException
                    || failure instanceof MongoExecutionTimeoutException)
                return new TavaException.Connection("MongoDB connection or timeout failure", failure);
            if (ErrorCategory.fromErrorCode(mongo.getCode()) == ErrorCategory.DUPLICATE_KEY
                    || failure instanceof MongoBulkWriteException bulk
                            && bulk.getWriteErrors().stream()
                                    .anyMatch(error -> ErrorCategory.fromErrorCode(error.getCode())
                                            == ErrorCategory.DUPLICATE_KEY))
                return new TavaException.Conflict("MongoDB constraint conflict", failure);
            return new TavaException.Data("MongoDB operation failed", failure);
        });
    }
}
