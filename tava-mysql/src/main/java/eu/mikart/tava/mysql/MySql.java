package eu.mikart.tava.mysql;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;
import eu.mikart.tava.schema.GeneratedValue;
import java.util.Objects;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Factory methods for MySQL and MariaDB adapters. */
public final class MySql {
    private MySql() {}

    /**
     * Creates a MySQL adapter using application-managed JDBC connections.
     *
     * @param dataSource connection pool or other JDBC data source
     * @return adapter backed by the data source
     * @since 1.3.0
     */
    public static @NotNull JdbcAdapter connect(final @NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbcAdapter(new Profile("mysql"), dataSource::getConnection);
    }

    /**
     * Creates a MySQL adapter using a JDBC connection URL.
     *
     * @param url JDBC connection URL
     * @param user database user
     * @param password database password
     * @return adapter that owns the opened connections
     */
    public static @NotNull JdbcAdapter connect(
            final @NotNull String url, final @NotNull String user, final @NotNull String password) {
        return JdbcAdapter.driverManager(new Profile("mysql"), url, user, password);
    }

    /**
     * Creates a MariaDB adapter using application-managed JDBC connections.
     *
     * @param dataSource connection pool or other JDBC data source
     * @return adapter backed by the data source
     * @since 1.3.0
     */
    public static @NotNull JdbcAdapter mariaDb(final @NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbcAdapter(new Profile("mariadb"), dataSource::getConnection);
    }

    /**
     * Creates a MariaDB adapter using a JDBC connection URL.
     *
     * @param url JDBC connection URL
     * @param user database user
     * @param password database password
     * @return adapter that owns the opened connections
     */
    public static @NotNull JdbcAdapter mariaDb(
            final @NotNull String url, final @NotNull String user, final @NotNull String password) {
        return JdbcAdapter.driverManager(new Profile("mariadb"), url, user, password);
    }

    /**
     * Creates a MySQL adapter from connection parameters.
     *
     * @param host database host
     * @param port database port
     * @param database database name
     * @param user database user
     * @param password database password
     * @return adapter that owns the opened connections
     */
    public static @NotNull JdbcAdapter connect(
            final @NotNull String host,
            final int port,
            final @NotNull String database,
            final @NotNull String user,
            final @NotNull String password) {
        return connect("jdbc:mysql://" + host + ":" + port + "/" + database, user, password);
    }

    private static final class Profile extends StandardJdbcProfile {
        private Profile(final @NotNull String name) {
            super(name, '`', true);
        }

        @Override
        public @NotNull String type(final @NotNull FieldDefinition field) {
            return switch (field.type().logicalType()) {
                case JSON -> "JSON";
                case BINARY -> "LONGBLOB";
                default -> super.type(field);
            };
        }

        @Override
        public @NotNull String identityClause(final @NotNull FieldDefinition field) {
            return field.generated() == GeneratedValue.IDENTITY ? "AUTO_INCREMENT" : "";
        }
    }
}
