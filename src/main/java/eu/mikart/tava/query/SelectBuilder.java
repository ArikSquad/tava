package eu.mikart.tava.query;

import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SelectBuilder {
    private final Database db;
    private final String table;
    private final Class<?> recordType;
    private final Map<String, Object> equals = new LinkedHashMap<>();
    private final List<String> orderDesc = new ArrayList<>();
    private final List<String> orderAsc = new ArrayList<>();
    private Integer limit;

    public SelectBuilder(Database db, String table, Class<?> recordType) {
        this.db = db;
        this.table = table;
        this.recordType = recordType;
    }

    public SelectBuilder eq(String column, Object value) { equals.put(column, value); return this; }
    public SelectBuilder uuid(String column, Object value) { return eq(column, value); }
    public SelectBuilder string(String column, Object value) { return eq(column, value); }
    public SelectBuilder orderByDesc(String column) { orderDesc.add(column); return this; }
    public SelectBuilder orderByAsc(String column) { orderAsc.add(column); return this; }
    public SelectBuilder limit(int limit) { this.limit = limit; return this; }

    public <T> List<T> execute() throws SQLException {
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(d.quoteIdentifier(table));
        if (!equals.isEmpty()) {
            sql.append(" WHERE ");
            int i = 0;
            for (String k : equals.keySet()) {
                if (i++ > 0) sql.append(" AND ");
                sql.append(d.quoteIdentifier(k)).append(" = ").append(d.parameter(i));
            }
        }
        if (!orderDesc.isEmpty() || !orderAsc.isEmpty()) {
            sql.append(" ORDER BY ");
            List<String> parts = new ArrayList<>();
            for (String c : orderAsc) parts.add(d.quoteIdentifier(c) + " ASC");
            for (String c : orderDesc) parts.add(d.quoteIdentifier(c) + " DESC");
            sql.append(String.join(", ", parts));
        }
        if (limit != null) sql.append(" LIMIT ").append(limit);
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object v : equals.values()) ps.setObject(idx++, v);
            try (ResultSet rs = ps.executeQuery()) {
                return mapResultSet(rs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> mapResultSet(ResultSet rs) throws SQLException {
        List<T> out = new ArrayList<>();
        RecordComponent[] components = recordType.getRecordComponents();
        while (rs.next()) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                String name = components[i].getName();
                Object raw = rs.getObject(name);
                Class<?> targetType = components[i].getType();
                values[i] = convert(raw, targetType, name);
            }
            try {
                Constructor<?> ctor = recordType.getDeclaredConstructor(
                    java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new)
                );
                ctor.setAccessible(true);
                out.add((T) ctor.newInstance(values));
            } catch (ReflectiveOperationException e) {
                throw new SQLException("Failed to instantiate record " + recordType.getName(), e);
            }
        }
        return out;
    }

	private Object convert(Object raw, Class<?> targetType, String column) throws SQLException {
		if (raw == null) return null;
		if (targetType.isInstance(raw)) return raw;
		try {
			if (targetType == Instant.class) {
				return switch (raw) {
					case java.sql.Timestamp timestamp -> timestamp.toInstant();
					case OffsetDateTime value -> value.toInstant();
					case java.time.LocalDateTime value -> value.toInstant(ZoneOffset.UTC);
					case Number value -> Instant.ofEpochMilli(value.longValue());
					default -> {
						String value = raw.toString();
						try {
							yield Instant.parse(value);
						} catch (java.time.format.DateTimeParseException ignored) {
							yield java.time.LocalDateTime.parse(
									value.replace(' ', 'T'),
									java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
							).toInstant(ZoneOffset.UTC);
						}
					}
				};
			}
			if (targetType == UUID.class) return UUID.fromString(raw.toString());
			if (targetType == String.class) return raw.toString();
			if ((targetType == int.class || targetType == Integer.class) && raw instanceof Number n) return n.intValue();
			if ((targetType == long.class || targetType == Long.class) && raw instanceof Number n) return n.longValue();
			if ((targetType == boolean.class || targetType == Boolean.class) && raw instanceof Number n) return n.intValue() != 0;
			return raw;
		} catch (RuntimeException exception) {
			throw new SQLException("Cannot map column '" + column + "' to " + targetType.getSimpleName(), exception);
		}
	}
}
