package eu.mikart.tava.sqlite;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Sqlite {

    private Sqlite() {
    }

    public static @NotNull JdbcAdapter file(final @NotNull Path file) {
        return JdbcAdapter.driverManager(new Profile(), "jdbc:sqlite:" + file.toAbsolutePath(), null, null);
    }

    public static @NotNull JdbcAdapter memory(final @NotNull String name) {
        final String url = "jdbc:sqlite:file:" + name + "?mode=memory&cache=shared";
        try {
            final var keepAlive = DriverManager.getConnection(url);
            return new JdbcAdapter(new Profile(), () -> DriverManager.getConnection(url), () -> {
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

    private static final class Profile extends StandardJdbcProfile {
        private Profile() {
            super("sqlite", '"', true);
        }

        @Override
        public @NotNull String type(final @NotNull FieldDefinition field) {
            return switch (field.type().logicalType()) {
                case INT32, INT64, BOOLEAN -> "INTEGER";
                case BINARY -> "BLOB";
                case DECIMAL -> "NUMERIC";
                default -> "TEXT";
            };
        }
    }
}
