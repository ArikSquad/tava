# Tava 1.3.3

Tava is a modular Java 21 database toolkit with one canonical entity model across relational,
document, and key-value databases.

## Modules

Import `tava-bom` and add only the adapters required by the application:

```kotlin
repositories {
    maven("https://repo.codemc.io/repository/ArikSquad/")
}

dependencies {
    implementation(platform("eu.mikart.tava:tava-bom:1.3.3"))
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

Schema schema = Schema.of(Account.class);

try(Tava tava = Tava.open(Postgres.connect(jdbcUrl, user, password))) {
    tava.schema().ensure(schema); // safe additive setup; use SchemaHistory for versioned migrations

    var accounts = tava.entity(Account.class);
    accounts.insert(new Account(UUID.randomUUID(), "ada@example.com", 7, null));

    Page<Account> page = accounts.find(Query.builder()
        .where(Predicate.eq("email", "ada@example.com"))
        .sort(Sort.desc("createdAt"))
        .limit(50)
        .build());
}
```
