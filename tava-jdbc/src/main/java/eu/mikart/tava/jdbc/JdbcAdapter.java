package eu.mikart.tava.jdbc;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.ConflictDetail;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.*;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class JdbcAdapter implements Adapter {
    @FunctionalInterface
    public interface Connections {
        @NotNull Connection open() throws SQLException;
    }

    private final JdbcProfile profile;
    private final Connections connections;
    private final Runnable closeAction;
    private final JdbcStore store = new JdbcStore();
    private final JdbcSchemas schemas = new JdbcSchemas();

    public JdbcAdapter(final @NotNull JdbcProfile profile, final @NotNull Connections connections) {
        this(profile, connections, () -> {
        });
    }

    public JdbcAdapter(
            final @NotNull JdbcProfile profile,
            final @NotNull Connections connections,
            final @NotNull Runnable closeAction
    ) {
        this.profile = Objects.requireNonNull(profile);
        this.connections = Objects.requireNonNull(connections);
        this.closeAction = Objects.requireNonNull(closeAction);
    }

    public static @NotNull JdbcAdapter driverManager(
            final @NotNull JdbcProfile profile,
            final @NotNull String url,
            final @Nullable String user,
            final @Nullable String password
    ) {
        return new JdbcAdapter(profile, () -> user == null
                ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password));
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
            @Override public <R> R transaction(@NotNull Function<EntityStore, R> callback) {
                try (Connection connection = connections.open()) {
                    boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        R result = callback.apply(new JdbcStore(connection));
                        connection.commit();
                        return result;
                    } catch (RuntimeException | Error failure) {
                        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                        throw failure;
                    } finally { connection.setAutoCommit(autoCommit); }
                } catch (SQLException failure) { throw data("Run transaction", failure); }
            }
        };
    }

    @Override
    public @NotNull NativeAccess nativeAccess() {
        return new NativeAccess() {
            @Override
            public <T> @NotNull T nativeHandle(final @NotNull Class<T> type) {
                if (type != Connection.class)
                    throw new IllegalArgumentException("JDBC adapter exposes java.sql.Connection");
                try {
                    return type.cast(connections.open());
                } catch (SQLException failure) {
                    throw data("Open native connection", failure);
                }
            }

            @Override
            public <T, R> @NotNull R withNative(
                    final @NotNull Class<T> type,
                    final @NotNull Function<T, R> callback
            ) {
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
        JdbcStore() { this.transaction = null; }
        JdbcStore(Connection transaction) { this.transaction = transaction; }
        private Lease lease() throws SQLException { return transaction == null ? new Lease(connections.open(), true) : new Lease(transaction, false); }
        @Override
        public @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record) {
            if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            List<String> fields = new ArrayList<>(record.values().keySet());
            String sql = "INSERT INTO " + profile.quote(entity) + " (" + fields.stream().map(profile::quote)
                    .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES (" +
                    String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
            try (Lease lease = lease(); PreparedStatement ps = lease.connection.prepareStatement(sql)) {
                bind(ps, record.values().values());
                ps.executeUpdate();
                return record;
            } catch (SQLException e) {
                throw data("Insert into " + entity, e);
            }
        }

        @Override public @NotNull eu.mikart.tava.data.MutationResult insertResult(@NotNull String entity, @NotNull EntityRecord record) {
            if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            List<String> fields = new ArrayList<>(record.values().keySet());
            String sql = "INSERT INTO " + profile.quote(entity) + " (" + fields.stream().map(profile::quote)
                    .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES (" +
                    String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
            try (Lease lease = lease(); PreparedStatement ps = lease.connection.prepareStatement(sql)) {
                bind(ps, record.values().values()); ps.executeUpdate();
                return eu.mikart.tava.data.MutationResult.changed(1, eu.mikart.tava.data.MutationResult.Outcome.INSERTED);
            } catch (SQLException failure) {
                if (isConflict(failure)) return new eu.mikart.tava.data.MutationResult(0, 0, 1,
                        eu.mikart.tava.data.MutationResult.Outcome.CONFLICT, List.of(conflictDetail(failure)));
                throw data("Insert into " + entity, failure);
            }
        }

        @Override public @NotNull eu.mikart.tava.data.MutationResult updateResult(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            long changed = update(entity, predicate, mutation);
            return new eu.mikart.tava.data.MutationResult(-1, changed, 0,
                    changed == 0 ? eu.mikart.tava.data.MutationResult.Outcome.UNCHANGED : eu.mikart.tava.data.MutationResult.Outcome.UPDATED);
        }

        @Override
        public @NotNull Page<EntityRecord> find(@NotNull String entity, @NotNull Query query) {
            List<Object> parameters = new ArrayList<>();
            String projection = query.projection().isEmpty() ? "*" : query.projection().stream()
                    .map(profile::quote).collect(java.util.stream.Collectors.joining(", "));
            StringBuilder sql = new StringBuilder("SELECT ").append(projection).append(" FROM ")
                    .append(profile.quote(entity));
            appendWhere(sql, parameters, query.predicate());
            if (!query.sorting().isEmpty()) {
                sql.append(" ORDER BY ");
                sql.append(query.sorting().stream().map(sort -> profile.quote(sort.field()) + " " + sort.direction())
                        .collect(java.util.stream.Collectors.joining(", ")));
            } else {
                sql.append(profile.defaultPaginationOrder());
            }
            int offset = decodeCursor(query.cursor());
            int requested = query.limit() == 0 ? 500 : query.limit();
            sql.append(profile.pagination(requested + 1, offset));
            try (Lease lease = lease(); PreparedStatement ps = lease.connection.prepareStatement(sql.toString())) {
                bind(ps, parameters);
                try (ResultSet rs = ps.executeQuery()) {
                    List<EntityRecord> records = new ArrayList<>();
                    ResultSetMetaData metadata = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++)
                            values.put(metadata.getColumnLabel(i), rs.getObject(i));
                        records.add(EntityRecord.of(values));
                    }
                    boolean more = records.size() > requested;
                    if (more) records.removeLast();
                    return new Page<>(records, more ? encodeCursor(offset + requested) : null);
                }
            } catch (SQLException e) {
                throw data("Query " + entity, e);
            }
        }

        @Override
        public long update(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
            if (mutation.values().isEmpty() && mutation.operations().isEmpty()) return 0;
            List<Object> parameters = new ArrayList<>(mutation.values().values());
            StringBuilder sql = new StringBuilder("UPDATE ").append(profile.quote(entity)).append(" SET ");
            List<String> assignments = new ArrayList<>(mutation.values().keySet().stream().map(field -> profile.quote(field) + " = ?").toList());
            mutation.operations().forEach((field, operation) -> {
                String quoted = profile.quote(field);
                if (operation.kind() == Mutation.Kind.INCREMENT) {
                    assignments.add(quoted + " = " + quoted + " + ?"); parameters.add(operation.value()); return;
                }
                JdbcProfile.MutationExpression expression = profile.collectionMutation(quoted, operation);
                if (expression == null) throw new TavaException.Capability(profile.name() + " does not support " + operation.kind() + " database-side mutations");
                assignments.add(quoted + " = " + expression.sql()); parameters.addAll(expression.parameters());
            });
            sql.append(String.join(", ", assignments));
            appendWhere(sql, parameters, predicate);
            try (Lease lease = lease(); PreparedStatement ps = lease.connection.prepareStatement(sql.toString())) {
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
            try (Lease lease = lease(); PreparedStatement ps = lease.connection.prepareStatement(sql.toString())) {
                bind(ps, parameters);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw data("Delete from " + entity, e);
            }
        }

        @Override public @NotNull eu.mikart.tava.data.MutationResult upsert(@NotNull String entity, @NotNull Predicate identity, @NotNull EntityRecord insert, @NotNull Mutation update) {
            if (transaction == null) {
                RuntimeException last = null;
                for (int attempt = 0; attempt < 8; attempt++) {
                    try { return transactions().transaction(store -> store.upsert(entity, identity, insert, update)); }
                    catch (RuntimeException failure) {
                        SQLException sql = sqlCause(failure);
                        if (sql == null || !profile.retryableWrite(sql)) throw failure;
                        last = failure;
                        java.util.concurrent.locks.LockSupport.parkNanos((attempt + 1L) * 2_000_000L);
                    }
                }
                throw last;
            }
            long changed = update(entity, identity, update);
            if (changed > 0) return eu.mikart.tava.data.MutationResult.changed(changed, eu.mikart.tava.data.MutationResult.Outcome.UPDATED);
            Savepoint savepoint = null;
            try {
                try { savepoint = transaction.setSavepoint(); }
                catch (SQLException failure) { throw data("Create upsert savepoint", failure); }
                insert(entity, insert);
                return eu.mikart.tava.data.MutationResult.changed(1, eu.mikart.tava.data.MutationResult.Outcome.INSERTED);
            } catch (TavaException.Conflict conflict) {
                try { if (savepoint != null) transaction.rollback(savepoint); }
                catch (SQLException failure) { conflict.addSuppressed(failure); throw conflict; }
                changed = update(entity, identity, update);
                if (changed > 0) return new eu.mikart.tava.data.MutationResult(changed, changed, 1,
                        eu.mikart.tava.data.MutationResult.Outcome.UPDATED,
                        conflict.getCause() instanceof SQLException sql ? List.of(conflictDetail(sql)) : List.of());
                if (!find(entity, Query.builder().where(identity).limit(1).build()).items().isEmpty())
                    return new eu.mikart.tava.data.MutationResult(1, 0, 1,
                            eu.mikart.tava.data.MutationResult.Outcome.UNCHANGED,
                            conflict.getCause() instanceof SQLException sql ? List.of(conflictDetail(sql)) : List.of());
                throw conflict;
            } finally {
                try { if (savepoint != null) transaction.releaseSavepoint(savepoint); }
                catch (SQLException ignored) { }
            }
        }
    }

    private static final class Lease implements AutoCloseable {
        private final Connection connection; private final boolean owned;
        private Lease(Connection connection, boolean owned) { this.connection = connection; this.owned = owned; }
        @Override public void close() throws SQLException { if (owned) connection.close(); }
    }

    private final class JdbcSchemas implements SchemaManager {
        @Override
        public @NotNull Schema inspect() {
            try (Connection c = connections.open()) {
                DatabaseMetaData meta = c.getMetaData();
                List<EntityDefinition> entities = new ArrayList<>();
                try (ResultSet tables = meta.getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
                    while (tables.next()) {
                        String table = tables.getString("TABLE_NAME");
                        if (table.startsWith("SYSTEM_")) continue;
                        List<FieldDefinition> fields = new ArrayList<>();
                        try (ResultSet columns = meta.getColumns(c.getCatalog(), null, table, "%")) {
                            while (columns.next()) {
                                fields.add(new FieldDefinition(columns.getString("COLUMN_NAME"),
                                        jdbcType(columns.getInt("DATA_TYPE"), columns.getInt("COLUMN_SIZE"),
                                                columns.getInt("DECIMAL_DIGITS")),
                                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                        false, false, GeneratedValue.NONE, Map.of()));
                            }
                        }
                        entities.add(new EntityDefinition(table, fields, List.of(), Map.of()));
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
        public @NotNull SchemaPlan plan(@NotNull Schema desired, @NotNull SchemaRenames renames) {
            validate(desired);
            Schema actual = inspect();
            List<SchemaChange> changes = new ArrayList<>();
            List<String> statements = new ArrayList<>();
            Map<String, EntityDefinition> existing = new HashMap<>();
            Set<String> actualNames = actual.entities().stream().map(value -> value.name().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            for (EntityDefinition current : actual.entities()) {
                String finalName = renames.entities().getOrDefault(current.name(), current.name());
                if (!finalName.equals(current.name())) {
                    if (actualNames.contains(finalName.toLowerCase(Locale.ROOT)))
                        throw new TavaException.Schema("Cannot rename entity " + current.name() + " to existing entity " + finalName);
                    String statement = profile.renameEntity(current.name(), finalName);
                    statements.add(statement);
                    changes.add(new SchemaChange("Rename entity " + current.name() + " to " + finalName, ChangeRisk.SAFE, statement));
                }
                Map<String, String> fieldRenames = renames.fields().getOrDefault(finalName, Map.of());
                Set<String> currentFieldNames = current.fields().stream().map(value -> value.name().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                List<FieldDefinition> fields = new ArrayList<>();
                for (FieldDefinition field : current.fields()) {
                    String finalField = fieldRenames.getOrDefault(field.name(), field.name());
                    if (!finalField.equals(field.name())) {
                        if (currentFieldNames.contains(finalField.toLowerCase(Locale.ROOT)))
                            throw new TavaException.Schema("Cannot rename field " + finalName + "." + field.name()
                                    + " to existing field " + finalField);
                        String statement = profile.renameField(finalName, field.name(), finalField);
                        statements.add(statement);
                        changes.add(new SchemaChange("Rename field " + finalName + "." + field.name() + " to " + finalField, ChangeRisk.SAFE, statement));
                    }
                    fields.add(new FieldDefinition(finalField, field.type(), field.nullable(), field.identity(),
                            field.unique(), field.generated(), field.settings()));
                }
                existing.put(finalName.toLowerCase(Locale.ROOT), new EntityDefinition(finalName, fields,
                        current.indexes(), current.settings()));
            }
            for (EntityDefinition entity : desired.entities()) {
                EntityDefinition current = existing.get(entity.name().toLowerCase(Locale.ROOT));
                if (current == null) {
                    statements.add(createEntity(entity));
                    // Keep the generated SQL attached so callers can display or audit the plan.
                    changes.add(new SchemaChange("Create entity " + entity.name(), ChangeRisk.SAFE, statements.getLast()));
                    for (IndexDefinition index : entity.indexes()) {
                        statements.add(createIndex(entity.name(), index));
                        changes.add(new SchemaChange("Create index " + index.name(), ChangeRisk.SAFE, statements.getLast()));
                    }
                } else {
                    Set<String> currentFields = new HashSet<>();
                    current.fields().forEach(field -> currentFields.add(field.name().toLowerCase(Locale.ROOT)));
                    for (FieldDefinition field : entity.fields()) {
                        if (!currentFields.contains(field.name().toLowerCase(Locale.ROOT))) {
                            String statement = "ALTER TABLE " + profile.quote(entity.name()) + " ADD " + fieldSql(field);
                            statements.add(statement);
                            ChangeRisk risk = field.nullable() || field.generated() != GeneratedValue.NONE
                                    ? ChangeRisk.SAFE : ChangeRisk.UNSUPPORTED;
                            changes.add(new SchemaChange("Add field " + entity.name() + "." + field.name(), risk, statement));
                        }
                    }
                }
            }
            return new SchemaPlan(changes, () -> execute(statements));
        }
    }

    private void validate(Schema schema) {
        for (EntityDefinition entity : schema.entities()) {
            for (FieldDefinition field : entity.fields()) {
                String nativeType = nativeType(field);
                if (nativeType != null && (!nativeType.matches("[A-Za-z0-9_(), .]+")
                        || nativeType.contains("--") || nativeType.contains("/*"))) {
                    throw new TavaException.Schema("Unsafe native type override for "
                            + entity.name() + "." + field.name());
                }
            }
        }
    }

    private String createEntity(EntityDefinition entity) {
        String fields = entity.fields().stream().map(this::fieldSql)
                .collect(java.util.stream.Collectors.joining(", "));
        List<String> identities = entity.fields().stream().filter(FieldDefinition::identity)
                .map(field -> profile.quote(field.name())).toList();
        if (!identities.isEmpty()) fields += ", PRIMARY KEY (" + String.join(", ", identities) + ")";
        return "CREATE TABLE " + (profile.supportsIfNotExists() ? "IF NOT EXISTS " : "")
                + profile.quote(entity.name()) + " (" + fields + ")";
    }

    private String fieldSql(FieldDefinition field) {
        String nativeType = nativeType(field);
        StringBuilder sql = new StringBuilder(profile.quote(field.name())).append(' ')
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
        if (value == null && profile.name().equals("mariadb")) value = field.settings().get("mysql.type");
        return Objects.toString(value, null);
    }

    private String createIndex(String entity, IndexDefinition index) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + profile.quote(index.name())
                + " ON " + profile.quote(entity) + " (" + index.fields().stream().map(profile::quote)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    private void execute(List<String> statements) {
        try (Connection c = connections.open(); Statement statement = c.createStatement()) {
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
                    default -> throw new TavaException.Capability(
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
                parameters.add(comparison.operator() == Predicate.Operator.CONTAINS
                        ? "%" + comparison.value() + "%" : comparison.value() + "%");
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
            return junction.predicates().stream().map(value -> "(" + predicateSql(value, parameters) + ")")
                    .collect(java.util.stream.Collectors.joining(junction.and() ? " AND " : " OR "));
        }
        return "1 = 1";
    }

    private static void bind(PreparedStatement statement, Collection<?> values) throws SQLException {
        int index = 1;
        for (Object value : values) {
            if (value instanceof java.time.Instant instant) statement.setTimestamp(index++, Timestamp.from(instant));
            else if (value instanceof Collection<?> || value instanceof Map<?, ?>) statement.setString(index++, ValueCodecs.toJson(value));
            else if (value instanceof Enum<?> enumeration) statement.setString(index++, enumeration.name());
            else statement.setObject(index++, value);
        }
    }

    private static FieldType jdbcType(int type, int size, int scale) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> FieldType.of(LogicalType.INT32);
            case Types.BIGINT -> FieldType.of(LogicalType.INT64);
            case Types.NUMERIC, Types.DECIMAL -> FieldType.decimal(Math.max(size, 1), Math.max(scale, 0));
            case Types.BOOLEAN, Types.BIT -> FieldType.of(LogicalType.BOOLEAN);
            case Types.DATE -> FieldType.of(LogicalType.LOCAL_DATE);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> FieldType.of(LogicalType.INSTANT);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> FieldType.of(LogicalType.BINARY);
            case Types.CHAR, Types.VARCHAR, Types.NVARCHAR -> FieldType.string(Math.max(size, 1));
            default -> FieldType.of(LogicalType.TEXT);
        };
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static TavaException data(String action, SQLException cause) {
        String message = action + " failed: " + cause.getMessage();
        String state = cause.getSQLState();
        if (isConflict(cause)) return new TavaException.Conflict(message, cause);
        if (state != null && state.startsWith("08")) return new TavaException.Connection(message, cause);
        return new TavaException.Data(message, cause);
    }

    private static boolean isConflict(SQLException cause) {
        return (cause.getSQLState() != null && cause.getSQLState().startsWith("23"))
                || cause.getErrorCode() == 19;
    }

    private static ConflictDetail conflictDetail(SQLException cause) {
        String state = cause.getSQLState();
        ConflictDetail.Kind kind = cause.getErrorCode() == 19 ? ConflictDetail.Kind.UNIQUE : state == null ? ConflictDetail.Kind.UNKNOWN : switch (state) {
            case "23505", "23000" -> ConflictDetail.Kind.UNIQUE;
            case "23503" -> ConflictDetail.Kind.FOREIGN_KEY;
            case "23514" -> ConflictDetail.Kind.CHECK;
            default -> ConflictDetail.Kind.UNKNOWN;
        };
        return new ConflictDetail(kind, null, state == null ? Integer.toString(cause.getErrorCode()) : state,
                cause.getMessage());
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql;
        return null;
    }
}
