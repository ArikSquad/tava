package eu.mikart.tava.dialect;

import eu.mikart.tava.ColumnDefinition;
import eu.mikart.tava.Dialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

public abstract class BaseDialect implements Dialect {
	protected final String url;
	protected final String user;
	protected final String password;

	protected BaseDialect(String url, String user, String password) {
		this.url = url;
		this.user = user;
		this.password = password;
	}

	@Override
	public String typeBinary() {
		return "BLOB";
	}

	@Override
	public String quoteIdentifier(String id) {
		if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid SQL identifier: " + id);
		}
		return '"' + id + '"';
	}

	@Override
	public String parameter(int index) {
		return "?";
	}

	@Override
	public String primaryKeyClause(List<String> columnNames) {
		if (columnNames.isEmpty()) return "";
		StringJoiner sj = new StringJoiner(", ");
		columnNames.forEach(c -> sj.add(quoteIdentifier(c)));
		return "PRIMARY KEY (" + sj + ")";
	}

	@Override
	public String buildCreateTableSql(String tableName, List<ColumnDefinition> columns) {
		StringJoiner defs = new StringJoiner(", ");
		List<String> primaryKeys = new java.util.ArrayList<>();
		for (ColumnDefinition c : columns) {
			StringBuilder b = new StringBuilder();
			b.append(quoteIdentifier(c.name())).append(' ').append(c.type());
			if (c.autoIncrement() && !autoIncrement().isBlank()) b.append(' ').append(autoIncrement());
			if (!c.nullable()) b.append(" NOT NULL");
			if (c.unique()) b.append(" UNIQUE");
			for (String extra : c.extras()) b.append(' ').append(extra);
			defs.add(b.toString());
			if (c.primary()) primaryKeys.add(c.name());
		}
		if (!primaryKeys.isEmpty()) defs.add(primaryKeyClause(primaryKeys));
		return "CREATE TABLE " + (supportsIfNotExists() ? "IF NOT EXISTS " : "") + quoteIdentifier(tableName) + " (" + defs + ")";
	}

	@Override
	public String buildDropTableSql(String tableName) {
		return "DROP TABLE " + quoteIdentifier(tableName);
	}

	@Override
	public String buildAlterTableAddColumn(String tableName, ColumnDefinition column) {
		StringBuilder b = new StringBuilder();
		b.append("ALTER TABLE ").append(quoteIdentifier(tableName)).append(" ADD COLUMN ");
		b.append(quoteIdentifier(column.name())).append(' ').append(column.type());
		if (!column.nullable()) b.append(" NOT NULL");
		if (column.unique()) b.append(" UNIQUE");
		return b.toString();
	}

	@Override
	public Connection createConnection() throws SQLException {
		if (user == null) return DriverManager.getConnection(url);
		return DriverManager.getConnection(url, user, password);
	}
}
