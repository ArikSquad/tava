package eu.mikart.tava.schema;

import eu.mikart.tava.ColumnDefinition;
import eu.mikart.tava.Dialect;
import eu.mikart.tava.TableBuilder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SchemaBuilder {
	private final Dialect dialect;
	private final Connection connection;
	private final List<String> sqlStatements = new ArrayList<>();

	public SchemaBuilder(Dialect dialect, Connection connection) {
		this.dialect = dialect;
		this.connection = connection;
	}

	public void createTable(String name, Consumer<TableBuilder> consumer) {
		TableBuilder tb = new TableBuilder(dialect, name);
		consumer.accept(tb);
		sqlStatements.add(dialect.buildCreateTableSql(name, new ArrayList<>(tb.columns())));
	}

	public void dropTable(String name) {
		sqlStatements.add(dialect.buildDropTableSql(name));
	}

	public void addColumn(String table, Consumer<TableBuilder.ColumnBuilder> consumer, String columnName, String type) {
		TableBuilder temp = new TableBuilder(dialect, table);
		TableBuilder.ColumnBuilder cb = new TableBuilder.ColumnBuilder(temp, columnName, type);
		consumer.accept(cb);
		cb.end();
		ColumnDefinition def = temp.columns().iterator().next();
		sqlStatements.add(dialect.buildAlterTableAddColumn(table, def));
	}

	public void execute() throws SQLException {
		try (Statement st = connection.createStatement()) {
			for (String sql : sqlStatements) st.execute(sql);
		}
	}
}
