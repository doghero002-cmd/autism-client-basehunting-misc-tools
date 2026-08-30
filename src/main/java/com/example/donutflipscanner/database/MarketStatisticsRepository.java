package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.MarketStatisticsEntity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class MarketStatisticsRepository {
    private final DatabaseManager database;

    public MarketStatisticsRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> upsert(MarketStatisticsEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO market_statistics(
                        statistics_key, item_fingerprint, computed_at, window_start,
                        window_end, sample_count, minimum_price, maximum_price,
                        median_price, statistics_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(statistics_key) DO UPDATE SET
                        item_fingerprint = excluded.item_fingerprint,
                        computed_at = excluded.computed_at,
                        window_start = excluded.window_start,
                        window_end = excluded.window_end,
                        sample_count = excluded.sample_count,
                        minimum_price = excluded.minimum_price,
                        maximum_price = excluded.maximum_price,
                        median_price = excluded.median_price,
                        statistics_json = excluded.statistics_json
                    """)) {
                statement.setString(1, entity.statisticsKey());
                statement.setString(2, entity.itemFingerprint());
                statement.setLong(3, entity.computedAt().toEpochMilli());
                statement.setLong(4, entity.windowStart().toEpochMilli());
                statement.setLong(5, entity.windowEnd().toEpochMilli());
                statement.setInt(6, entity.sampleCount());
                RepositorySupport.optionalDecimal(statement, 7, entity.minimumPrice());
                RepositorySupport.optionalDecimal(statement, 8, entity.maximumPrice());
                RepositorySupport.optionalDecimal(statement, 9, entity.medianPrice());
                RepositorySupport.optionalString(statement, 10, entity.statisticsJson());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<MarketStatisticsEntity>> findLatest(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT statistics_key, item_fingerprint, computed_at, window_start,
                           window_end, sample_count, minimum_price, maximum_price,
                           median_price, statistics_json
                    FROM market_statistics
                    WHERE item_fingerprint = ?
                    ORDER BY computed_at DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, fingerprint);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new MarketStatisticsEntity(
                            result.getString("statistics_key"),
                            result.getString("item_fingerprint"),
                            Instant.ofEpochMilli(result.getLong("computed_at")),
                            Instant.ofEpochMilli(result.getLong("window_start")),
                            Instant.ofEpochMilli(result.getLong("window_end")),
                            result.getInt("sample_count"),
                            DatabaseValues.optionalDecimal(result, "minimum_price"),
                            DatabaseValues.optionalDecimal(result, "maximum_price"),
                            DatabaseValues.optionalDecimal(result, "median_price"),
                            DatabaseValues.optionalString(result, "statistics_json")
                    ));
                }
            }
        });
    }

    public CompletableFuture<Long> count() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM market_statistics");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }
}
