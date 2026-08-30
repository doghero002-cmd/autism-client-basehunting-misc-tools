package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.profit.PersonalProfitPoint;
import com.example.donutflipscanner.profit.PersonalProfitSnapshot;
import com.example.donutflipscanner.profit.PlayerIdentity;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Persistent cost-basis ledger for scanner opportunities explicitly confirmed as purchased. */
public final class PersonalProfitRepository {
    private final DatabaseManager database;

    public PersonalProfitRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Atomically confirms a still-current opportunity and opens its cost-basis position.
     * Repeated confirmation of the same opportunity is idempotent.
     */
    public CompletableFuture<Boolean> confirmPurchase(
            String opportunityId,
            Instant purchasedAt,
            Instant verifiedAfter
    ) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(purchasedAt, "purchasedAt");
        Objects.requireNonNull(verifiedAfter, "verifiedAfter");
        return database.transaction(connection -> {
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT 1 FROM tracked_flip_positions WHERE opportunity_id = ?")) {
                existing.setString(1, opportunityId);
                try (ResultSet result = existing.executeQuery()) {
                    if (result.next()) {
                        return true;
                    }
                }
            }

            PurchaseCandidate candidate;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT o.listing_key, o.item_fingerprint, o.purchase_price, o.state,
                           l.raw_item_id, l.item_count
                    FROM opportunities AS o
                    JOIN auction_listings AS l ON l.listing_key = o.listing_key
                    WHERE o.opportunity_id = ?
                      AND o.state IN ('NEW', 'REVIEWED')
                      AND l.state = 'ACTIVE'
                      AND l.last_seen_at >= ?
                    """)) {
                select.setString(1, opportunityId);
                select.setLong(2, verifiedAfter.toEpochMilli());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        return false;
                    }
                    candidate = new PurchaseCandidate(
                            result.getString("listing_key"),
                            result.getString("item_fingerprint"),
                            result.getString("raw_item_id"),
                            result.getInt("item_count"),
                            new BigDecimal(result.getString("purchase_price")),
                            result.getString("state")
                    );
                }
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO tracked_flip_positions(
                        position_id, opportunity_id, listing_key, item_fingerprint,
                        raw_item_id, item_count, acquisition_cost, purchased_at,
                        status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                    """)) {
                insert.setString(1, opportunityId);
                insert.setString(2, opportunityId);
                insert.setString(3, candidate.listingKey());
                insert.setString(4, candidate.itemFingerprint());
                insert.setString(5, candidate.rawItemId());
                insert.setInt(6, candidate.itemCount());
                insert.setString(7, candidate.acquisitionCost().toPlainString());
                insert.setLong(8, purchasedAt.toEpochMilli());
                insert.setLong(9, purchasedAt.toEpochMilli());
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE opportunities SET state = 'PURCHASED_MANUALLY' WHERE opportunity_id = ?")) {
                update.setString(1, opportunityId);
                update.executeUpdate();
            }
            try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO opportunity_state_changes(
                        opportunity_id, previous_state, new_state, changed_at, reason
                    ) VALUES (?, ?, 'PURCHASED_MANUALLY', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                history.setString(1, opportunityId);
                history.setString(2, candidate.previousState());
                history.setLong(3, purchasedAt.toEpochMilli());
                history.setString(4, "Purchase confirmed by user for personal profit tracking");
                history.executeUpdate();
            }
            return true;
        });
    }

    /** Matches only the player's exact fingerprint/count sales, oldest confirmed lot first. */
    public CompletableFuture<Integer> reconcileSales(PlayerIdentity identity, List<SaleEntity> sales) {
        Objects.requireNonNull(identity, "identity");
        List<SaleEntity> matching = Objects.requireNonNull(sales, "sales").stream()
                .filter(identity::matches)
                .sorted(Comparator.comparing(SaleEntity::soldAt).thenComparing(SaleEntity::saleKey))
                .toList();
        return database.transaction(connection -> {
            int realized = 0;
            for (SaleEntity sale : matching) {
                if (realize(connection, sale)) {
                    realized++;
                }
            }
            return realized;
        });
    }

    /** Reconciles persisted API sales on startup, healing interruptions between ingestion and matching. */
    public CompletableFuture<Integer> reconcileStoredSales(PlayerIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return database.transaction(connection -> {
            List<SaleEntity> sales = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sale_key, seller_uuid, seller_name, item_fingerprint,
                           raw_item_id, item_count, sale_price, sold_at, imported_at
                    FROM completed_sales
                    WHERE sold_at >= COALESCE(
                        (SELECT MIN(purchased_at) FROM tracked_flip_positions WHERE status = 'OPEN'),
                        9223372036854775807
                    )
                    ORDER BY sold_at, sale_key
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        SaleEntity sale = new SaleEntity(
                                result.getString("sale_key"), Optional.empty(),
                                Optional.ofNullable(result.getString("seller_uuid")),
                                Optional.ofNullable(result.getString("seller_name")),
                                Optional.empty(), Optional.empty(),
                                result.getString("item_fingerprint"), result.getString("raw_item_id"),
                                result.getInt("item_count"), new BigDecimal(result.getString("sale_price")),
                                Optional.empty(), Instant.ofEpochMilli(result.getLong("sold_at")),
                                Instant.ofEpochMilli(result.getLong("imported_at")), Optional.empty()
                        );
                        if (identity.matches(sale)) {
                            sales.add(sale);
                        }
                    }
                }
            }
            int realized = 0;
            for (SaleEntity sale : sales) {
                if (realize(connection, sale)) {
                    realized++;
                }
            }
            return realized;
        });
    }

    public CompletableFuture<PersonalProfitSnapshot> snapshot(Instant refreshedAt) {
        Objects.requireNonNull(refreshedAt, "refreshedAt");
        return database.execute(connection -> {
            int openPositions;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM tracked_flip_positions WHERE status = 'OPEN'");
                 ResultSet result = statement.executeQuery()) {
                openPositions = result.next() ? result.getInt(1) : 0;
            }
            BigDecimal acquisition = BigDecimal.ZERO;
            BigDecimal proceeds = BigDecimal.ZERO;
            BigDecimal cumulative = BigDecimal.ZERO;
            List<PersonalProfitPoint> points = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT acquisition_cost, sale_proceeds, realized_profit, sold_at
                    FROM tracked_flip_positions
                    WHERE status = 'REALIZED'
                    ORDER BY sold_at, position_id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    acquisition = acquisition.add(new BigDecimal(result.getString("acquisition_cost")));
                    proceeds = proceeds.add(new BigDecimal(result.getString("sale_proceeds")));
                    cumulative = cumulative.add(new BigDecimal(result.getString("realized_profit")));
                    points.add(new PersonalProfitPoint(
                            Instant.ofEpochMilli(result.getLong("sold_at")), cumulative
                    ));
                }
            }
            return new PersonalProfitSnapshot(
                    cumulative, acquisition, proceeds, openPositions, points.size(),
                    points, Optional.of(refreshedAt)
            );
        });
    }

    private static boolean realize(java.sql.Connection connection, SaleEntity sale) throws Exception {
        try (PreparedStatement used = connection.prepareStatement(
                "SELECT 1 FROM tracked_flip_positions WHERE matched_sale_key = ?")) {
            used.setString(1, sale.saleKey());
            try (ResultSet result = used.executeQuery()) {
                if (result.next()) {
                    return false;
                }
            }
        }
        String positionId;
        BigDecimal cost;
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT position_id, acquisition_cost
                FROM tracked_flip_positions
                WHERE status = 'OPEN'
                  AND item_fingerprint = ?
                  AND item_count = ?
                  AND purchased_at <= ?
                ORDER BY purchased_at, position_id
                LIMIT 1
                """)) {
            select.setString(1, sale.itemFingerprint());
            select.setInt(2, sale.itemCount());
            select.setLong(3, sale.soldAt().toEpochMilli());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                positionId = result.getString("position_id");
                cost = new BigDecimal(result.getString("acquisition_cost"));
            }
        }
        BigDecimal profit = sale.salePrice().subtract(cost);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE tracked_flip_positions
                SET status = 'REALIZED', matched_sale_key = ?, sale_proceeds = ?,
                    realized_profit = ?, sold_at = ?
                WHERE position_id = ? AND status = 'OPEN'
                """)) {
            update.setString(1, sale.saleKey());
            update.setString(2, sale.salePrice().toPlainString());
            update.setString(3, profit.toPlainString());
            update.setLong(4, sale.soldAt().toEpochMilli());
            update.setString(5, positionId);
            return update.executeUpdate() == 1;
        }
    }

    private record PurchaseCandidate(
            String listingKey,
            String itemFingerprint,
            String rawItemId,
            int itemCount,
            BigDecimal acquisitionCost,
            String previousState
    ) {
    }
}
