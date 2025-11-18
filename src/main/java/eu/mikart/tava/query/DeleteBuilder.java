package eu.mikart.tava.query;

import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeleteBuilder {
    private final Database db;
    private final String table;
    private final Map<String, Object> whereEquals = new LinkedHashMap<>();

    public DeleteBuilder(Database db, String table) {
        this.db = db;
        this.table = table;
    }

    public DeleteBuilder eq(String column, Object value) { whereEquals.put(column, value); return this; }
    public DeleteBuilder uuid(String column, Object value) { return eq(column, value); }

    public int execute() throws SQLException {
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(d.quoteIdentifier(table));
        if (!whereEquals.isEmpty()) {
            sql.append(" WHERE ");
            int i = 0;
            for (String k : whereEquals.keySet()) {
                if (i++ > 0) sql.append(" AND ");
                sql.append(d.quoteIdentifier(k)).append(" = ").append(d.parameter(i));
            }
        }
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object v : whereEquals.values()) ps.setObject(idx++, v);
            return ps.executeUpdate();
        }
    }
}
