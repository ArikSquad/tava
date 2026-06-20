package eu.mikart.tava.mysql;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;

public final class MySql {
    private MySql() {
    }

    public static JdbcAdapter connect(String url, String user, String password) {
        return JdbcAdapter.driverManager(new Profile("mysql"), url, user, password);
    }

    public static JdbcAdapter mariaDb(String url, String user, String password) {
        return JdbcAdapter.driverManager(new Profile("mariadb"), url, user, password);
    }

    public static JdbcAdapter connect(String host, int port, String database, String user, String password) {
        return connect("jdbc:mysql://" + host + ":" + port + "/" + database, user, password);
    }

    private static final class Profile extends StandardJdbcProfile {
        private Profile(String name) {
            super(name, '`', true);
        }

        @Override
        public String type(FieldDefinition field) {
            return switch (field.type().logicalType()) {
                case JSON -> "JSON";
                case BINARY -> "LONGBLOB";
                default -> super.type(field);
            };
        }

        @Override
        public String identityClause(FieldDefinition field) {
            return field.generated() == eu.mikart.tava.schema.GeneratedValue.IDENTITY ? "AUTO_INCREMENT" : "";
        }
    }
}
