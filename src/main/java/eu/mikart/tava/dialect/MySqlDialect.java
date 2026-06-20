package eu.mikart.tava.dialect;

public final class MySqlDialect extends BaseDialect {
	public MySqlDialect(String host, int port, String database, String user, String password) {
		super("jdbc:mysql://" + host + ":" + port + '/' + database + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
	}

	public MySqlDialect(String url, String user, String password) {
		super(url, user, password);
	}

	@Override
	public String quoteIdentifier(String id) {
		if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid SQL identifier: " + id);
		}
		return '`' + id + '`';
	}

	@Override
	public String typeBinary() {
		return "LONGBLOB";
	}

	@Override
	public String name() {
		return "mysql";
	}

	@Override
	public String typeUuid() {
		return "CHAR(36)";
	}

	@Override
	public String typeString(int length) {
		return length > 0 ? "VARCHAR(" + length + ")" : "TEXT";
	}

	@Override
	public String typeText() {
		return "TEXT";
	}

	@Override
	public String typeInt() {
		return "INT";
	}

	@Override
	public String typeBigInt() {
		return "BIGINT";
	}

	@Override
	public String typeBoolean() {
		return "BOOLEAN";
	}

	@Override
	public String typeTimestamp() {
		return "TIMESTAMP";
	}

	@Override
	public String typeJson() {
		return "JSON";
	}

	@Override
	public String autoIncrement() {
		return "AUTO_INCREMENT";
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
