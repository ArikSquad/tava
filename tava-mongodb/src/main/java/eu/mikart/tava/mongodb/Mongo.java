package eu.mikart.tava.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import eu.mikart.tava.spi.Adapter;
import org.jetbrains.annotations.NotNull;

public final class Mongo {
    private Mongo() {
    }

    public static @NotNull Adapter connect(final @NotNull String connectionString, final @NotNull String database) {
        return new MongoAdapter(MongoClients.create(connectionString), database, true);
    }

    public static @NotNull Adapter use(final @NotNull MongoClient client, final @NotNull String database) {
        return new MongoAdapter(client, database, false);
    }
}
