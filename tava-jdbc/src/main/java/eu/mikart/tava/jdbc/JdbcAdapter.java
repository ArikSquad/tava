package eu.mikart.tava.jdbc;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.ConflictDetail;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.MutationResult;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.FieldDefinition;
import eu.mikart.tava.schema.FieldType;
import eu.mikart.tava.schema.GeneratedValue;
import eu.mikart.tava.schema.IndexDefinition;
import eu.mikart.tava.schema.LogicalType;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.SchemaRenames;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import eu.mikart.tava.spi.KeysetCursor;
import eu.mikart.tava.spi.KeysetPagination;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import eu.mikart.tava.spi.TransactionManager;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.intellij.lang.annotations.RegExp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** JDBC-backed implementation of the adapter SPI. */
public final class JdbcAdapter implements Adapter {
    private static final @RegExp String SAFE_NATIVE_TYPE_PATTERN = "[A-Za-z0-9_(), .]+";
    private static final Pattern SQLSERVER_NOW_DEFAULT_PATTERN =
            Pattern.compile("\\b(?:GETDATE|GETUTCDATE|SYSDATETIME|SYSUTCDATETIME|SYSDATETIMEOFFSET)\\s*\\(");

    /** Supplies connections for individual operations and transactions. */
    @FunctionalInterface
    public interface Connections {
        /**
         * Opens one connection for an adapter operation.
         *
         * @return connection that the adapter will close after use
         * @throws SQLException if the connection cannot be opened
         */
        @NotNull
        Connection open() throws SQLException;
    }

    private final JdbcProfile profile;
    private final Connections connections;
    private final Runnable closeAction;
    private final JdbcStore store = new JdbcStore();
    private final JdbcSchemas schemas = new JdbcSchemas();

    /**
     * Creates an adapter using a connection provider that remains externally managed.
     *
     * @param profile SQL dialect profile
     * @param connections connection provider
     */
    public JdbcAdapter(final @NotNull JdbcProfile profile, final @NotNull Connections connections) {
        this(profile, connections, () -> {});
    }

    /**
     * Creates an adapter using a connection provider and a provider cleanup action.
     *
     * @param profile SQL dialect profile
     * @param connections connection provider
     * @param closeAction cleanup action for externally managed connection resources
     */
    public JdbcAdapter(
            final @NotNull JdbcProfile profile,
            final @NotNull Connections connections,
            final @NotNull Runnable closeAction) {
        this.profile = Objects.requireNonNull(profile);
        this.connections = Objects.requireNonNull(connections);
        this.closeAction = Objects.requireNonNull(closeAction);
    }

    /**
     * Creates an adapter that opens connections through {@link DriverManager}.
     *
     * @param profile SQL dialect profile
     * @param url JDBC connection URL
     * @param user optional database user
     * @param password optional database password
     * @return adapter that closes each connection after use
     */
    public static @NotNull JdbcAdapter driverManager(
            final @NotNull JdbcProfile profile,
            final @NotNull String url,
            final @Nullable String user,
            final @Nullable String password) {
        return new JdbcAdapter(
                profile,
                () -> user == null
                        ? DriverManager.getConnection(url)
                        : DriverManager.getConnection(url, user, password));
    }

    @Override
    public @NotNull String name() {
        return profile.name();
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return profile.capabilities();
    }

    @Override
    public @NotNull SchemaManager schemas() {
        return schemas;
    }

    @Override
    public @NotNull EntityStore entities() {
        return store;
    }

