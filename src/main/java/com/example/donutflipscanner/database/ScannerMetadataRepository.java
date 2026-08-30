package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.ScannerMetadataEntity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ScannerMetadataRepository {
    private final DatabaseManager database;

    public ScannerMetadataRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> put(ScannerMetadataEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scanner_metadata(metadata_key, metadata_value, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(metadata_key) DO UPDATE SET
                        metadata_value = excluded.metadata_value,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, entity.key());
                statement.setString(2, entity.value());
                statement.setLong(3, entity.updatedAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<ScannerMetadataEntity>> find(String key) {
        Objects.requireNonNull(key, "key");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT metadata_key, metadata_value, updated_at
                    FROM scanner_metadata
                    WHERE metadata_key = ?
                    """)) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ScannerMetadataEntity(
                            result.getString("metadata_key"),
                            result.getString("metadata_value"),
                            Instant.ofEpochMilli(result.getLong("updated_at"))
                    ));
                }
            }
        });
    }
}
