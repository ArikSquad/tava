package eu.mikart.tava.dialect;

import java.nio.file.Path;

public final class SqliteDialect extends BaseDialect {
	public SqliteDialect(Path file) {
		super("jdbc:sqlite:" + file.toAbsolutePath(), null, null);
	}

	@Override
	public String name() {
		return "sqlite";
	}

	@Override
	public String typeUuid() {
		return "TEXT";
	}

	@Override
	public String typeString(int length) {
		return "TEXT";
	}

	@Override
	public String typeText() {
		return "TEXT";
	}

	@Override
	public String typeInt() {
		return "INTEGER";
	}

	@Override
	public String typeBigInt() {
		return "INTEGER";
	}

	@Override
	public String typeBoolean() {
		return "INTEGER";
	}

	@Override
	public String typeTimestamp() {
		return "TEXT";
	}

	@Override
	public String typeJson() {
		return "TEXT";
	}

	@Override
	public String autoIncrement() {
		return "AUTOINCREMENT";
	}

	@Override
	public boolean supportsIfNotExists() {
		return true;
	}

	@Override
	public boolean supportsReturning() {
		return false;
	}
}
