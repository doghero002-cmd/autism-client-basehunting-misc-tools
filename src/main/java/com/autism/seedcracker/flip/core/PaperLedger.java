package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import com.autism.seedcracker.flip.core.FlipModel.Sale;

/**
 * Leak-free simulated trading: a flip only "sells" once a LATER real sale of the same market
 * cleared at or above the target. Lets you see whether the strategy would have paid before any
 * real coins move. Also backs auto-bench: markets whose settled paper flips lose get skipped.
 */
public final class PaperLedger {

    public enum Status { OPEN, SOLD, EXPIRED }

    public static final class Position {
        public final String itemKey;
        public final FlipModel.StackBucket bucket;
        public final int count;
        public final long buyPrice;
        public final long targetPrice;
        public final long openedAt;
        public Status status = Status.OPEN;
        public long closedAt;
        public double netProfit;

        Position(String itemKey, FlipModel.StackBucket bucket, int count, long buyPrice, long targetPrice, long openedAt) {
            this.itemKey = itemKey;
            this.bucket = bucket;
            this.count = count;
            this.buyPrice = buyPrice;
            this.targetPrice = targetPrice;
            this.openedAt = openedAt;
        }
    }

    public record Summary(int open, int sold, int expired, double realizedProfit, double winRate) {}

    private final List<Position> positions = new ArrayList<>();
    private final OpportunityDetector.FeeConfig fees;
    private final long expiryMillis;
    private final int maxOpen;

    public PaperLedger(OpportunityDetector.FeeConfig fees, long expiryMillis, int maxOpen) {
        this.fees = fees;
        this.expiryMillis = expiryMillis;
        this.maxOpen = maxOpen;
    }

    /** Opens a paper position unless the same listing is already held or slots are full. */
    public synchronized boolean open(Opportunity o, long now) {
        if (openCount() >= maxOpen) return false;
        for (Position p : positions) {
            if (p.status == Status.OPEN && p.itemKey.equals(o.listing().itemKey())
                && p.count == o.listing().count() && p.buyPrice == o.buyPrice()) return false;
        }
        positions.add(new Position(o.listing().itemKey(), o.listing().bucket(), o.listing().count(),
            o.buyPrice(), o.sellPrice(), now));
        return true;
    }

    /** Settles open positions against sales that happened strictly after they were opened. */
    public synchronized void settle(List<Sale> sales, long now) {
        for (Position p : positions) {
            if (p.status != Status.OPEN) continue;
            double targetUnit = (double) p.targetPrice / p.count;
            for (Sale s : sales) {
                if (!s.itemKey().equals(p.itemKey) || s.bucket() != p.bucket || s.soldAt() <= p.openedAt) continue;
                if (s.unitPrice() >= targetUnit * 0.99) {
                    p.status = Status.SOLD;
                    p.closedAt = s.soldAt();
                    p.netProfit = fees.netSale(p.targetPrice) - p.buyPrice;
                    break;
                }
            }
            if (p.status == Status.OPEN && now - p.openedAt > expiryMillis) {
                p.status = Status.EXPIRED;
                p.closedAt = now;
                // Stuck stock: assume a fire sale at 85% of cost.
                p.netProfit = -0.15 * p.buyPrice;
            }
        }
    }

    /** Markets whose settled paper flips (at least {@code minSettled}) net a loss inside the window. */
    public synchronized java.util.Set<String> losingMarkets(int minSettled, long windowMillis, long now) {
        Map<String, double[]> agg = new HashMap<>();
        for (Position p : positions) {
            if (p.status == Status.OPEN || now - p.closedAt > windowMillis) continue;
            double[] a = agg.computeIfAbsent(p.itemKey, k -> new double[2]);
            a[0] += 1;
            a[1] += p.netProfit;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (var e : agg.entrySet()) {
            if (e.getValue()[0] >= Math.max(1, minSettled) && e.getValue()[1] < 0) out.add(e.getKey());
        }
        return out;
    }

    public synchronized Summary summary() {
        int open = 0, sold = 0, expired = 0;
        double profit = 0;
        for (Position p : positions) {
            switch (p.status) {
                case OPEN -> open++;
                case SOLD -> { sold++; profit += p.netProfit; }
                case EXPIRED -> { expired++; profit += p.netProfit; }
            }
        }
        int settled = sold + expired;
        return new Summary(open, sold, expired, profit, settled == 0 ? Double.NaN : (double) sold / settled);
    }

    public synchronized List<Position> positions() {
        return List.copyOf(positions);
    }

    private int openCount() {
        int n = 0;
        for (Position p : positions) if (p.status == Status.OPEN) n++;
        return n;
    }
}
