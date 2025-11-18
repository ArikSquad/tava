package eu.mikart.tava;

import eu.mikart.tava.dialect.MySqlDialect;
import eu.mikart.tava.dialect.PostgresDialect;
import eu.mikart.tava.dialect.SqliteDialect;

import java.nio.file.Path;

public final class Dialects {
	private Dialects() {
	}

	public static Dialect postgres(String host, int port, String database, String user, String password) {
		return new PostgresDialect(host, port, database, user, password);
	}

	public static Dialect mysql(String host, int port, String database, String user, String password) {
		return new MySqlDialect(host, port, database, user, password);
	}

	public static Dialect sqlite(Path file) {
		return new SqliteDialect(file);
	}
}
