package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.database.entity.OpportunityStateChangeEntity;
import com.example.donutflipscanner.database.entity.OpportunityListingView;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class OpportunityRepository {
    public static final String UNVERIFIED_LISTING_REASON =
            "Listing was not reconfirmed by a fresh auction API response.";
    private static final String SELECT_COLUMNS = """
            opportunity_id, listing_key, item_fingerprint, detected_at,
            purchase_price, fair_value, estimated_profit, roi_percent,
            confidence_percent, state, rejection_reason, evaluation_json,
            evaluation_version
            """;

    private final DatabaseManager database;

    public OpportunityRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> upsert(OpportunityEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return database.transaction(connection -> {
            Optional<String> previousState = state(connection, entity.opportunityId());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO opportunities(
                        opportunity_id, listing_key, item_fingerprint, detected_at,
                        purchase_price, fair_value, estimated_profit, roi_percent,
                        confidence_percent, state, rejection_reason, evaluation_json,
                        evaluation_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(opportunity_id) DO UPDATE SET
                        listing_key = excluded.listing_key,
                        item_fingerprint = excluded.item_fingerprint,
                        detected_at = excluded.detected_at,
                        purchase_price = excluded.purchase_price,
                        fair_value = excluded.fair_value,
                        estimated_profit = excluded.estimated_profit,
                        roi_percent = excluded.roi_percent,
                        confidence_percent = excluded.confidence_percent,
                        state = excluded.state,
                        rejection_reason = excluded.rejection_reason,
                        evaluation_json = excluded.evaluation_json,
                        evaluation_version = excluded.evaluation_version
                    """)) {
                bind(statement, entity);
                statement.executeUpdate();
            }
            if (previousState.isEmpty() || !previousState.get().equals(entity.state())) {
                insertStateChange(
                        connection,
                        entity.opportunityId(),
                        previousState,
                        entity.state(),
                        entity.detectedAt(),
                        Optional.empty()
                );
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> updateState(
            String opportunityId,
            String newState,
            Instant changedAt,
            Optional<String> reason
    ) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        if (newState == null || newState.isBlank()) {
            throw new IllegalArgumentException("newState must not be blank");
        }
        Objects.requireNonNull(changedAt, "changedAt");
        reason = reason == null ? Optional.empty() : reason;
        Optional<String> reasonSnapshot = reason;
        return database.transaction(connection -> {
            Optional<String> previousState = state(connection, opportunityId);
            if (previousState.isEmpty()) {
                return false;
            }
            if (previousState.get().equals(newState)) {
                return true;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE opportunities SET state = ? WHERE opportunity_id = ?")) {
                statement.setString(1, newState);
                statement.setString(2, opportunityId);
                statement.executeUpdate();
            }
            insertStateChange(connection, opportunityId, previousState, newState, changedAt, reasonSnapshot);
            return true;
        });
    }

    public CompletableFuture<Optional<OpportunityEntity>> find(String opportunityId) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM opportunities WHERE opportunity_id = ?")) {
                statement.setString(1, opportunityId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<OpportunityStateChangeEntity>> stateHistory(String opportunityId, int limit) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT change_id, opportunity_id, previous_state, new_state, changed_at, reason
                    FROM opportunity_state_changes
                    WHERE opportunity_id = ?
                    ORDER BY changed_at, change_id
                    LIMIT ?
                    """)) {
                statement.setString(1, opportunityId);
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<OpportunityStateChangeEntity> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new OpportunityStateChangeEntity(
                                result.getLong("change_id"),
                                result.getString("opportunity_id"),
                                DatabaseValues.optionalString(result, "previous_state"),
                                result.getString("new_state"),
                                Instant.ofEpochMilli(result.getLong("changed_at")),
                                DatabaseValues.optionalString(result, "reason")
                        ));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    public CompletableFuture<Long> count() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM opportunities");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Long> countActive() {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM opportunities WHERE state IN ('NEW', 'REVIEWED')");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    /**
     * Atomically moves reviewable opportunities to history when their joined listing is
     * missing, inactive, or older than the bounded verification window.
     */
    public CompletableFuture<List<String>> expireUnverifiedActive(
            Instant verifiedAfter,
            Instant changedAt
    ) {
        Objects.requireNonNull(verifiedAfter, "verifiedAfter");
        Objects.requireNonNull(changedAt, "changedAt");
        return database.transaction(connection -> {
            List<ExpiringOpportunity> candidates = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT o.opportunity_id, o.state
                    FROM opportunities AS o
                    LEFT JOIN auction_listings AS l ON l.listing_key = o.listing_key
                    WHERE o.state IN ('NEW', 'REVIEWED')
                      AND (l.listing_key IS NULL OR l.state <> 'ACTIVE' OR l.last_seen_at < ?)
                    ORDER BY o.detected_at
                    """)) {
                select.setLong(1, verifiedAfter.toEpochMilli());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        candidates.add(new ExpiringOpportunity(
                                result.getString("opportunity_id"), result.getString("state")
                        ));
                    }
                }
            }
            List<String> expired = new ArrayList<>(candidates.size());
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE opportunities
                    SET state = 'NO_LONGER_AVAILABLE',
                        rejection_reason = COALESCE(rejection_reason, ?)
                    WHERE opportunity_id = ? AND state = ?
                    """)) {
                for (ExpiringOpportunity candidate : candidates) {
                    update.setString(1, UNVERIFIED_LISTING_REASON);
                    update.setString(2, candidate.opportunityId());
                    update.setString(3, candidate.previousState());
                    if (update.executeUpdate() == 1) {
                        insertStateChange(
                                connection, candidate.opportunityId(),
                                Optional.of(candidate.previousState()), "NO_LONGER_AVAILABLE",
                                changedAt, Optional.of(UNVERIFIED_LISTING_REASON)
                        );
                        expired.add(candidate.opportunityId());
                    }
                }
            }
            return List.copyOf(expired);
        });
    }

    /** Bounded joined rows prevent GUI providers from issuing one listing query per opportunity. */
    public CompletableFuture<List<OpportunityListingView>> findRecentWithListings(int limit) {
        return findRecentWithListings(limit, Instant.EPOCH);
    }

    /** Active rows are returned only while their listing has a fresh ACTIVE observation. */
    public CompletableFuture<List<OpportunityListingView>> findRecentWithListings(
            int limit,
            Instant verifiedAfter
    ) {
        int safeLimit = RepositorySupport.positiveLimit(limit);
        Objects.requireNonNull(verifiedAfter, "verifiedAfter");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT o.opportunity_id, o.listing_key, o.item_fingerprint, o.detected_at,
                           o.purchase_price, o.fair_value, o.estimated_profit, o.roi_percent,
                           o.confidence_percent, o.state, o.rejection_reason, o.evaluation_json,
                           o.evaluation_version, COALESCE(l.raw_item_id, 'unknown:unknown') AS raw_item_id,
                           COALESCE(l.item_count, 1) AS item_count, l.seller_name, l.listed_at,
                           COALESCE(l.last_seen_at, o.detected_at) AS last_verified_at,
                           f.normalized_metadata
                    FROM opportunities AS o
                    LEFT JOIN auction_listings AS l ON l.listing_key = o.listing_key
                    LEFT JOIN item_fingerprints AS f ON f.fingerprint = o.item_fingerprint
                    WHERE o.state NOT IN ('NEW', 'REVIEWED')
                       OR (l.state = 'ACTIVE' AND l.last_seen_at >= ?)
                    ORDER BY o.detected_at DESC
                    LIMIT ?
                    """)) {
                statement.setLong(1, verifiedAfter.toEpochMilli());
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    List<OpportunityListingView> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new OpportunityListingView(
                                map(result),
                                result.getString("raw_item_id"),
                                result.getInt("item_count"),
                                DatabaseValues.optionalString(result, "seller_name"),
                                DatabaseValues.optionalInstant(result, "listed_at"),
                                Instant.ofEpochMilli(result.getLong("last_verified_at")),
                                DatabaseValues.optionalString(result, "normalized_metadata")
                        ));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /** Intended for the GUI's confirmed clear-history action in a later chunk. */
    public CompletableFuture<Integer> deleteAll() {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM opportunities")) {
                return statement.executeUpdate();
            }
        });
    }

    /** Clears terminal/history rows without deleting currently reviewable opportunities. */
    public CompletableFuture<Integer> deleteHistory() {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM opportunities WHERE state NOT IN ('NEW', 'REVIEWED')")) {
                return statement.executeUpdate();
            }
        });
    }

    private Optional<String> state(java.sql.Connection connection, String opportunityId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM opportunities WHERE opportunity_id = ?")) {
            statement.setString(1, opportunityId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private void insertStateChange(
            java.sql.Connection connection,
            String opportunityId,
            Optional<String> previousState,
            String newState,
            Instant changedAt,
            Optional<String> reason
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO opportunity_state_changes(
                    opportunity_id, previous_state, new_state, changed_at, reason
                ) VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, opportunityId);
            RepositorySupport.optionalString(statement, 2, previousState);
            statement.setString(3, newState);
            statement.setLong(4, changedAt.toEpochMilli());
            RepositorySupport.optionalString(statement, 5, reason);
            statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, OpportunityEntity entity) throws Exception {
        statement.setString(1, entity.opportunityId());
        statement.setString(2, entity.listingKey());
        statement.setString(3, entity.itemFingerprint());
        statement.setLong(4, entity.detectedAt().toEpochMilli());
        statement.setString(5, DatabaseValues.decimal(entity.purchasePrice()));
        statement.setString(6, DatabaseValues.decimal(entity.fairValue()));
        statement.setString(7, DatabaseValues.decimal(entity.estimatedProfit()));
        statement.setString(8, DatabaseValues.decimal(entity.roiPercent()));
        statement.setString(9, DatabaseValues.decimal(entity.confidencePercent()));
        statement.setString(10, entity.state());
        RepositorySupport.optionalString(statement, 11, entity.rejectionReason());
        RepositorySupport.optionalString(statement, 12, entity.evaluationJson());
        statement.setString(13, entity.evaluationVersion());
    }

    private OpportunityEntity map(ResultSet result) throws Exception {
        return new OpportunityEntity(
                result.getString("opportunity_id"),
                result.getString("listing_key"),
                result.getString("item_fingerprint"),
                Instant.ofEpochMilli(result.getLong("detected_at")),
                DatabaseValues.requiredDecimal(result, "purchase_price"),
                DatabaseValues.requiredDecimal(result, "fair_value"),
                DatabaseValues.requiredDecimal(result, "estimated_profit"),
                DatabaseValues.requiredDecimal(result, "roi_percent"),
                DatabaseValues.requiredDecimal(result, "confidence_percent"),
                result.getString("state"),
                DatabaseValues.optionalString(result, "rejection_reason"),
                DatabaseValues.optionalString(result, "evaluation_json"),
                result.getString("evaluation_version")
        );
    }

    private record ExpiringOpportunity(String opportunityId, String previousState) {
    }
}
