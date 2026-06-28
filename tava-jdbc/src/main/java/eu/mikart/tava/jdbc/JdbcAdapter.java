package eu.mikart.tava.jdbc;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
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
        @Override
        public @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record) {
            if (record.values().isEmpty()) throw new IllegalArgumentException("record has no values");
            List<String> fields = new ArrayList<>(record.values().keySet());
            String sql = "INSERT INTO " + profile.quote(entity) + " (" + fields.stream().map(profile::quote)
                    .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES (" +
                    String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
            try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, record.values().values());
                ps.executeUpdate();
                return record;
            } catch (SQLException e) {
                throw data("Insert into " + entity, e);
            }
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
            try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
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
            if (mutation.values().isEmpty()) return 0;
            List<Object> parameters = new ArrayList<>(mutation.values().values());
            StringBuilder sql = new StringBuilder("UPDATE ").append(profile.quote(entity)).append(" SET ");
            sql.append(mutation.values().keySet().stream().map(field -> profile.quote(field) + " = ?")
                    .collect(java.util.stream.Collectors.joining(", ")));
            appendWhere(sql, parameters, predicate);
            try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
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
            try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
                bind(ps, parameters);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw data("Delete from " + entity, e);
            }
        }
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
            validate(desired);
            Schema actual = inspect();
            Map<String, EntityDefinition> existing = new HashMap<>();
            actual.entities().forEach(entity -> existing.put(entity.name().toLowerCase(Locale.ROOT), entity));
            List<SchemaChange> changes = new ArrayList<>();
            List<String> statements = new ArrayList<>();
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

    private static TavaException.Data data(String action, SQLException cause) {
        return new TavaException.Data(action + " failed: " + cause.getMessage(), cause);
    }
}
