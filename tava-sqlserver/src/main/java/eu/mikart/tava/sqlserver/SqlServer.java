package eu.mikart.tava.sqlserver;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;

public final class SqlServer {
    private SqlServer() {
    }

    public static JdbcAdapter connect(String url, String user, String password) {
        return JdbcAdapter.driverManager(new Profile(), url, user, password);
    }

    private static final class Profile extends StandardJdbcProfile {
        private Profile() {
            super("sqlserver", '"', false);
        }

        @Override
        public String quote(String identifier) {
            if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid identifier: " + identifier);
            return "[" + identifier + "]";
        }

        @Override
        public String type(FieldDefinition field) {
            return switch (field.type().logicalType()) {
                case UUID -> "UNIQUEIDENTIFIER";
                case JSON, TEXT -> "NVARCHAR(MAX)";
                case STRING -> "NVARCHAR(" + (field.type().length() == null ? 255 : field.type().length()) + ")";
                case BOOLEAN -> "BIT";
                case BINARY -> "VARBINARY(MAX)";
                case INSTANT -> "DATETIMEOFFSET";
                default -> super.type(field);
            };
        }

        @Override
        public String identityClause(FieldDefinition field) {
            return field.generated() == eu.mikart.tava.schema.GeneratedValue.IDENTITY ? "IDENTITY(1,1)" : "";
        }

        @Override
        public String pagination(int limit, int offset) {
            return " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }

        @Override
        public String defaultPaginationOrder() {
            return " ORDER BY (SELECT NULL)";
        }
    }
}
