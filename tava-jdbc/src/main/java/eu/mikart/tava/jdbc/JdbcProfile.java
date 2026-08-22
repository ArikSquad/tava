package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.schema.FieldDefinition;
import eu.mikart.tava.query.Mutation;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

import java.util.List;
import java.sql.SQLException;

public interface JdbcProfile {
    record MutationExpression(@NotNull String sql, @NotNull List<Object> parameters) {
        public MutationExpression { parameters = List.copyOf(parameters); }
    }
    @NotNull String name();

    @NotNull String quote(@Pattern("[A-Za-z_][A-Za-z0-9_]*") @NotNull String identifier);

    @NotNull String type(@NotNull FieldDefinition field);

    @NotNull String identityClause(@NotNull FieldDefinition field);

    boolean supportsIfNotExists();

    /** Returns a backend-specific atomic collection expression, or null when unsupported. */
    default @Nullable MutationExpression collectionMutation(@NotNull String quotedField,
                                                             @NotNull Mutation.Operation operation) {
        return null;
    }

    default @NotNull String renameEntity(@NotNull String from, @NotNull String to) {
        return "ALTER TABLE " + quote(from) + " RENAME TO " + quote(to);
    }

    default @NotNull String renameField(@NotNull String entity, @NotNull String from, @NotNull String to) {
        return "ALTER TABLE " + quote(entity) + " RENAME COLUMN " + quote(from) + " TO " + quote(to);
    }

    default boolean retryableWrite(@NotNull SQLException failure) { return false; }

    default @NotNull String pagination(
            final @Range(from = 1, to = Integer.MAX_VALUE) int limit,
            final @Range(from = 0, to = Integer.MAX_VALUE) int offset
    ) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    default @NotNull String defaultPaginationOrder() {
        return "";
    }

    @NotNull Capabilities capabilities();
}
