package com.example.donutflipscanner.database;

import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionState;
import com.example.donutflipscanner.database.entity.TradeExecutionEntity;
import com.example.donutflipscanner.database.entity.TradeExecutionTransitionEntity;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Append-oriented audit journal for automation state; it never initiates an execution. */
public final class TradeExecutionRepository {
    private static final String TERMINAL_STATES = "'COMPLETED','CANCELLED','FAILED','INTERRUPTED_REQUIRES_REVIEW'";
    private final DatabaseManager database;

    public TradeExecutionRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> recordTransition(
            TradeExecutionRequest request,
            TradeExecutionState previous,
            TradeExecutionState current,
            String message,
            Instant at
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(at, "at");
        String safeMessage = Objects.requireNonNullElse(message, "");
        return database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO trade_executions(
                        execution_id, opportunity_id, listing_key, mode, state, item_id,
                        item_fingerprint, expected_item_count, expected_listing_price,
                        status_message, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, request.executionId());
                insert.setString(2, request.opportunityId());
                insert.setString(3, request.listingKey());
                insert.setString(4, request.requestedMode().name());
                insert.setString(5, current.name());
                insert.setString(6, request.itemId());
                insert.setString(7, request.itemFingerprint());
                insert.setInt(8, request.expectedItemCount());
                insert.setString(9, DatabaseValues.decimal(request.expectedListingPrice()));
                insert.setString(10, safeMessage);
                insert.setLong(11, at.toEpochMilli());
                insert.setLong(12, at.toEpochMilli());
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE trade_executions
                    SET state = ?, status_message = ?, updated_at = ?
                    WHERE execution_id = ?
                    """)) {
                update.setString(1, current.name());
                update.setString(2, safeMessage);
                update.setLong(3, at.toEpochMilli());
                update.setString(4, request.executionId());
                update.executeUpdate();
            }
            insertTransition(connection, request.executionId(), previous, current, safeMessage, at);
            return null;
        });
    }

    public CompletableFuture<Void> recordOutcome(
            String executionId,
            Optional<BigDecimal> relistPrice,
            boolean purchaseConfirmed,
            boolean listingConfirmed,
            Instant at
    ) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(at, "at");
        Optional<BigDecimal> safePrice = relistPrice == null ? Optional.empty() : relistPrice;
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE trade_executions
                    SET relist_price = ?, purchase_confirmed = ?, listing_confirmed = ?, updated_at = ?
                    WHERE execution_id = ?
                    """)) {
                if (safePrice.isPresent()) {
                    statement.setString(1, DatabaseValues.decimal(safePrice.orElseThrow()));
                } else {
                    statement.setNull(1, Types.VARCHAR);
                }
                statement.setInt(2, purchaseConfirmed ? 1 : 0);
                statement.setInt(3, listingConfirmed ? 1 : 0);
                statement.setLong(4, at.toEpochMilli());
                statement.setString(5, executionId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Crash recovery is review-only: unfinished runs are never resumed automatically. */
    public CompletableFuture<Integer> markInterruptedExecutionsForReview(Instant at) {
        Objects.requireNonNull(at, "at");
        return database.transaction(connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT execution_id, state FROM trade_executions WHERE state NOT IN (" + TERMINAL_STATES + ")")) {
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        String id = result.getString("execution_id");
                        TradeExecutionState previous = TradeExecutionState.valueOf(result.getString("state"));
                        ids.add(id);
                        insertTransition(connection, id, previous,
                                TradeExecutionState.INTERRUPTED_REQUIRES_REVIEW,
                                "Client restarted during execution; manual review is required.", at);
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE trade_executions SET state = ?, status_message = ?, updated_at = ? WHERE state NOT IN ("
                            + TERMINAL_STATES + ")")) {
                update.setString(1, TradeExecutionState.INTERRUPTED_REQUIRES_REVIEW.name());
                update.setString(2, "Client restarted during execution; manual review is required.");
                update.setLong(3, at.toEpochMilli());
                update.executeUpdate();
            }
            return ids.size();
        });
    }

    public CompletableFuture<Optional<TradeExecutionEntity>> find(String executionId) {
        Objects.requireNonNull(executionId, "executionId");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM trade_executions WHERE execution_id = ?")) {
                statement.setString(1, executionId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<TradeExecutionTransitionEntity>> transitions(String executionId, int limit) {
        int safeLimit = RepositorySupport.positiveLimit(limit);
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT transition_id, execution_id, previous_state, new_state, message, transitioned_at
                    FROM trade_execution_transitions WHERE execution_id = ?
                    ORDER BY transitioned_at, transition_id LIMIT ?
                    """)) {
                statement.setString(1, executionId);
                statement.setInt(2, safeLimit);
                List<TradeExecutionTransitionEntity> values = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String previous = result.getString("previous_state");
                        values.add(new TradeExecutionTransitionEntity(
                                result.getLong("transition_id"), result.getString("execution_id"),
                                previous == null ? Optional.empty() : Optional.of(TradeExecutionState.valueOf(previous)),
                                TradeExecutionState.valueOf(result.getString("new_state")),
                                result.getString("message"),
                                Instant.ofEpochMilli(result.getLong("transitioned_at"))
                        ));
                    }
                }
                return List.copyOf(values);
            }
        });
    }

    private static void insertTransition(
            java.sql.Connection connection, String executionId, TradeExecutionState previous,
            TradeExecutionState current, String message, Instant at
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trade_execution_transitions(
                    execution_id, previous_state, new_state, message, transitioned_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, executionId);
            if (previous == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, previous.name());
            }
            statement.setString(3, current.name());
            statement.setString(4, message);
            statement.setLong(5, at.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static TradeExecutionEntity map(ResultSet result) throws Exception {
        String relist = result.getString("relist_price");
        return new TradeExecutionEntity(
                result.getString("execution_id"), result.getString("opportunity_id"),
                result.getString("listing_key"),
                com.example.donutflipscanner.automation.model.AutomationMode.valueOf(result.getString("mode")),
                TradeExecutionState.valueOf(result.getString("state")), result.getString("item_id"),
                result.getString("item_fingerprint"), result.getInt("expected_item_count"),
                DatabaseValues.requiredDecimal(result, "expected_listing_price"),
                relist == null ? Optional.empty() : Optional.of(new BigDecimal(relist)),
                result.getInt("purchase_confirmed") != 0, result.getInt("listing_confirmed") != 0,
                result.getString("status_message"), Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at"))
        );
    }
}