    @Override
    public @NotNull TransactionManager transactions() {
        return new TransactionManager() {
            @Override
            public <R> R transaction(@NotNull Function<EntityStore, R> callback) {
                try (Connection connection = connections.open()) {
                    boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        R result = callback.apply(new JdbcStore(connection));
                        connection.commit();
                        return result;
                    } catch (RuntimeException | Error failure) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollback) {
                            failure.addSuppressed(rollback);
                        }
                        throw failure;
                    } finally {
                        connection.setAutoCommit(autoCommit);
                    }
                } catch (SQLException failure) {
                    throw data("Run transaction", failure);
                }
            }
        };
    }

    @Override
    public @NotNull NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            @SuppressWarnings("deprecation")
            public <T> @NotNull T nativeHandle(final @NotNull Class<T> type) {
                if (type != Connection.class) throw new IllegalArgumentException("JDBC adapter exposes Connection");
                try {
                    return type.cast(connections.open());
                } catch (SQLException failure) {
                    throw data("Open native connection", failure);
                }
            }

            @Override
            public <T, R> @NotNull R withNative(final @NotNull Class<T> type, final @NotNull Function<T, R> callback) {
                try (final Connection connection = connections.open()) {
                    return callback.apply(type.cast(connection));
                } catch (SQLException failure) {
                    throw data("Use native connection", failure);
                }
            }
        };
    }

    @Override
    public void close() {
        closeAction.run();
    }

    private final class JdbcStore implements EntityStore {
        private final Connection transaction;

        JdbcStore() {
            this.transaction = null;
        }

        JdbcStore(Connection transaction) {
            this.transaction = transaction;
        }

        private Lease lease() throws SQLException {
            return transaction == null ? new Lease(connections.open(), true) : new Lease(transaction, false);
        }

        @Override
        public @NotNull List<EntityRecord> insert(@NotNull String entity, @NotNull List<EntityRecord> records) {
            if (records.isEmpty()) return List.of();
            for (EntityRecord record : records) {
                if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            }
            try (Lease lease = lease()) {
                Connection connection = lease.connection;
                boolean owned = transaction == null;
                boolean previousAutoCommit = connection.getAutoCommit();
                if (owned) connection.setAutoCommit(false);
                Savepoint savepoint = null;
                try {
                    if (!owned) {
                        if (previousAutoCommit)
                            throw new SQLException("Transaction connection must disable auto-commit for batch inserts");
                        savepoint = connection.setSavepoint();
                    }
                    int start = 0;
                    while (start < records.size()) {
                        List<String> fields =
                                List.copyOf(records.get(start).values().keySet());
                        String sql = insertSql(entity, fields);
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            int end = start;
                            while (end < records.size()
                                    && fields.equals(List.copyOf(
                                            records.get(end).values().keySet()))) {
                                bind(statement, records.get(end).values().values());
                                statement.addBatch();
                                end++;
                            }
                            for (int result : statement.executeBatch()) {
                                if (result == Statement.EXECUTE_FAILED) throw new SQLException("Batch insert failed");
                            }
                            start = end;
                        }
                    }
                    if (owned) connection.commit();
                    return List.copyOf(records);
                } catch (SQLException | RuntimeException failure) {
                    if (owned)
                        try {
                            connection.rollback();
                        } catch (SQLException rollback) {
                            failure.addSuppressed(rollback);
                        }
                    else if (savepoint != null) {
                        try {
                            connection.rollback(savepoint);
                        } catch (SQLException rollback) {
                            rollback.addSuppressed(failure);
                            throw new TavaException.Transaction(
                                    "Rollback failed after batch insert into " + entity, rollback);
                        }
                    }
                    throw failure;
                } finally {
                    if (savepoint != null) {
                        try {
                            connection.releaseSavepoint(savepoint);
                        } catch (SQLException ignored) {
                        }
                    }
                    if (owned) connection.setAutoCommit(previousAutoCommit);
                }
            } catch (SQLException failure) {
                throw data("Batch insert into " + entity, failure);
            }
        }

        @Override
        public @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record) {
            if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            List<String> fields = new ArrayList<>(record.values().keySet());
            String sql = insertSql(entity, fields);
            try (Lease lease = lease();
                    PreparedStatement ps = lease.connection.prepareStatement(sql)) {
                bind(ps, record.values().values());
                ps.executeUpdate();
                return record;
            } catch (SQLException e) {
                throw data("Insert into " + entity, e);
            }
        }

        @Override
        public @NotNull MutationResult insertResult(@NotNull String entity, @NotNull EntityRecord record) {
            if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            List<String> fields = new ArrayList<>(record.values().keySet());
            String sql = insertSql(entity, fields);
            try (Lease lease = lease()) {
                Connection connection = lease.connection;
                Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
                try {
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        bind(ps, record.values().values());
                        ps.executeUpdate();
                    }
                    return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
                } catch (SQLException failure) {
                    if (isConflict(failure)) {
                        if (savepoint != null) {
                            try {
                                connection.rollback(savepoint);
                            } catch (SQLException rollbackFailure) {
                                rollbackFailure.addSuppressed(failure);
                                throw data("Recover from insert conflict into " + entity, rollbackFailure);
                            }
                        }
                        return new MutationResult(
                                0, 0, 1, MutationResult.Outcome.CONFLICT, List.of(conflictDetail(failure)));
                    }
                    throw data("Insert into " + entity, failure);
                } finally {
                    if (savepoint != null) {
                        try {
                            connection.releaseSavepoint(savepoint);
                        } catch (SQLException ignored) {
                        }
                    }
                }
            } catch (SQLException failure) {
                throw data("Insert into " + entity, failure);
            }
        }

        @Override
        public @NotNull MutationResult updateResult(
                @NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            long changed = update(entity, predicate, mutation);
            return new MutationResult(
                    -1, changed, 0, changed == 0 ? MutationResult.Outcome.UNCHANGED : MutationResult.Outcome.UPDATED);
        }

        @Override
        public @NotNull Page<EntityRecord> find(@NotNull String entity, @NotNull Query query) {
            try (Lease lease = lease()) {
                Connection connection = lease.connection;
                String namespace;
                try {
                    namespace = connection.getSchema();
                } catch (SQLFeatureNotSupportedException unsupported) {
                    namespace = null;
                }
                SortedMap<Integer, String> keys = new TreeMap<>();
                try (ResultSet primary =
                        connection.getMetaData().getPrimaryKeys(connection.getCatalog(), namespace, entity)) {
                    while (primary.next()) keys.put(primary.getInt("KEY_SEQ"), primary.getString("COLUMN_NAME"));
                }
                if (keys.isEmpty())
                    throw new TavaException.Capability("Pagination requires a primary key on " + entity);
                List<Sort> order = KeysetPagination.ordering(query.sorting(), List.copyOf(keys.values()));
                Predicate where = query.predicate();
                if (query.cursor() != null) {
                    List<Object> values =
                            KeysetCursor.decode(query.cursor(), profile.name() + ":" + entity, query, order);
                    where = Predicate.and(where, KeysetPagination.after(order, values));
                }
                List<Object> parameters = new ArrayList<>();
                String projection = query.projection().isEmpty()
                        ? profile.quote(entity) + ".*"
                        : query.projection().stream().map(profile::quote).collect(Collectors.joining(", "));
                String aliasPrefix =
                        "_tava_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
                List<String> extraColumns = new ArrayList<>(order.size());
                for (int i = 0; i < order.size(); i++) {
                    extraColumns.add(profile.quote(order.get(i).field()) + " AS " + profile.quote(aliasPrefix + i));
                }
                String extra = String.join(", ", extraColumns);
                StringBuilder sql = new StringBuilder("SELECT ")
                        .append(projection)
                        .append(", ")
                        .append(extra)
                        .append(" FROM ")
                        .append(profile.quote(entity));
                appendWhere(sql, parameters, where);
                sql.append(" ORDER BY ");
                sql.append(order.stream()
                        .map(sort -> {
                            String field = profile.quote(sort.field());
                            return "CASE WHEN " + field + " IS NULL THEN 0 ELSE 1 END " + sort.direction() + ", "
                                    + field + " " + sort.direction();
                        })
                        .collect(Collectors.joining(", ")));
                int requested = query.limit() == 0 ? 500 : query.limit();
                sql.append(profile.pagination(requested + 1, 0));
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    bind(statement, parameters);
                    try (ResultSet rs = statement.executeQuery()) {
                        ResultSetMetaData metadata = rs.getMetaData();
                        int visible = metadata.getColumnCount() - order.size();
                        List<EntityRecord> records = new ArrayList<>(Math.min(requested, 1023) + 1);
                        List<List<Object>> positions = new ArrayList<>(Math.min(requested, 1023) + 1);
                        while (rs.next()) {
                            Map<String, Object> values = new LinkedHashMap<>(visible);
                            for (int i = 1; i <= visible; i++) values.put(metadata.getColumnLabel(i), rs.getObject(i));
                            records.add(EntityRecord.of(values));
                            List<Object> position = new ArrayList<>(order.size());
                            for (int i = 1; i <= order.size(); i++) position.add(rs.getObject(visible + i));
                            positions.add(position);
                        }
                        boolean more = records.size() > requested;
                        if (more) records.removeLast();
                        String cursor = more
                                ? KeysetCursor.encode(
                                        profile.name() + ":" + entity, query, order, positions.get(requested - 1))
                                : null;
                        return new Page<>(records, cursor);
                    }
                }
            } catch (SQLException failure) {
                throw data("Query " + entity, failure);
            }
        }

        @Override
        public long update(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            if (mutation.values().isEmpty() && mutation.operations().isEmpty()) return 0;
            List<Object> parameters = new ArrayList<>(mutation.values().values());
            StringBuilder sql =
                    new StringBuilder("UPDATE ").append(profile.quote(entity)).append(" SET ");
            List<String> assignments = new ArrayList<>(
                    mutation.values().size() + mutation.operations().size());
            for (String field : mutation.values().keySet()) assignments.add(profile.quote(field) + " = ?");
            mutation.operations().forEach((field, operation) -> {
                String quoted = profile.quote(field);
                if (operation.kind() == Mutation.Kind.INCREMENT) {
                    assignments.add(quoted + " = " + quoted + " + ?");
                    parameters.add(operation.value());
                    return;
                }
                JdbcProfile.MutationExpression expression = profile.collectionMutation(quoted, operation);
                if (expression == null)
                    throw new TavaException.Capability(
                            profile.name() + " does not support " + operation.kind() + " database-side mutations");
                assignments.add(quoted + " = " + expression.sql());
                parameters.addAll(expression.parameters());
            });
            sql.append(String.join(", ", assignments));
            appendWhere(sql, parameters, predicate);
            try (Lease lease = lease();
                    PreparedStatement ps = lease.connection.prepareStatement(sql.toString())) {
                bind(ps, parameters);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw data("Update " + entity, e);
            }
        }

        @Override
        public long delete(@NotNull String entity, @NotNull Predicate predicate) {
            List<Object> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(profile.quote(entity));
            appendWhere(sql, parameters, predicate);
            try (Lease lease = lease();
                    PreparedStatement ps = lease.connection.prepareStatement(sql.toString())) {
                bind(ps, parameters);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw data("Delete from " + entity, e);
            }
        }

        @Override
        public @NotNull MutationResult upsert(
                @NotNull String entity,
                @NotNull Predicate identity,
                @NotNull EntityRecord insert,
                @NotNull Mutation update) {
            if (transaction == null) {
                RuntimeException last = null;
                for (int attempt = 0; attempt < 8; attempt++) {
                    try {
                        return transactions().transaction(store -> store.upsert(entity, identity, insert, update));
                    } catch (RuntimeException failure) {
                        SQLException sql = sqlCause(failure);
                        if (sql == null || !profile.retryableWrite(sql)) throw failure;
                        last = failure;
                        LockSupport.parkNanos((attempt + 1L) * 2_000_000L);
                    }
                }
                throw last;
            }
            long changed = update(entity, identity, update);
            if (changed > 0) return MutationResult.changed(changed, MutationResult.Outcome.UPDATED);
            Savepoint savepoint = null;
            try {
                try {
                    savepoint = transaction.setSavepoint();
                } catch (SQLException failure) {
                    throw data("Create upsert savepoint", failure);
                }
                insert(entity, insert);
                return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
            } catch (TavaException.Conflict conflict) {
                try {
                    if (savepoint != null) transaction.rollback(savepoint);
                } catch (SQLException failure) {
                    conflict.addSuppressed(failure);
                    throw conflict;
                }
                changed = update(entity, identity, update);
                if (changed > 0)
                    return new MutationResult(
                            changed,
                            changed,
                            1,
                            MutationResult.Outcome.UPDATED,
                            conflict.getCause() instanceof SQLException sql ? List.of(conflictDetail(sql)) : List.of());
                if (!find(entity, Query.builder().where(identity).limit(1).build())
                        .items()
                        .isEmpty())
                    return new MutationResult(
                            1,
                            0,
                            1,
                            MutationResult.Outcome.UNCHANGED,
                            conflict.getCause() instanceof SQLException sql ? List.of(conflictDetail(sql)) : List.of());
                throw conflict;
            } finally {
                try {
                    if (savepoint != null) transaction.releaseSavepoint(savepoint);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private String insertSql(String entity, List<String> fields) {
        return "INSERT INTO " + profile.quote(entity) + " ("
                + fields.stream().map(profile::quote).collect(Collectors.joining(", ")) + ") VALUES ("
                + String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
    }

    private static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final boolean owned;

        private Lease(Connection connection, boolean owned) {
            this.connection = connection;
            this.owned = owned;
        }

        @Override
        public void close() throws SQLException {
            if (owned) connection.close();
        }
    }

    private final class JdbcSchemas implements SchemaManager {
        @Override
        public @NotNull Schema inspect() {
            try (Connection c = connections.open()) {
                DatabaseMetaData meta = c.getMetaData();
                List<EntityDefinition> entities = new ArrayList<>();
                String schema;
                try {
                    schema = c.getSchema();
                } catch (SQLFeatureNotSupportedException unsupported) {
                    schema = null;
                }
                try (ResultSet tables = meta.getTables(c.getCatalog(), schema, "%", new String[] {"TABLE"})) {
                    while (tables.next()) {
                        String table = tables.getString("TABLE_NAME");
                        String catalog = tables.getString("TABLE_CAT");
                        String namespace = tables.getString("TABLE_SCHEM");
                        if (table.startsWith("SYSTEM_")) continue;
                        Set<String> identities = new HashSet<>();
                        try (ResultSet keys = meta.getPrimaryKeys(catalog, namespace, table)) {
                            while (keys.next()) identities.add(keys.getString("COLUMN_NAME"));
                        }
                        Map<String, SortedMap<Integer, String>> indexFields = new TreeMap<>();
                        Map<String, Boolean> uniqueIndexes = new HashMap<>();
                        for (JdbcProfile.IndexColumn index : profile.indexColumns(c, catalog, namespace, table)) {
                            indexFields
                                    .computeIfAbsent(index.index(), ignored -> new TreeMap<>())
                                    .put(index.position(), index.column());
                            uniqueIndexes.put(index.index(), index.unique());
                        }
                        List<IndexDefinition> indexes = new ArrayList<>();
                        Set<String> uniqueFields = new HashSet<>();
                        indexFields.forEach((name, columns) -> {
                            List<String> names = List.copyOf(columns.values());
                            boolean unique = uniqueIndexes.get(name);
                            indexes.add(new IndexDefinition(name, names, unique, Map.of()));
                            if (unique && names.size() == 1) uniqueFields.add(names.getFirst());
                        });
                        List<FieldDefinition> fields = new ArrayList<>();
                        String escape = meta.getSearchStringEscape();
                        String tablePattern = table.replace(escape, escape + escape)
                                .replace("_", escape + "_")
                                .replace("%", escape + "%");
                        try (ResultSet columns = meta.getColumns(catalog, namespace, tablePattern, "%")) {
                            while (columns.next()) {
                                String name = columns.getString("COLUMN_NAME");
                                String defaultValue = columns.getString("COLUMN_DEF");
                                GeneratedValue generated = "YES".equals(columns.getString("IS_AUTOINCREMENT"))
                                        ? GeneratedValue.IDENTITY
                                        : defaultValue != null && isNowDefault(defaultValue)
                                                ? GeneratedValue.NOW
                                                : GeneratedValue.NONE;
                                Map<String, Object> settings = new HashMap<>();
                                settings.put(profile.name() + ".type", columns.getString("TYPE_NAME"));
                                if (defaultValue != null) settings.put("jdbc.default", defaultValue);
                                settings.put("jdbc.size", columns.getInt("COLUMN_SIZE"));
                                settings.put("jdbc.scale", columns.getInt("DECIMAL_DIGITS"));
                                fields.add(new FieldDefinition(
                                        name,
                                        jdbcType(
                                                columns.getInt("DATA_TYPE"),
                                                columns.getInt("COLUMN_SIZE"),
                                                columns.getInt("DECIMAL_DIGITS")),
                                        !identities.contains(name)
                                                && columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                        identities.contains(name),
                                        uniqueFields.contains(name),
                                        generated,
                                        settings));
                            }
                        }
                        entities.add(new EntityDefinition(table, fields, indexes, Map.of()));
                    }
                }
                return new Schema(entities);
            } catch (SQLException e) {
                throw new TavaException.Schema("Inspect JDBC schema", e);
            }
        }

        @Override
        public @NotNull SchemaPlan plan(@NotNull Schema desired) {
            return plan(desired, SchemaRenames.none());
        }

        @Override
        public @NotNull SchemaPlan planExact(@NotNull Schema desired) {
            Schema actual = inspect();
            SchemaPlan additive = plan(desired);
            List<SchemaChange> changes = new ArrayList<>(additive.changes());
            for (EntityDefinition current : actual.entities()) {
                EntityDefinition target = desired.entities().stream()
                        .filter(value -> value.name().equalsIgnoreCase(current.name()))
                        .findFirst()
                        .orElse(null);
                if (target == null) {
                    changes.add(new SchemaChange("Remove entity " + current.name(), ChangeRisk.UNSUPPORTED, null));
                    continue;
                }
                for (FieldDefinition field : current.fields()) {
                    FieldDefinition wanted = target.fields().stream()
                            .filter(value -> value.name().equalsIgnoreCase(field.name()))
                            .findFirst()
                            .orElse(null);
                    if (wanted == null)
                        changes.add(new SchemaChange(
                                "Remove field " + current.name() + "." + field.name(), ChangeRisk.UNSUPPORTED, null));
                    else if (field.unique() && !field.identity() && !wanted.unique())
                        changes.add(new SchemaChange(
                                "Remove uniqueness of " + current.name() + "." + field.name(),
                                ChangeRisk.UNSUPPORTED,
                                null));
                }
                for (IndexDefinition index : current.indexes()) {
                    boolean declared = target.indexes().stream()
                            .anyMatch(value -> value.name().equalsIgnoreCase(index.name()));
                    boolean identity = index.unique()
                            && new HashSet<>(index.fields())
                                    .equals(current.fields().stream()
                                            .filter(FieldDefinition::identity)
                                            .map(FieldDefinition::name)
                                            .collect(Collectors.toSet()));
                    boolean uniqueField = index.unique()
                            && index.fields().size() == 1
                            && target.fields().stream()
                                    .anyMatch(value -> value.name()
                                                    .equalsIgnoreCase(
                                                            index.fields().getFirst())
                                            && value.unique());
                    if (!declared && !identity && !uniqueField)
                        changes.add(new SchemaChange("Remove index " + index.name(), ChangeRisk.UNSUPPORTED, null));
                }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                additive.application().run();
            });
        }

        @Override
        public @NotNull SchemaPlan plan(@NotNull Schema desired, @NotNull SchemaRenames renames) {
            validate(desired);
            Schema actual = inspect();
            List<SchemaChange> changes = new ArrayList<>();
            List<String> statements = new ArrayList<>();
            Map<String, EntityDefinition> existing = new HashMap<>();
            Set<String> actualNames = actual.entities().stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            for (EntityDefinition current : actual.entities()) {
                String finalName = renames.entities().getOrDefault(current.name(), current.name());
                if (!finalName.equals(current.name())) {
                    if (actualNames.contains(finalName.toLowerCase(Locale.ROOT)))
                        throw new TavaException.Schema(
                                "Cannot rename entity " + current.name() + " to existing entity " + finalName);
                    String statement = profile.renameEntity(current.name(), finalName);
                    statements.add(statement);
                    changes.add(new SchemaChange(
                            "Rename entity " + current.name() + " to " + finalName, ChangeRisk.SAFE, statement));
                }
                Map<String, String> fieldRenames = renames.fields().getOrDefault(finalName, Map.of());
                Set<String> currentFieldNames = current.fields().stream()
                        .map(value -> value.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                List<FieldDefinition> fields = new ArrayList<>();
                for (FieldDefinition field : current.fields()) {
                    String finalField = fieldRenames.getOrDefault(field.name(), field.name());
                    if (!finalField.equals(field.name())) {
                        if (currentFieldNames.contains(finalField.toLowerCase(Locale.ROOT)))
                            throw new TavaException.Schema("Cannot rename field " + finalName + "." + field.name()
                                    + " to existing field " + finalField);
                        String statement = profile.renameField(finalName, field.name(), finalField);
                        statements.add(statement);
                        changes.add(new SchemaChange(
                                "Rename field " + finalName + "." + field.name() + " to " + finalField,
                                ChangeRisk.SAFE,
                                statement));
                    }
                    fields.add(new FieldDefinition(
                            finalField,
                            field.type(),
                            field.nullable(),
                            field.identity(),
                            field.unique(),
                            field.generated(),
                            field.settings()));
                }
                existing.put(
                        finalName.toLowerCase(Locale.ROOT),
                        new EntityDefinition(
                                finalName,
                                fields,
                                current.indexes().stream()
                                        .map(index -> new IndexDefinition(
                                                index.name(),
                                                index.fields().stream()
                                                        .map(name -> fieldRenames.getOrDefault(name, name))
                                                        .toList(),
                                                index.unique(),
                                                index.settings()))
                                        .toList(),
                                current.settings()));
            }
            for (EntityDefinition entity : desired.entities()) {
                EntityDefinition current = existing.get(entity.name().toLowerCase(Locale.ROOT));
                if (current == null) {
                    statements.add(createEntity(entity));
                    // Keep the generated SQL attached so callers can display or audit the plan.
                    changes.add(
                            new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, statements.getLast()));
                    for (IndexDefinition index : entity.indexes()) {
                        statements.add(createIndex(entity.name(), index));
                        changes.add(new SchemaChange(
                                "Create index " + index.name(), ChangeRisk.SAFE, statements.getLast()));
                    }
                } else {
                    Set<String> currentFields = new HashSet<>();
                    current.fields()
                            .forEach(field -> currentFields.add(field.name().toLowerCase(Locale.ROOT)));
                    for (FieldDefinition field : entity.fields()) {
                        FieldDefinition actualField = current.fields().stream()
                                .filter(value -> value.name().equalsIgnoreCase(field.name()))
                                .findFirst()
                                .orElse(null);
                        if (actualField != null) {
                            if (!compatibleType(field, actualField))
                                changes.add(new SchemaChange(
                                        "Change type of " + entity.name() + "." + field.name(),
                                        ChangeRisk.UNSUPPORTED,
                                        null));
                            if (actualField.identity() != field.identity())
                                changes.add(new SchemaChange(
                                        "Change identity of " + entity.name() + "." + field.name(),
                                        ChangeRisk.UNSUPPORTED,
                                        null));
                            if (field.unique() && !actualField.unique()) {
                                String statement = createIndex(
                                        entity.name(),
                                        new IndexDefinition(
                                                entity.name() + "_" + field.name() + "_unique",
                                                List.of(field.name()),
                                                true,
                                                Map.of()));
                                statements.add(statement);
                                changes.add(new SchemaChange(
                                        "Enforce uniqueness of " + entity.name() + "." + field.name(),
                                        ChangeRisk.SAFE,
                                        statement));
                            }
                            if (field.generated() != GeneratedValue.UUID
                                    && actualField.generated() != field.generated())
                                changes.add(new SchemaChange(
                                        "Change generation of " + entity.name() + "." + field.name(),
                                        ChangeRisk.UNSUPPORTED,
                                        null));
                        }
                        if (actualField != null && actualField.nullable() != field.nullable()) {
                            changes.add(new SchemaChange(
                                    "Change nullability of " + entity.name() + "." + field.name(),
                                    ChangeRisk.UNSUPPORTED,
                                    null));
                        }
                        if (!currentFields.contains(field.name().toLowerCase(Locale.ROOT))) {
                            String statement =
                                    "ALTER TABLE " + profile.quote(entity.name()) + " ADD " + fieldSql(field);
                            statements.add(statement);
                            ChangeRisk risk = field.nullable() || field.generated() == GeneratedValue.NOW
                                    ? ChangeRisk.SAFE
                                    : ChangeRisk.UNSUPPORTED;
                            changes.add(new SchemaChange(
                                    "Add field " + entity.name() + "." + field.name(), risk, statement));
                        }
                    }
                    for (IndexDefinition index : entity.indexes()) {
                        IndexDefinition previous = current.indexes().stream()
                                .filter(value -> value.name().equalsIgnoreCase(index.name()))
                                .findFirst()
                                .orElse(null);
                        if (previous == null) {
                            String statement = createIndex(entity.name(), index);
                            statements.add(statement);
                            changes.add(new SchemaChange("Create index " + index.name(), ChangeRisk.SAFE, statement));
                        } else if (!previous.fields().stream()
                                        .map(value -> value.toLowerCase(Locale.ROOT))
                                        .toList()
                                        .equals(index.fields().stream()
                                                .map(value -> value.toLowerCase(Locale.ROOT))
                                                .toList())
                                || previous.unique() != index.unique()) {
                            changes.add(
                                    new SchemaChange("Replace index " + index.name(), ChangeRisk.UNSUPPORTED, null));
                        }
                    }
                }
            }
            return new SchemaPlan(changes, () -> {
                if (!actual.equals(inspect()))
                    throw new TavaException.Schema("Schema changed after planning; create a new plan");
                execute(statements);
            });
        }
    }

    private boolean compatibleType(FieldDefinition desired, FieldDefinition actual) {
        String expected = nativeType(desired);
        if (expected == null) expected = profile.type(desired);
        String found = Objects.toString(actual.settings().get(profile.name() + ".type"), "");
        if (!normalizeType(expected).equals(normalizeType(found))) return false;
        Matcher dimensions = Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(expected);
        if (!profile.name().equals("sqlite") && dimensions.find()) {
            if (Integer.parseInt(dimensions.group(1))
                    != ((Number) actual.settings().get("jdbc.size")).intValue()) return false;
            if (dimensions.group(2) != null
                    && Integer.parseInt(dimensions.group(2))
                            != ((Number) actual.settings().get("jdbc.scale")).intValue()) return false;
        }
        if (desired.type().length() != null
                && !profile.name().equals("sqlite")
                && !desired.type().length().equals(actual.settings().get("jdbc.size"))) return false;
        if (desired.type().precision() != null && !profile.name().equals("sqlite")) {
            if (!desired.type().precision().equals(actual.settings().get("jdbc.size"))) return false;
            if (!Objects.equals(desired.type().scale(), actual.settings().get("jdbc.scale"))) return false;
        }
        return true;
    }

    private static String normalizeType(String type) {
        String normalized = type.toUpperCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .strip()
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "CHARACTER VARYING", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> "VARCHAR";
            case "CHARACTER LARGE OBJECT", "CLOB", "LONGVARCHAR" -> "TEXT";
            case "INT", "INT4", "INT32" -> "INTEGER";
            case "INT8", "INT64" -> "BIGINT";
            case "FLOAT8", "DOUBLE" -> "DOUBLE PRECISION";
            case "BOOL", "BIT" -> "BOOLEAN";
            case "TIMESTAMPTZ", "DATETIMEOFFSET" -> "TIMESTAMP WITH TIME ZONE";
            case "DECIMAL" -> "NUMERIC";
            case "BINARY LARGE OBJECT" -> "BLOB";
            default -> normalized;
        };
    }

    private boolean isNowDefault(String defaultValue) {
        String normalized = defaultValue.toUpperCase(Locale.ROOT);
        if (normalized.contains("CURRENT_TIMESTAMP")) return true;
        return profile.name().equals("sqlserver")
                && SQLSERVER_NOW_DEFAULT_PATTERN.matcher(normalized).find();
    }

    private void validate(Schema schema) {
        for (EntityDefinition entity : schema.entities()) {
            for (FieldDefinition field : entity.fields()) {
                String nativeType = nativeType(field);
                if (nativeType != null
                        && (!nativeType.matches(SAFE_NATIVE_TYPE_PATTERN)
                                || nativeType.contains("--")
                                || nativeType.contains("/*"))) {
                    throw new TavaException.Schema(
                            "Unsafe native type override for " + entity.name() + "." + field.name());
                }
            }
        }
    }

    private String createEntity(EntityDefinition entity) {
        String fields = entity.fields().stream().map(this::fieldSql).collect(Collectors.joining(", "));
        List<String> identities = entity.fields().stream()
                .filter(FieldDefinition::identity)
                .map(field -> profile.quote(field.name()))
                .toList();
        if (!identities.isEmpty()) fields += ", PRIMARY KEY (" + String.join(", ", identities) + ")";
        return "CREATE TABLE " + (profile.supportsIfNotExists() ? "IF NOT EXISTS " : "") + profile.quote(entity.name())
                + " (" + fields + ")";
    }

    private String fieldSql(FieldDefinition field) {
        String nativeType = nativeType(field);
        StringBuilder sql = new StringBuilder(profile.quote(field.name()))
                .append(' ')
                .append(nativeType == null ? profile.type(field) : nativeType);
        String identity = profile.identityClause(field);
        if (!identity.isBlank()) sql.append(' ').append(identity);
        if (field.generated() == GeneratedValue.NOW) sql.append(" DEFAULT CURRENT_TIMESTAMP");
        if (!field.nullable()) sql.append(" NOT NULL");
        if (field.unique()) sql.append(" UNIQUE");
        return sql.toString();
    }

    private String nativeType(FieldDefinition field) {
        Object value = field.settings().get(profile.name() + ".type");
        if (value == null && profile.name().equals("mariadb"))
            value = field.settings().get("mysql.type");
        return Objects.toString(value, null);
    }

    private String createIndex(String entity, IndexDefinition index) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + profile.quote(index.name())
                + " ON " + profile.quote(entity) + " ("
                + index.fields().stream().map(profile::quote).collect(Collectors.joining(", ")) + ")";
    }

    private void execute(List<String> statements) {
        if (statements.isEmpty()) return;
        try (Connection c = connections.open();
                Statement statement = c.createStatement()) {
            boolean previous = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (String sql : statements) statement.execute(sql);
                c.commit();
            } catch (SQLException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw new TavaException.Schema("Apply JDBC schema plan", e);
        }
    }

    private void appendWhere(StringBuilder sql, List<Object> parameters, Predicate predicate) {
        if (predicate == null || predicate instanceof Predicate.All) return;
        sql.append(" WHERE ").append(predicateSql(predicate, parameters));
    }

    private String predicateSql(Predicate predicate, List<Object> parameters) {
        if (predicate instanceof Predicate.Comparison comparison) {
            String field = profile.quote(comparison.field());
            if (comparison.operator() == Predicate.Operator.IS_NULL) return field + " IS NULL";
            if (comparison.operator() == Predicate.Operator.IS_NOT_NULL) return field + " IS NOT NULL";
            if (comparison.value() == null) {
                return switch (comparison.operator()) {
                    case EQ -> field + " IS NULL";
                    case NE -> field + " IS NOT NULL";
                    default ->
                        throw new TavaException.Capability(
                                "JDBC predicate " + comparison.operator() + " does not support null values");
                };
            }
            if (comparison.operator() == Predicate.Operator.IN) {
                if (!(comparison.value() instanceof Collection<?> values) || values.isEmpty())
                    throw new IllegalArgumentException("IN requires at least one value");
                parameters.addAll(values);
                return field + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")";
            }
            if (comparison.operator() == Predicate.Operator.CONTAINS
                    || comparison.operator() == Predicate.Operator.STARTS_WITH) {
                parameters.add(
                        comparison.operator() == Predicate.Operator.CONTAINS
                                ? "%" + comparison.value() + "%"
                                : comparison.value() + "%");
                return field + " LIKE ?";
            }
            parameters.add(comparison.value());
            String operator = switch (comparison.operator()) {
                case EQ -> "=";
                case NE -> "<>";
                case LT -> "<";
                case LTE -> "<=";
                case GT -> ">";
                case GTE -> ">=";
                default -> throw new TavaException.Capability("Unsupported JDBC predicate " + comparison.operator());
            };
            return field + " " + operator + " ?";
        }
        if (predicate instanceof Predicate.Junction junction) {
            return junction.predicates().stream()
                    .map(value -> "(" + predicateSql(value, parameters) + ")")
                    .collect(Collectors.joining(junction.and() ? " AND " : " OR "));
        }
        return "1 = 1";
    }

    private static void bind(PreparedStatement statement, Collection<?> values) throws SQLException {
        int index = 1;
        for (Object value : values) {
            if (value instanceof Instant instant) statement.setTimestamp(index++, Timestamp.from(instant));
            else if (value instanceof Collection<?> || value instanceof Map<?, ?>)
                statement.setString(index++, ValueCodecs.toJson(value));
            else if (value instanceof Enum<?> enumeration) statement.setString(index++, enumeration.name());
            else statement.setObject(index++, value);
        }
    }

    private static FieldType jdbcType(int type, int size, int scale) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> FieldType.of(LogicalType.INT32);
            case Types.BIGINT -> FieldType.of(LogicalType.INT64);
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> FieldType.of(LogicalType.FLOAT64);
            case Types.NUMERIC, Types.DECIMAL -> FieldType.decimal(Math.max(size, 1), Math.max(scale, 0));
            case Types.BOOLEAN, Types.BIT -> FieldType.of(LogicalType.BOOLEAN);
            case Types.DATE -> FieldType.of(LogicalType.LOCAL_DATE);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> FieldType.of(LogicalType.INSTANT);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> FieldType.of(LogicalType.BINARY);
            case Types.CHAR, Types.VARCHAR, Types.NVARCHAR -> FieldType.string(Math.max(size, 1));
            default -> FieldType.of(LogicalType.TEXT);
        };
    }

    private static TavaException data(String action, SQLException cause) {
        String message = action + " failed: " + cause.getMessage();
        String state = cause.getSQLState();
        if (isConflict(cause)) return new TavaException.Conflict(message, cause);
        if (state != null && state.startsWith("08")) return new TavaException.Connection(message, cause);
        return new TavaException.Data(message, cause);
    }

    private static boolean isConflict(SQLException cause) {
        String state = cause.getSQLState();
        int code = cause.getErrorCode();
        if ("23505".equals(state) || code == 1 || code == 1062 || code == 2601 || code == 2627) return true;
        if (code != 19 || cause.getMessage() == null) return false;
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("unique constraint failed") || message.contains("primary key");
    }

    private static ConflictDetail conflictDetail(SQLException cause) {
        String state = cause.getSQLState();
        return new ConflictDetail(
                ConflictDetail.Kind.UNIQUE,
                null,
                state == null ? Integer.toString(cause.getErrorCode()) : state,
                cause.getMessage());
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql;
        return null;
    }
}
