package eu.mikart.tava.migration;

import eu.mikart.tava.ColumnDefinition;
import eu.mikart.tava.Database;
import eu.mikart.tava.Dialect;
import eu.mikart.tava.schema.SchemaBuilder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class MigrationManager {
	private final List<Migration> migrations = new ArrayList<>();

	public void register(Migration migration) {
		migrations.add(migration);
	}

	public void applyPending(Database db) throws SQLException {
		Collections.sort(migrations);
		if (migrations.isEmpty()) return;
		Dialect dialect = db.dialect();
		try (Connection c = db.connection(); Statement st = c.createStatement()) {
			ensureMetadataTable(dialect, st);
			int currentVersion = readCurrentVersion(st);
			for (Migration m : migrations) {
				if (m.version() > currentVersion) {
					db.log("Applying migration " + m.version() + " - " + m.description());
					c.setAutoCommit(false);
					SchemaBuilder schema = new SchemaBuilder(dialect, c);
					m.up(schema);
					schema.execute();
					st.execute("INSERT INTO __metadata(version, description) VALUES(" + m.version() + ", '" + escape(m.description()) + "')");
					c.commit();
					c.setAutoCommit(true);
				}
			}
		}
	}

	private void ensureMetadataTable(Dialect dialect, Statement st) throws SQLException {
		try {
			st.executeQuery("SELECT version FROM __metadata LIMIT 1").close();
		} catch (SQLException e) {
			List<ColumnDefinition> cols = List.of(
					new ColumnDefinition("version", dialect.typeInt(), false, true, false, false, null),
					new ColumnDefinition("description", dialect.typeString(255), false, false, false, false, null),
					new ColumnDefinition("applied_at", dialect.typeTimestamp(), false, false, false, false, Set.of("DEFAULT CURRENT_TIMESTAMP"))
			);
			st.execute(dialect.buildCreateTableSql("__metadata", cols));
		}
	}

	private int readCurrentVersion(Statement st) {
		try (ResultSet rs = st.executeQuery("SELECT version FROM __metadata ORDER BY version DESC LIMIT 1")) {
			if (rs.next()) return rs.getInt(1);
		} catch (SQLException ignored) {
		}
		return 0;
	}

	private String escape(String s) {
		return s.replace("'", "''");
	}
}
