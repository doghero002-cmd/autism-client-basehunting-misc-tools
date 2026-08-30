package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ListingRepository {
    private static final String SELECT_COLUMNS = """
            listing_key, remote_listing_id, seller_uuid, seller_name, item_fingerprint,
            raw_item_id, item_count, listing_price, unit_price, first_seen_at,
            last_seen_at, listed_at, expires_at, state, missing_observations, raw_json
            """;

    private final DatabaseManager database;

    public ListingRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<BatchWriteResult> upsertBatch(List<ListingEntity> entities) {
        List<ListingEntity> snapshot = List.copyOf(Objects.requireNonNull(entities, "entities"));
        return database.transaction(connection -> {
            int inserted = 0;
            try (PreparedStatement exists = connection.prepareStatement(
                    "SELECT 1 FROM auction_listings WHERE listing_key = ?");
                 PreparedStatement upsert = connection.prepareStatement("""
                         INSERT INTO auction_listings(
                             listing_key, remote_listing_id, seller_uuid, seller_name,
                             item_fingerprint, raw_item_id, item_count, listing_price,
                             unit_price, first_seen_at, last_seen_at, listed_at, expires_at,
                             state, missing_observations, raw_json
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         ON CONFLICT(listing_key) DO UPDATE SET
                             remote_listing_id = excluded.remote_listing_id,
                             seller_uuid = excluded.seller_uuid,
                             seller_name = excluded.seller_name,
                             item_fingerprint = excluded.item_fingerprint,
                             raw_item_id = excluded.raw_item_id,
                             item_count = excluded.item_count,
                             listing_price = excluded.listing_price,
                             unit_price = excluded.unit_price,
                             first_seen_at = MIN(auction_listings.first_seen_at, excluded.first_seen_at),
                             last_seen_at = MAX(auction_listings.last_seen_at, excluded.last_seen_at),
                             listed_at = excluded.listed_at,
                             expires_at = excluded.expires_at,
                             state = excluded.state,
                             missing_observations = excluded.missing_observations,
                             raw_json = excluded.raw_json
                         """)) {
                for (ListingEntity entity : snapshot) {
                    exists.setString(1, entity.listingKey());
                    try (ResultSet result = exists.executeQuery()) {
                        if (!result.next()) {
                            inserted++;
                        }
                    }
                    bind(upsert, entity);
                    upsert.executeUpdate();
                }
            }
            return new BatchWriteResult(snapshot.size(), inserted, snapshot.size() - inserted);
        });
    }

    public CompletableFuture<Optional<ListingEntity>> find(String listingKey) {
        Objects.requireNonNull(listingKey, "listingKey");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM auction_listings WHERE listing_key = ?")) {
                statement.setString(1, listingKey);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Advances verification time even when a fetched API page is byte-for-byte unchanged.
     * Page-hash deduplication may skip full upserts, but it must never skip this observation.
     */
    public CompletableFuture<Integer> markObserved(Set<String> listingKeys, Instant observedAt) {
        Set<String> keys = Set.copyOf(Objects.requireNonNull(listingKeys, "listingKeys"));
        Objects.requireNonNull(observedAt, "observedAt");
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return database.transaction(connection -> {
            int updated = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE auction_listings
                    SET last_seen_at = MAX(last_seen_at, ?),
                        state = 'ACTIVE',
                        missing_observations = 0
                    WHERE listing_key = ?
                    """)) {
                for (String key : keys) {
                    if (key.isBlank()) {
                        throw new IllegalArgumentException("listing key must not be blank");
                    }
                    statement.setLong(1, observedAt.toEpochMilli());
                    statement.setString(2, key);
                    updated += statement.executeUpdate();
                }
            }
            return updated;
        });
    }

    /** Marks listings whose continued availability has not been recently reconfirmed. */
    public CompletableFuture<Integer> markUnverifiedBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE auction_listings
                    SET state = 'INACTIVE_UNKNOWN'
                    WHERE state IN ('ACTIVE', 'MISSING_ONCE', 'MISSING_REPEATEDLY')
                      AND last_seen_at < ?
                    """)) {
                statement.setLong(1, cutoff.toEpochMilli());
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<ListingEntity>> findByFingerprint(String fingerprint, int limit) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM auction_listings "
                            + "WHERE item_fingerprint = ? ORDER BY last_seen_at DESC LIMIT ?")) {
                statement.setString(1, fingerprint);
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<ListingEntity> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(map(result));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /** Returns only listings observed as active, for ask-side supporting evidence. */
    public CompletableFuture<List<ListingEntity>> findActiveByFingerprint(String fingerprint, int limit) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM auction_listings "
                            + "WHERE item_fingerprint = ? AND state = 'ACTIVE' "
                            + "ORDER BY last_seen_at DESC LIMIT ?")) {
                statement.setString(1, fingerprint);
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<ListingEntity> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(map(result));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /** Returns a bounded set of fingerprints that currently have active asks. */
    public CompletableFuture<List<String>> findActiveFingerprints(int limit) {
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT item_fingerprint
                    FROM auction_listings
                    WHERE state = 'ACTIVE'
                    GROUP BY item_fingerprint
                    ORDER BY MAX(last_seen_at) DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<String> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(result.getString(1));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /** Records explicit missing evidence; it is never called implicitly after a failed page request. */
    public CompletableFuture<Optional<ListingEntity>> recordMissingObservation(
            String listingKey,
            int inactiveThreshold
    ) {
        Objects.requireNonNull(listingKey, "listingKey");
        if (inactiveThreshold < 2) {
            throw new IllegalArgumentException("inactiveThreshold must be at least two");
        }
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE auction_listings
                    SET missing_observations = missing_observations + 1,
                        state = CASE
                            WHEN missing_observations + 1 >= ? THEN 'INACTIVE_UNKNOWN'
                            WHEN missing_observations + 1 = 1 THEN 'MISSING_ONCE'
                            ELSE 'MISSING_REPEATEDLY'
                        END
                    WHERE listing_key = ?
                      AND state IN ('ACTIVE', 'MISSING_ONCE', 'MISSING_REPEATEDLY')
                    """)) {
                statement.setInt(1, inactiveThreshold);
                statement.setString(2, listingKey);
                statement.executeUpdate();
            }
            return find(connection, listingKey);
        });
    }

    public CompletableFuture<Boolean> markState(String listingKey, ListingState state) {
        Objects.requireNonNull(listingKey, "listingKey");
        Objects.requireNonNull(state, "state");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE auction_listings SET state = ? WHERE listing_key = ?")) {
                statement.setString(1, state.name());
                statement.setString(2, listingKey);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Integer> deleteStaleInactiveBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM auction_listings
                    WHERE listing_key IN (
                        SELECT listing_key
                        FROM auction_listings AS candidate
                        WHERE candidate.last_seen_at < ?
                          AND candidate.state IN ('INACTIVE_UNKNOWN', 'EXPIRED')
                          AND NOT EXISTS (
                              SELECT 1 FROM opportunities
                              WHERE opportunities.listing_key = candidate.listing_key
                          )
                        ORDER BY candidate.last_seen_at
                        LIMIT ?
                    )
                    """)) {
                statement.setLong(1, cutoff.toEpochMilli());
                statement.setInt(2, safeLimit);
                return statement.executeUpdate();
            }
        });
    }

    /** Drops bulky raw snapshots while preserving normalized listing history. */
    public CompletableFuture<Integer> clearStaleRawJsonBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE auction_listings
                    SET raw_json = NULL
                    WHERE listing_key IN (
                        SELECT listing_key
                        FROM auction_listings
                        WHERE last_seen_at < ? AND raw_json IS NOT NULL
                        ORDER BY last_seen_at
                        LIMIT ?
                    )
                    """)) {
                statement.setLong(1, cutoff.toEpochMilli());
                statement.setInt(2, safeLimit);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Long> count() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM auction_listings");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Long> countActive() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM auction_listings WHERE state = 'ACTIVE'");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    private Optional<ListingEntity> find(java.sql.Connection connection, String listingKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM auction_listings WHERE listing_key = ?")) {
            statement.setString(1, listingKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private void bind(PreparedStatement statement, ListingEntity entity) throws Exception {
        statement.setString(1, entity.listingKey());
        RepositorySupport.optionalString(statement, 2, entity.remoteListingId());
        RepositorySupport.optionalString(statement, 3, entity.sellerUuid());
        RepositorySupport.optionalString(statement, 4, entity.sellerName());
        statement.setString(5, entity.itemFingerprint());
        statement.setString(6, entity.rawItemId());
        statement.setInt(7, entity.itemCount());
        statement.setString(8, DatabaseValues.decimal(entity.listingPrice()));
        RepositorySupport.optionalDecimal(statement, 9, entity.unitPrice());
        statement.setLong(10, entity.firstSeenAt().toEpochMilli());
        statement.setLong(11, entity.lastSeenAt().toEpochMilli());
        RepositorySupport.optionalInstant(statement, 12, entity.listedAt());
        RepositorySupport.optionalInstant(statement, 13, entity.expiresAt());
        statement.setString(14, entity.state().name());
        statement.setInt(15, entity.missingObservations());
        RepositorySupport.optionalString(statement, 16, entity.rawJson());
    }

    private ListingEntity map(ResultSet result) throws Exception {
        return new ListingEntity(
                result.getString("listing_key"),
                DatabaseValues.optionalString(result, "remote_listing_id"),
                DatabaseValues.optionalString(result, "seller_uuid"),
                DatabaseValues.optionalString(result, "seller_name"),
                result.getString("item_fingerprint"),
                result.getString("raw_item_id"),
                result.getInt("item_count"),
                DatabaseValues.requiredDecimal(result, "listing_price"),
                DatabaseValues.optionalDecimal(result, "unit_price"),
                Instant.ofEpochMilli(result.getLong("first_seen_at")),
                Instant.ofEpochMilli(result.getLong("last_seen_at")),
                DatabaseValues.optionalInstant(result, "listed_at"),
                DatabaseValues.optionalInstant(result, "expires_at"),
                ListingState.valueOf(result.getString("state")),
                result.getInt("missing_observations"),
                DatabaseValues.optionalString(result, "raw_json")
        );
    }
}
