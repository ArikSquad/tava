package eu.mikart.tava.jdbc;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.schema.FieldDefinition;

public interface JdbcProfile {
    String name();

    String quote(String identifier);

    String type(FieldDefinition field);

    String identityClause(FieldDefinition field);

    boolean supportsIfNotExists();

    default String pagination(int limit, int offset) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    default String defaultPaginationOrder() {
        return "";
    }

    Capabilities capabilities();
}
