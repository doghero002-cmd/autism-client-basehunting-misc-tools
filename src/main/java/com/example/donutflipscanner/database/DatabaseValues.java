package com.example.donutflipscanner.database;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

final class DatabaseValues {
    private DatabaseValues() {
    }

    static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    static BigDecimal requiredDecimal(ResultSet result, String column) throws SQLException {
        return new BigDecimal(result.getString(column));
    }

    static Optional<BigDecimal> optionalDecimal(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        return value == null ? Optional.empty() : Optional.of(new BigDecimal(value));
    }

    static Optional<String> optionalString(ResultSet result, String column) throws SQLException {
        return Optional.ofNullable(result.getString(column));
    }

    static Optional<Instant> optionalInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }
}
