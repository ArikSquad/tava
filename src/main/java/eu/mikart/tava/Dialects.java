package eu.mikart.tava;

import eu.mikart.tava.dialect.H2Dialect;
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

	public static Dialect sqliteMemory(String name) {
		return new SqliteDialect("jdbc:sqlite:file:" + name + "?mode=memory&cache=shared");
	}

	public static Dialect h2(String name) {
		return new H2Dialect("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
	}

	public static Dialect jdbc(String name, String url, String user, String password) {
		return switch (name.toLowerCase()) {
			case "postgres", "postgresql" -> new PostgresDialect(url, user, password);
			case "mysql" -> new MySqlDialect(url, user, password);
			case "h2" -> new H2Dialect(url, user, password);
			case "sqlite" -> new SqliteDialect(url);
			default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + name);
		};
	}
}
