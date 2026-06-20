# Tava

Tava is a small Java 21 database toolkit. It provides a fluent schema and query API for PostgreSQL, MySQL, H2, and SQLite, plus a separate (for now) document API for MongoDB.

It stays close to JDBC and the MongoDB driver. Connections, BSON filters, SQL exceptions, and database-specific behavior remain visible.

## Requirements

- Java 21 or newer
- A JDBC driver for the SQL database you use
- MongoDB Java driver when using MongoDB

Packages are published to GitHub Packages:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/ArikSquad/tava")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}

dependencies {
    implementation("eu.mikart.tava:tava:0.2.0")
}
```

## SQL

Create a database from a built-in dialect:

```java
var database = Database.connect(Dialects.postgres(
        "localhost", 5432, "app", "app", "secret"
));
```

Container-managed applications can pass a complete JDBC URL:

```java
var database = Database.connect(Dialects.jdbc(
        "postgres", jdbcUrl, username, password
));
```

Define a table from an annotated record:

```java
record AccountTable(
        @Primary UUID id,
        @NotNull @Unique @Column(length = 255) String email,
        int score,
        boolean active,
        @DefaultNow Instant created_at
) {}

database.createTable("account", AccountTable.class);
```

Reference types are nullable unless marked `@NotNull` or `@Primary`. Primitive components are non-null. Tava infers strings, UUIDs, integral numbers, booleans, timestamps, byte arrays, and decimals. `BigDecimal` requires `@Column(precision = ..., scale = ...)`.

The fluent API remains available for database-specific definitions:

```java
database.createTable("account", table -> {
    table.uuid("id").primary();
    table.string("email", 255).unique();
    table.integer("score");
    table.bool("active");
    table.instant("created_at").defaultNow();
});
```

Use records for query results:

```java
record Account(
        UUID id,
        String email,
        int score,
        boolean active,
        Instant created_at
) {}

var id = UUID.randomUUID();

database.insert("account", row -> {
    row.set("id", id);
    row.set("email", "ada@example.com");
    row.set("score", 7);
    row.set("active", true);
});

List<Account> accounts = database.select(
        "account",
        Account.class,
        query -> query.eq("active", true).orderByDesc("created_at").limit(50)
);

database.update("account", row -> row.set("score", 8).eq("id", id));
database.delete("account", query -> query.eq("id", id));
```

### Migrations

```java
var database = Database.builder()
        .dialect(Dialects.h2("app"))
        .migration(new Migration(1, "create_account") {
            @Override
            public void up(SchemaBuilder schema) {
                schema.createTable("account", AccountTable.class);
            }
        })
        .build();

database.initialize();
```

## MongoDB

MongoDB has its own API because BSON queries and document updates are not SQL operations.

```java
try (var database = MongoDatabase.connect("mongodb://localhost:27017", "app")) {
    database.insert("accounts", new Document("email", "ada@example.com")
            .append("score", 7));

    var accounts = database.find("accounts", new MongoDatabase.Query()
            .eq("email", "ada@example.com")
            .descending("score")
            .limit(20));

    database.update(
            "accounts",
            Filters.eq("email", "ada@example.com"),
            Updates.set("score", 8)
    );
}
```

## Testing and releases

`./gradlew test` runs the embedded SQLite and H2 tests. When Docker is available, Testcontainers also runs the same relational behavior against PostgreSQL and MySQL and runs the MongoDB suite.

Tags matching `vX.Y.Z` run the full test suite, publish the package to GitHub Packages, and create a GitHub release with generated notes and build artifacts.

## License

MIT
