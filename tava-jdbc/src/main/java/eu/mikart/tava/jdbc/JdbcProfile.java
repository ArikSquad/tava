package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.schema.FieldDefinition;
import org.jetbrains.annotations.NotNull;

public interface JdbcProfile {
    @NotNull String name();

    @NotNull String quote(@NotNull String identifier);

    @NotNull String type(@NotNull FieldDefinition field);

    @NotNull String identityClause(@NotNull FieldDefinition field);

    boolean supportsIfNotExists();

    default @NotNull String pagination(final int limit, final int offset) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    default @NotNull String defaultPaginationOrder() {
        return "";
    }

    @NotNull Capabilities capabilities();
}
