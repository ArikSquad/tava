package eu.mikart.tava.mysql;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;
import org.jetbrains.annotations.NotNull;

public final class MySql {
    private MySql() {}

    public static @NotNull JdbcAdapter connect(
            final @NotNull String url,
            final @NotNull String user,
            final @NotNull String password
    ) {
        return JdbcAdapter.driverManager(new Profile("mysql"), url, user, password);
    }

    public static @NotNull JdbcAdapter mariaDb(
            final @NotNull String url,
            final @NotNull String user,
            final @NotNull String password
    ) {
        return JdbcAdapter.driverManager(new Profile("mariadb"), url, user, password);
    }

    public static @NotNull JdbcAdapter connect(
            final @NotNull String host,
            final int port,
            final @NotNull String database,
            final @NotNull String user,
            final @NotNull String password
    ) {
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
            return field.generated() == eu.mikart.tava.schema.GeneratedValue.IDENTITY ? "AUTO_INCREMENT" : "";
        }
    }
}
