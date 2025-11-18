package eu.mikart.tava;

import eu.mikart.tava.migration.Migration;
import eu.mikart.tava.migration.MigrationManager;
import eu.mikart.tava.query.DeleteBuilder;
import eu.mikart.tava.query.InsertBuilder;
import eu.mikart.tava.query.SelectBuilder;
import eu.mikart.tava.query.UpdateBuilder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Database {
	private final Dialect dialect;
	private final MigrationManager migrationManager;

	private Database(Dialect dialect, MigrationManager migrationManager) {
		this.dialect = dialect;
		this.migrationManager = migrationManager;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Dialect dialect() {
		return dialect;
	}

	public Connection connection() throws SQLException {
		return dialect.createConnection();
	}

	public void initialize() throws SQLException {
		migrationManager.applyPending(this);
	}

	public void migration(Migration migration) {
		migrationManager.register(migration);
	}

	public void log(String s) {
		// later this will include so user can define their logger
	}

	public void createTable(String name, Consumer<TableBuilder> consumer) throws SQLException {
		TableBuilder tb = new TableBuilder(dialect, name);
		consumer.accept(tb);
		String sql = dialect.buildCreateTableSql(name, new ArrayList<>(tb.columns()));
		try (Connection c = connection()) {
			c.createStatement().execute(sql);
		}
	}

	public <T> List<T> select(String table, Class<T> recordType, Consumer<SelectBuilder> consumer) throws SQLException {
		SelectBuilder sb = new SelectBuilder(this, table, recordType);
		consumer.accept(sb);
		return sb.execute();
	}

	public int insert(String table, Consumer<InsertBuilder> consumer) throws SQLException {
		InsertBuilder ib = new InsertBuilder(this, table);
		consumer.accept(ib);
		return ib.execute();
	}

	public int update(String table, Consumer<UpdateBuilder> consumer) throws SQLException {
		UpdateBuilder ub = new UpdateBuilder(this, table);
		consumer.accept(ub);
		return ub.execute();
	}

	public int delete(String table, Consumer<DeleteBuilder> consumer) throws SQLException {
		DeleteBuilder db = new DeleteBuilder(this, table);
		consumer.accept(db);
		return db.execute();
	}

	public static final class Builder {
		private Dialect dialect;
		private MigrationManager migrationManager = new MigrationManager();

		public Builder dialect(Dialect dialect) {
			this.dialect = dialect;
			return this;
		}

		public Builder migrations(Consumer<MigrationManager> consumer) {
			consumer.accept(migrationManager);
			return this;
		}

		public Builder migration(Migration migration) {
			migrationManager.register(migration);
			return this;
		}

		public Database build() {
			return new Database(dialect, migrationManager);
		}
	}
}
