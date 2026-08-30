package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class FingerprintRepository {
    private final DatabaseManager database;

    public FingerprintRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Boolean> insertIfAbsent(ItemFingerprintEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO item_fingerprints(
                        fingerprint, base_item_id, match_type, normalized_metadata, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(fingerprint) DO NOTHING
                    """)) {
                statement.setString(1, entity.fingerprint());
                statement.setString(2, entity.baseItemId());
                statement.setString(3, entity.matchType());
                statement.setString(4, entity.normalizedMetadata());
                statement.setLong(5, entity.createdAt().toEpochMilli());
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<BatchWriteResult> insertBatch(List<ItemFingerprintEntity> entities) {
        List<ItemFingerprintEntity> snapshot = List.copyOf(Objects.requireNonNull(entities, "entities"));
        return database.transaction(connection -> {
            int inserted = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO item_fingerprints(
                        fingerprint, base_item_id, match_type, normalized_metadata, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(fingerprint) DO NOTHING
                    """)) {
                for (ItemFingerprintEntity entity : snapshot) {
                    statement.setString(1, entity.fingerprint());
                    statement.setString(2, entity.baseItemId());
                    statement.setString(3, entity.matchType());
                    statement.setString(4, entity.normalizedMetadata());
                    statement.setLong(5, entity.createdAt().toEpochMilli());
                    inserted += statement.executeUpdate();
                }
            }
            return new BatchWriteResult(snapshot.size(), inserted, snapshot.size() - inserted);
        });
    }

    public CompletableFuture<Optional<ItemFingerprintEntity>> find(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT fingerprint, base_item_id, match_type, normalized_metadata, created_at
                    FROM item_fingerprints
                    WHERE fingerprint = ?
                    """)) {
                statement.setString(1, fingerprint);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ItemFingerprintEntity(
                            result.getString("fingerprint"),
                            result.getString("base_item_id"),
                            result.getString("match_type"),
                            result.getString("normalized_metadata"),
                            Instant.ofEpochMilli(result.getLong("created_at"))
                    ));
                }
            }
        });
    }

    public CompletableFuture<Long> count() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM item_fingerprints");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }
}
