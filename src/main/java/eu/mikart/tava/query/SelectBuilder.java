package eu.mikart.tava.query;

import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                if (raw != null && targetType == java.time.Instant.class) {
                    if (raw instanceof java.sql.Timestamp ts) {
                        values[i] = ts.toInstant();
                    } else if (raw instanceof String s) {
                        java.time.Instant inst = null;
                        try {
                            inst = java.time.Instant.parse(s);
                        } catch (Exception ignored) {
                            try {
                                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                inst = ldt.toInstant(java.time.ZoneOffset.UTC);
                            } catch (Exception ignored2) {
                                try { inst = java.time.Instant.ofEpochMilli(Long.parseLong(s)); } catch (NumberFormatException ignored3) { inst = java.time.Instant.now(); }
                            }
                        }
                        values[i] = inst;
                    } else if (raw instanceof Long l) {
                        values[i] = java.time.Instant.ofEpochMilli(l);
                    } else {
                        values[i] = java.time.Instant.now();
                    }
                } else {
                    values[i] = raw;
                }
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
}
