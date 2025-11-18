package eu.mikart.tava.query;

import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InsertBuilder {
    private final Database db;
    private final String table;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public InsertBuilder(Database db, String table) {
        this.db = db;
        this.table = table;
    }

    public InsertBuilder set(String column, Object value) { values.put(column, value); return this; }
    public InsertBuilder uuid(String column, Object value) { return set(column, value); }
    public InsertBuilder string(String column, Object value) { return set(column, value); }
    public InsertBuilder intValue(String column, Object value) { return set(column, value); }

    public int execute() throws SQLException {
        Dialect d = db.dialect();
        StringBuilder cols = new StringBuilder();
        StringBuilder params = new StringBuilder();
        int i = 0;
        for (String k : values.keySet()) {
            if (i++ > 0) { cols.append(", "); params.append(", "); }
            cols.append(d.quoteIdentifier(k));
            params.append(d.parameter(i));
        }
        String sql = "INSERT INTO " + d.quoteIdentifier(table) + "(" + cols + ") VALUES(" + params + ")";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            for (Object v : values.values()) ps.setObject(idx++, v);
            return ps.executeUpdate();
        }
    }
}
