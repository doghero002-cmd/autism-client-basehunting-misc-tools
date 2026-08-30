package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.SaleEntity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SaleRepository {
    private static final String SELECT_COLUMNS = """
            sale_key, remote_transaction_id, seller_uuid, seller_name, buyer_uuid,
            buyer_name, item_fingerprint, raw_item_id, item_count, sale_price,
            unit_price, sold_at, imported_at, raw_json
            """;

    private final DatabaseManager database;

    public SaleRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<BatchWriteResult> insertBatch(List<SaleEntity> entities) {
        List<SaleEntity> snapshot = List.copyOf(Objects.requireNonNull(entities, "entities"));
        return database.transaction(connection -> {
            int inserted = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO completed_sales(
                        sale_key, remote_transaction_id, seller_uuid, seller_name,
                        buyer_uuid, buyer_name, item_fingerprint, raw_item_id,
                        item_count, sale_price, unit_price, sold_at, imported_at, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(sale_key) DO NOTHING
                    """)) {
                for (SaleEntity entity : snapshot) {
                    bind(statement, entity);
                    inserted += statement.executeUpdate();
                }
            }
            return new BatchWriteResult(snapshot.size(), inserted, snapshot.size() - inserted);
        });
    }

    public CompletableFuture<Optional<SaleEntity>> find(String saleKey) {
        Objects.requireNonNull(saleKey, "saleKey");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM completed_sales WHERE sale_key = ?")) {
                statement.setString(1, saleKey);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<SaleEntity>> findByFingerprint(String fingerprint, int limit) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM completed_sales "
                            + "WHERE item_fingerprint = ? ORDER BY sold_at DESC LIMIT ?")) {
                statement.setString(1, fingerprint);
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<SaleEntity> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(map(result));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /**
     * Returns a bounded immutable snapshot for market-statistics calculation.
     * The upper bound prevents a future-dated row from silently entering a snapshot.
     */
    public CompletableFuture<List<SaleEntity>> findByFingerprintBetween(
            String fingerprint,
            Optional<Instant> earliestInclusive,
            Instant latestInclusive,
            int limit
    ) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        earliestInclusive = earliestInclusive == null ? Optional.empty() : earliestInclusive;
        Objects.requireNonNull(latestInclusive, "latestInclusive");
        earliestInclusive.ifPresent(earliest -> {
            if (earliest.isAfter(latestInclusive)) {
                throw new IllegalArgumentException("earliestInclusive must not follow latestInclusive");
            }
        });
        int safeLimit = RepositorySupport.positiveLimit(limit);
        Optional<Instant> safeEarliest = earliestInclusive;
        return database.execute(connection -> {
            String lowerBound = safeEarliest.isPresent() ? " AND sold_at >= ?" : "";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM completed_sales "
                            + "WHERE item_fingerprint = ? AND sold_at <= ?"
                            + lowerBound + " ORDER BY sold_at DESC LIMIT ?")) {
                int index = 1;
                statement.setString(index++, fingerprint);
                statement.setLong(index++, latestInclusive.toEpochMilli());
                if (safeEarliest.isPresent()) {
                    statement.setLong(index++, safeEarliest.get().toEpochMilli());
                }
                statement.setInt(index, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<SaleEntity> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(map(result));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    public CompletableFuture<Long> count() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM completed_sales");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    private void bind(PreparedStatement statement, SaleEntity entity) throws Exception {
        statement.setString(1, entity.saleKey());
        RepositorySupport.optionalString(statement, 2, entity.remoteTransactionId());
        RepositorySupport.optionalString(statement, 3, entity.sellerUuid());
        RepositorySupport.optionalString(statement, 4, entity.sellerName());
        RepositorySupport.optionalString(statement, 5, entity.buyerUuid());
        RepositorySupport.optionalString(statement, 6, entity.buyerName());
        statement.setString(7, entity.itemFingerprint());
        statement.setString(8, entity.rawItemId());
        statement.setInt(9, entity.itemCount());
        statement.setString(10, DatabaseValues.decimal(entity.salePrice()));
        RepositorySupport.optionalDecimal(statement, 11, entity.unitPrice());
        statement.setLong(12, entity.soldAt().toEpochMilli());
        statement.setLong(13, entity.importedAt().toEpochMilli());
        RepositorySupport.optionalString(statement, 14, entity.rawJson());
    }

    private SaleEntity map(ResultSet result) throws Exception {
        return new SaleEntity(
                result.getString("sale_key"),
                DatabaseValues.optionalString(result, "remote_transaction_id"),
                DatabaseValues.optionalString(result, "seller_uuid"),
                DatabaseValues.optionalString(result, "seller_name"),
                DatabaseValues.optionalString(result, "buyer_uuid"),
                DatabaseValues.optionalString(result, "buyer_name"),
                result.getString("item_fingerprint"),
                result.getString("raw_item_id"),
                result.getInt("item_count"),
                DatabaseValues.requiredDecimal(result, "sale_price"),
                DatabaseValues.optionalDecimal(result, "unit_price"),
                Instant.ofEpochMilli(result.getLong("sold_at")),
                Instant.ofEpochMilli(result.getLong("imported_at")),
                DatabaseValues.optionalString(result, "raw_json")
        );
    }
}
