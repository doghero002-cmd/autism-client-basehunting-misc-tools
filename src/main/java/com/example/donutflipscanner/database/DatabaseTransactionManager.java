package com.example.donutflipscanner.database;

import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseTransactionManager {
    private DatabaseTransactionManager() {
    }

    public static <T> T execute(Connection connection, SqlOperation<T> operation) throws Exception {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (Exception exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @FunctionalInterface
    public interface SqlOperation<T> {
        T execute(Connection connection) throws Exception;
    }
}
