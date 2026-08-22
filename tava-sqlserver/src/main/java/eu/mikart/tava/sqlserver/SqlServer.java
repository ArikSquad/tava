package eu.mikart.tava.sqlserver;

import eu.mikart.tava.jdbc.JdbcAdapter;
import eu.mikart.tava.jdbc.StandardJdbcProfile;
import eu.mikart.tava.schema.FieldDefinition;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

public final class SqlServer {
    private SqlServer() {
    }

    public static @NotNull JdbcAdapter connect(
            final @NotNull String url,
            final @NotNull String user,
            final @NotNull String password
    ) {
        return JdbcAdapter.driverManager(new Profile(), url, user, password);
    }

    private static final class Profile extends StandardJdbcProfile {
        private Profile() {
            super("sqlserver", '"', false);
        }

        @Override
        public @NotNull String quote(
                @Pattern("[A-Za-z_][A-Za-z0-9_]*") final @NotNull String identifier
        ) {
            if (!validIdentifier(identifier))
                throw new IllegalArgumentException("Invalid identifier: " + identifier);
            return "[" + identifier + "]";
        }

        @Override
        public @NotNull String type(final @NotNull FieldDefinition field) {
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
        public @NotNull String identityClause(final @NotNull FieldDefinition field) {
            return field.generated() == eu.mikart.tava.schema.GeneratedValue.IDENTITY ? "IDENTITY(1,1)" : "";
        }

        @Override
        public @NotNull String pagination(
                final @Range(from = 1, to = Integer.MAX_VALUE) int limit,
                final @Range(from = 0, to = Integer.MAX_VALUE) int offset
        ) {
            return " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }

        @Override
        public @NotNull String defaultPaginationOrder() {
            return " ORDER BY (SELECT NULL)";
        }

        @Override
        public @NotNull String renameEntity(@NotNull String from, @NotNull String to) {
            quote(from); quote(to);
            return "EXEC sp_rename N'" + from + "', N'" + to + "'";
        }

        @Override
        public @NotNull String renameField(@NotNull String entity, @NotNull String from, @NotNull String to) {
            quote(entity); quote(from); quote(to);
            return "EXEC sp_rename N'" + entity + "." + from + "', N'" + to + "', 'COLUMN'";
        }
    }
}
