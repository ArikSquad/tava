# Tava 1.2

Tava is a modular Java 21 database toolkit with one canonical entity model across relational,
document, and key-value databases.

## Modules

Import `tava-bom` and add only the adapters required by the application:

```kotlin
dependencies {
    implementation(platform("eu.mikart.tava:tava-bom:1.2.0"))
    implementation("eu.mikart.tava:tava-core")
    implementation("eu.mikart.tava:tava-postgres")
    implementation("eu.mikart.tava:tava-mongodb")
}
```

Adapters are available for PostgreSQL, MySQL/MariaDB, SQLite, H2, SQL Server, Oracle,
MongoDB, and DynamoDB. Managed services using these protocols use the same adapters.

## Canonical schema

```java

@Entity("accounts")
record Account(
        @Identity UUID id,
        @Required @Unique @Field(length = 255) String email,
        int score,
        @Generated(GeneratedValue.NOW) Instant createdAt
) {}

Schema schema = Schema.builder().record(Account.class).build();

try(Tava tava = Tava.open(Postgres.connect(jdbcUrl, user, password))) {
    SchemaPlan plan = tava.plan(schema);
    plan.apply(); // destructive and lossy changes require explicit options

    var accounts = tava.entity(Account.class);
    accounts.insert(new Account(UUID.randomUUID(), "ada@example.com", 7, null));

    Page<Account> page = accounts.find(Query.builder()
        .where(Predicate.eq("email", "ada@example.com"))
        .sort(Sort.desc("createdAt"))
        .limit(50)
        .build());
}
```

Canonical fields may include validated adapter settings:

```java

@AdapterSetting(adapter = "postgres", key = "type", value = "JSONB")
Map<String, Object> metadata
```

Adapter artifacts also provide typed annotations such as `@PostgresType("JSONB")`,
`@OracleType("CLOB")`, and `@DynamoPartitionKey`.

The canonical logical type and nullability remain authoritative. Unsupported mappings fail
schema planning rather than silently degrading.

## Data transfer

```java
TransferReport report = DataTransfer.copy(
        source,
        target,
        schema,
        TransferOptions.defaults()
);
```

Transfers are paged and support transformation callbacks, progress reporting, strict loss
handling, and schema planning on the target.

## Capabilities and native access

`tava.capabilities()` returns a machine-readable support matrix. Features that cannot be
portable—such as joins, full-text search, and backend-specific transactions—remain available
through typed native access:

```java
MongoDatabase mongo = tava.nativeHandle(MongoDatabase.class);

tava.nativeAccess().withNative(Connection.class, connection -> {
        // The JDBC connection is closed after the callback.
        return null;
});
```

The public API consistently uses entities, fields, and records. SQL-specific table/column
terminology is confined to JDBC adapter internals.

## Atomic writes and transactions

Tava 1.2 adds atomic typed `upsert`, insert-if-absent results with portable conflict
details, database-side increments and collection mutations, concise `findOne`, and
callback transactions for JDBC and MongoDB. Typed entities may target a runtime table or
collection name with `tava.entity(name, RecordType.class)`.

SQLite connections can configure foreign keys, synchronous mode, journal mode, and busy
timeouts through `SqliteOptions`; `Sqlite.backup(...)` creates a consistent flat-file
snapshot. Schema adoption can explicitly rename existing JDBC entities and fields with
`SchemaRenames`.
