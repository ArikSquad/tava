package eu.mikart.tava.jdbc;

import eu.mikart.tava.spi.OperationContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

final class JdbcOperations {
    private JdbcOperations() {}

    static Connection guard(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        return result instanceof Statement statement ? guard(statement) : result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Statement guard(Statement statement) throws SQLException {
        AutoCloseable registration;
        try {
            registration = OperationContext.onCancel(() -> {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                }
            });
        } catch (RuntimeException failure) {
            try {
                statement.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
        Class<?> type = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) registration.close();
                    if (method.getName().startsWith("execute")) {
                        long remaining = OperationContext.remainingNanos();
                        if (remaining > 0)
                            statement.setQueryTimeout((int) Math.min(
                                    Integer.MAX_VALUE,
                                    Math.max(
                                            1,
                                            TimeUnit.NANOSECONDS.toSeconds(remaining)
                                                    + (remaining % 1000000000L == 0 ? 0 : 1))));
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
