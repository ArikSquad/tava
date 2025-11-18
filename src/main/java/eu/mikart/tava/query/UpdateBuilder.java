package eu.mikart.tava.query;

import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UpdateBuilder {
	private final Database db;
	private final String table;
	private final Map<String, Object> setValues = new LinkedHashMap<>();
	private final Map<String, Object> whereEquals = new LinkedHashMap<>();

	public UpdateBuilder(Database db, String table) {
		this.db = db;
		this.table = table;
	}

	public UpdateBuilder set(String column, Object value) {
		setValues.put(column, value);
		return this;
	}

	public UpdateBuilder eq(String column, Object value) {
		whereEquals.put(column, value);
		return this;
	}

	public int execute() throws SQLException {
		Dialect d = db.dialect();
		StringBuilder sql = new StringBuilder("UPDATE ").append(d.quoteIdentifier(table)).append(" SET ");
		int i = 0;
		for (String k : setValues.keySet()) {
			if (i++ > 0) sql.append(", ");
			sql.append(d.quoteIdentifier(k)).append(" = ").append(d.parameter(i));
		}
		if (!whereEquals.isEmpty()) {
			sql.append(" WHERE ");
			int j = 0;
			for (String k : whereEquals.keySet()) {
				if (j++ > 0) sql.append(" AND ");
				sql.append(d.quoteIdentifier(k)).append(" = ").append(d.parameter(i + j));
			}
		}
		try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
			int idx = 1;
			for (Object v : setValues.values()) ps.setObject(idx++, v);
			for (Object v : whereEquals.values()) ps.setObject(idx++, v);
			return ps.executeUpdate();
		}
	}
}
