package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.schema.FieldDefinition;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

/**
 * SQL dialect hooks used by the JDBC adapter.
 *
 * <p>Implementations provide database-specific syntax and metadata behavior while the adapter owns query execution,
 * resource management, and transaction handling.
 */
public interface JdbcProfile {
    /**
     * SQL expression and bound parameters for one atomic collection mutation.
     *
     * @param sql expression containing positional placeholders
     * @param parameters values in placeholder order
     * @since 1.3.0
     */
    record MutationExpression(@NotNull String sql, @NotNull List<Object> parameters) {
        /**
         * Creates an expression with an immutable copy of its bound parameters.
         *
         * @param sql expression containing positional placeholders
         * @param parameters values in placeholder order
         */
        public MutationExpression {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Metadata for one ordered column in a JDBC index.
     *
     * @param index index name
     * @param column indexed column name
     * @param position one-based index position
     * @param unique whether the index enforces uniqueness
     * @since 1.3.0
     */
    record IndexColumn(@NotNull String index, @NotNull String column, int position, boolean unique) {}

    /**
     * Returns the stable adapter name used in capability and schema metadata.
     *
     * @return stable adapter name for this SQL dialect
     */
    @NotNull
    String name();

    /**
     * Quotes a validated SQL identifier.
     *
     * @param identifier identifier to quote
     * @return dialect-quoted identifier
     */
    @NotNull
    String quote(@Pattern("[A-Za-z_][A-Za-z0-9_]*") @NotNull String identifier);

    /**
     * Maps a logical field type to its native SQL type declaration.
     *
     * @param field field whose logical type is being mapped
     * @return native SQL type declaration
     */
    @NotNull
    String type(@NotNull FieldDefinition field);

    /**
     * Builds the SQL fragment used to generate identity values for a field.
     *
     * @param field field whose generated identity clause is being requested
     * @return SQL fragment, or an empty string when identity generation is not supported
     */
    @NotNull
    String identityClause(@NotNull FieldDefinition field);

    /**
     * Reports whether entity creation supports the {@code IF NOT EXISTS} clause.
     *
     * @return {@code true} when conditional entity creation is supported
     */
    boolean supportsIfNotExists();

    /**
     * Returns a backend-specific atomic collection expression, or {@code null} when unsupported.
     *
     * @param quotedField already quoted target field
     * @param operation collection mutation to translate
     * @return SQL expression and bound values, or {@code null} when unsupported
     * @since 1.3.0
     */
    default @Nullable MutationExpression collectionMutation(
            @NotNull String quotedField, @NotNull Mutation.Operation operation) {
        return null;
    }

    /**
     * Builds SQL to rename an entity.
     *
     * @param from current entity name
     * @param to desired entity name
     * @return SQL statement
     * @since 1.3.0
     */
    default @NotNull String renameEntity(@NotNull String from, @NotNull String to) {
        return "ALTER TABLE " + quote(from) + " RENAME TO " + quote(to);
    }

    /**
     * Builds SQL to rename a field.
     *
     * @param entity entity containing the field
     * @param from current field name
     * @param to desired field name
     * @return SQL statement
     * @since 1.3.0
     */
    default @NotNull String renameField(@NotNull String entity, @NotNull String from, @NotNull String to) {
        return "ALTER TABLE " + quote(entity) + " RENAME COLUMN " + quote(from) + " TO " + quote(to);
    }

    /**
     * Reports whether a failed write is safe for the adapter to retry.
     *
     * @param failure database failure to classify
     * @return whether the write may safely be retried
     * @since 1.3.0
     */
    default boolean retryableWrite(@NotNull SQLException failure) {
        return false;
    }

    /**
     * Reads index columns for one table. Profiles may override this when driver metadata is incomplete.
     *
     * @param connection JDBC connection used to read metadata
     * @param catalog database catalog, if available
     * @param schema database schema, if available
     * @param table table name
     * @return index columns ordered by index and position
     * @throws SQLException if metadata cannot be read
     * @since 1.3.0
     */
    default @NotNull List<IndexColumn> indexColumns(
            @NotNull Connection connection, @Nullable String catalog, @Nullable String schema, @NotNull String table)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<IndexColumn> columns = new ArrayList<>();
        try (ResultSet indexes = metadata.getIndexInfo(catalog, schema, table, false, true)) {
            while (indexes.next()) {
                String index = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (index != null && column != null) {
                    columns.add(new IndexColumn(
                            index, column, indexes.getInt("ORDINAL_POSITION"), !indexes.getBoolean("NON_UNIQUE")));
                }
            }
        }
        return List.copyOf(columns);
    }

    /**
     * Builds the dialect-specific pagination fragment.
     *
     * @param limit maximum row count
     * @param offset number of rows to skip
     * @return SQL fragment
     */
    default @NotNull String pagination(
            final @Range(from = 1, to = Integer.MAX_VALUE) int limit,
            final @Range(from = 0, to = Integer.MAX_VALUE) int offset) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * Returns a fallback ordering for dialects that require ordering when applying pagination.
     *
     * @return ordering SQL fragment, or an empty string when no fallback is needed
     */
    default @NotNull String defaultPaginationOrder() {
        return "";
    }

    /**
     * Returns the features and limits supported by this SQL dialect.
     *
     * @return declared support and limits for this dialect
     */
    @NotNull
    Capabilities capabilities();
}
