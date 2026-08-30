package com.example.donutflipscanner.database;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

final class RepositorySupport {
    private RepositorySupport() {
    }

    static void optionalString(PreparedStatement statement, int index, Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    static void optionalInstant(PreparedStatement statement, int index, Optional<Instant> value) throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.get().toEpochMilli());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }

    static void optionalDecimal(PreparedStatement statement, int index, Optional<BigDecimal> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, DatabaseValues.decimal(value.get()));
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    static int positiveLimit(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        return limit;
    }
}
