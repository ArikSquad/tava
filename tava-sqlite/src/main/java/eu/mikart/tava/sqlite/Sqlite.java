package eu.mikart.tava.sqlite;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.schema.FieldDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Creates SQLite JDBC adapters and database backups. */
public final class Sqlite {

    private Sqlite() {}

    /**
     * Creates an adapter for a SQLite database file using default connection options.
     *
     * @param file database file path
     * @return SQLite JDBC adapter
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static @NotNull JdbcAdapter file(final @NotNull Path file) {
        return file(file, SqliteOptions.defaults());
    }

    /**
     * Creates an adapter for a SQLite database file using the supplied connection options.
     *
     * @param file database file path
     * @param options connection PRAGMA settings
     * @return SQLite JDBC adapter
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static @NotNull JdbcAdapter file(final @NotNull Path file, final @NotNull SqliteOptions options) {
        final String url = "jdbc:sqlite:" + file.toAbsolutePath();
        return new JdbcAdapter(new Profile(), () -> configured(DriverManager.getConnection(url), options));
    }

    /**
     * Opens a shared in-memory SQLite database using default memory-journal options.
     *
     * @param name name used to identify the shared in-memory database
     * @return SQLite JDBC adapter
     * @throws IllegalStateException if the database cannot be opened
     */
    public static @NotNull JdbcAdapter memory(final @NotNull String name) {
        return memory(
                name,
                SqliteOptions.builder()
                        .journalMode(SqliteOptions.JournalMode.MEMORY)
                        .build());
    }

    /**
     * Opens a shared in-memory SQLite database using the supplied connection options.
     *
     * @param name name used to identify the shared in-memory database
     * @param options connection PRAGMA settings
     * @return SQLite JDBC adapter
     * @throws IllegalStateException if the database cannot be opened
     * @throws NullPointerException if {@code options} is {@code null}
     */
    public static @NotNull JdbcAdapter memory(final @NotNull String name, final @NotNull SqliteOptions options) {
        final String url = "jdbc:sqlite:file:" + name + "?mode=memory&cache=shared";
        try {
            final var keepAlive = configured(DriverManager.getConnection(url), options);
            return new JdbcAdapter(new Profile(), () -> configured(DriverManager.getConnection(url), options), () -> {
                try {
                    keepAlive.close();
                } catch (SQLException failure) {
                    throw new IllegalStateException("Close SQLite memory database", failure);
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("Open SQLite memory database", failure);
        }
    }

    /**
     * Creates a consistent flat-file snapshot using SQLite's {@code VACUUM INTO} operation.
     *
     * @param database existing SQLite database file
     * @param target path for the new backup file
     * @throws IllegalArgumentException if the source file does not exist, the target already exists, or both paths are
     *     equal
     * @throws NullPointerException if {@code database} or {@code target} is {@code null}
     * @throws TavaException.Data if the backup fails
     */
    public static void backup(final @NotNull Path database, final @NotNull Path target) {
        backup(database, target, SqliteOptions.defaults());
    }

    /**
     * Creates a consistent flat-file snapshot using SQLite's {@code VACUUM INTO} operation.
     *
     * @param database existing SQLite database file
     * @param target path for the new backup file
     * @param options connection PRAGMA settings
     * @throws IllegalArgumentException if the source file does not exist, the target already exists, or both paths are
     *     equal
     * @throws NullPointerException if an argument is {@code null}
     * @throws TavaException.Data if the backup fails
     */
    public static void backup(
            final @NotNull Path database, final @NotNull Path target, final @NotNull SqliteOptions options) {
        Path source = database.toAbsolutePath();
        Path destination = target.toAbsolutePath();
        if (source.equals(destination))
            throw new IllegalArgumentException("Backup target must differ from the database");
        if (!Files.isRegularFile(source))
            throw new IllegalArgumentException("SQLite database does not exist: " + source);
        if (Files.exists(destination))
            throw new IllegalArgumentException("Backup target already exists: " + destination);
        try {
            Path parent = destination.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection connection = configured(DriverManager.getConnection("jdbc:sqlite:" + source), options);
                    var statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, destination.toString());
                statement.execute();
            }
        } catch (SQLException | IOException failure) {
            throw new TavaException.Data("Back up SQLite database to " + destination, failure);
        }
    }

    private static Connection configured(Connection connection, SqliteOptions options) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = " + (options.foreignKeys() ? "ON" : "OFF"));
            statement.execute("PRAGMA synchronous = " + options.synchronous().name());
            statement.execute("PRAGMA busy_timeout = " + options.busyTimeout().toMillis());
            statement.execute("PRAGMA journal_mode = " + options.journalMode().name());
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private static final class Profile extends StandardJdbcProfile {
        private Profile() {
            super("sqlite", '"', true);
        }

        @Override
        public @NotNull String type(final @NotNull FieldDefinition field) {
            return switch (field.type().logicalType()) {
                case INT32, INT64, BOOLEAN -> "INTEGER";
                case FLOAT64 -> "REAL";
                case BINARY -> "BLOB";
                case DECIMAL -> "NUMERIC";
                default -> "TEXT";
            };
        }

        @Override
        public MutationExpression collectionMutation(String field, Mutation.Operation operation) {
            String encoded = ValueCodecs.toJson(operation.value());
            return switch (operation.kind()) {
                case APPEND_IF_ABSENT, APPEND ->
                    new MutationExpression(
                            "CASE WHEN EXISTS (SELECT 1 FROM json_each(COALESCE(" + field
                                    + ", '[]')) WHERE value IS json_extract(?, '$')) " + "THEN " + field
                                    + " ELSE json_insert(COALESCE(" + field
                                    + ", '[]'), '$[#]', json_extract(?, '$')) END",
                            List.of(encoded, encoded));
                case REMOVE ->
                    new MutationExpression(
                            "(SELECT COALESCE(json_group_array(value), '[]') FROM json_each(COALESCE(" + field
                                    + ", '[]')) WHERE value IS NOT json_extract(?, '$'))",
                            List.of(encoded));
                default -> null;
            };
        }

        @Override
        public boolean retryableWrite(SQLException failure) {
            return failure.getErrorCode() == 5 || failure.getErrorCode() == 6;
        }
    }
}
