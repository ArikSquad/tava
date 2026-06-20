package eu.mikart.tava.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import eu.mikart.tava.spi.Adapter;

public final class Mongo {
    private Mongo() {
    }

    public static Adapter connect(String connectionString, String database) {
        return new MongoAdapter(MongoClients.create(connectionString), database, true);
    }

    public static Adapter use(MongoClient client, String database) {
        return new MongoAdapter(client, database, false);
    }
}
