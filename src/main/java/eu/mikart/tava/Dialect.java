package eu.mikart.tava;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface Dialect {
	String name();

	String typeUuid();

	String typeString(int length);

	String typeText();

	String typeInt();

	String typeBigInt();

	String typeBoolean();

	String typeTimestamp();

	String typeJson();

	String autoIncrement();

	boolean supportsIfNotExists();

	boolean supportsReturning();

	String parameter(int index);

	String quoteIdentifier(String id);

	String primaryKeyClause(List<String> columnNames);

	String buildCreateTableSql(String tableName, List<ColumnDefinition> columns);

	String buildDropTableSql(String tableName);

	String buildAlterTableAddColumn(String tableName, ColumnDefinition column);

	Connection createConnection() throws SQLException;
}
