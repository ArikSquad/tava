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
	public String quoteIdentifier(String id) {
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
		StringJoiner pk = new StringJoiner(", ");
		for (ColumnDefinition c : columns) {
			StringBuilder b = new StringBuilder();
			b.append(quoteIdentifier(c.name())).append(' ').append(c.type());
			if (c.autoIncrement()) b.append(' ').append(autoIncrement());
			if (!c.nullable()) b.append(" NOT NULL");
			if (c.unique()) b.append(" UNIQUE");
			for (String extra : c.extras()) b.append(' ').append(extra);
			defs.add(b.toString());
			if (c.primary()) pk.add(c.name());
		}
		if (pk.length() > 0) defs.add(primaryKeyClause(List.of(pk.toString().split(", "))));
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
