package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.MarketStats;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.autism.seedcracker.flip.core.FlipModel.StackBucket;

/**
 * Given one listing, the market's sale history and the active competition: is buying now and
 * reselling conservatively likely to pay? Deterministic, and every rejection carries a reason.
 */
public final class OpportunityDetector {

    /** Auction costs; all zero until confirmed on the server. */
    public record FeeConfig(long listingFeeFlat, double listingFeePercent, double saleTaxPercent) {
        public static FeeConfig zero() {
            return new FeeConfig(0, 0.0, 0.0);
        }

        public double netSale(double gross) {
            return gross - listingFeeFlat - gross * listingFeePercent / 100.0 - gross * saleTaxPercent / 100.0;
        }
    }

    public record RiskConfig(
        int minimumSamples,
        long minimumProfit,
        double minimumRoiPercent,
        boolean skipFallingMarkets,
        double maximumVolatility,
        long maximumNewestSaleAgeMillis,
        double maximumExpectedHoldHours,
        double minimumConfidence,
        long undercutAmount,
        long maximumBuyPrice,
        int minimumAsks
    ) {
        public static RiskConfig defaults() {
            return new RiskConfig(20, 5_000, 12.0, true, 0.25, MarketAnalyzer.HOUR, 2.0, 0.5, 1, Long.MAX_VALUE, 6);
        }
    }

    public record Evaluation(Optional<Opportunity> opportunity, List<String> rejections) {
        public boolean accepted() {
            return opportunity.isPresent();
        }
    }

    private final FeeConfig fees;
    private final RiskConfig risk;
    private final ManipulationDetector manipulation = new ManipulationDetector();

    public OpportunityDetector(FeeConfig fees, RiskConfig risk) {
        this.fees = fees;
        this.risk = risk;
    }

    /** Sales-backed evaluation (API transactions or keyless inferred sales). */
    public Evaluation evaluate(Listing candidate, MarketStats stats, List<Sale> keptSales, List<Listing> active) {
        if (!candidate.isValid()) return reject("Malformed listing");
        if (!stats.hasPrices()) return reject("No usable sale history");
        List<String> rejections = new ArrayList<>();

        if (stats.sampleCount() < risk.minimumSamples()) {
            rejections.add("Only " + stats.sampleCount() + " sales (need " + risk.minimumSamples() + ")");
        }
        long newestAge = stats.calculatedAt() - stats.newestSaleAt();
        if (newestAge > risk.maximumNewestSaleAgeMillis()) rejections.add("Last sale " + newestAge / 60000 + "m ago");
        if (stats.robustVolatility() > risk.maximumVolatility()) {
            rejections.add(String.format(Locale.ROOT, "Volatility %.0f%% (max %.0f%%)",
                stats.robustVolatility() * 100, risk.maximumVolatility() * 100));
        }
        if (risk.skipFallingMarkets() && stats.trend() < -0.10) {
            rejections.add(String.format(Locale.ROOT, "Falling %.0f%%", -stats.trend() * 100));
        }
        if (candidate.totalPrice() > risk.maximumBuyPrice()) rejections.add("Over max buy price");

        double quickSale = stackTarget(stats.quickSalePrice(), stats, candidate);
        Optional<Listing> nextAsk = nextComparable(candidate, active);
        double recommended = quickSale;
        if (nextAsk.isPresent()) {
            recommended = Math.min(quickSale, comparableStackPrice(nextAsk.get(), candidate) - risk.undercutAmount());
        }
        long sell = (long) Math.floor(recommended);
        if (sell <= 0) return reject("No viable resale price");

        double profit = fees.netSale(sell) - candidate.totalPrice();
        double roi = profit / candidate.totalPrice() * 100.0;
        if (profit < risk.minimumProfit()) {
            rejections.add(String.format(Locale.ROOT, "Profit %.0f (need %d)", profit, risk.minimumProfit()));
        }
        if (roi < risk.minimumRoiPercent()) {
            rejections.add(String.format(Locale.ROOT, "ROI %.1f%% (need %.0f%%)", roi, risk.minimumRoiPercent()));
        }

        long ahead = active.stream().filter(l -> isComparable(l, candidate))
            .filter(l -> comparableStackPrice(l, candidate) <= sell).count();
        double holdHours = stats.salesPerHour() > 0 ? (ahead + 1) / stats.salesPerHour() : Double.POSITIVE_INFINITY;
        if (holdHours > risk.maximumExpectedHoldHours()) {
            rejections.add(String.format(Locale.ROOT, "Hold %.1fh (max %.0fh)", holdHours, risk.maximumExpectedHoldHours()));
        }

        // Asks only cap the target; confidence comes from completed sales alone.
        ManipulationDetector.Assessment m = manipulation.assess(stats, keptSales);
        double confidence = stats.confidence() * m.penalty();
        if (confidence < risk.minimumConfidence()) {
            rejections.add(String.format(Locale.ROOT, "Confidence %.0f%% (need %.0f%%)",
                confidence * 100, risk.minimumConfidence() * 100));
        }
        if (!rejections.isEmpty()) return new Evaluation(Optional.empty(), rejections);

        double probability = saleProbability(sell, stats, candidate);
        double liquidity = Math.min(1.0, stats.salesPerHour() / 6.0);
        double score = profit * probability * confidence * liquidity / candidate.totalPrice()
            / Math.max(0.05, holdHours) * m.penalty();

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format(Locale.ROOT, "%.0f%% below quick-sale value", (1.0 - candidate.totalPrice() / quickSale) * 100));
        reasons.add(String.format(Locale.ROOT, "%d sales in window (%.1f/h)", stats.sampleCount(), stats.salesPerHour()));
        reasons.add(String.format(Locale.ROOT, "Volatility %.1f%%, trend %+.1f%%", stats.robustVolatility() * 100, stats.trend() * 100));
        reasons.add(String.format(Locale.ROOT, "Resell at %d (%s)", sell,
            nextAsk.isPresent() && recommended < quickSale ? "undercut next ask" : "quick-sale value"));
        reasons.add(String.format(Locale.ROOT, "Hold ~%.0f min, net profit %.0f", holdHours * 60, profit));
        reasons.addAll(m.warnings());
        Basis basis = stats.inferredShare() >= 0.5 ? Basis.INFERRED : Basis.SALES;
        return new Evaluation(Optional.of(new Opportunity(candidate, stats, basis, candidate.totalPrice(), sell,
            profit, roi, holdHours, probability, confidence, score, reasons)), List.of());
    }

    /**
     * Thin-data fallback for keyless mode: value against the other live asks only. Asks are hope,
     * not proof, so confidence is capped low and callers should treat these as alerts only.
     */
    public Evaluation evaluateAgainstAsks(Listing candidate, List<Listing> active, long now) {
        if (!candidate.isValid()) return reject("Malformed listing");
        List<Double> others = new ArrayList<>();
        for (Listing l : active) if (isComparable(l, candidate)) others.add(comparableStackPrice(l, candidate));
        if (others.size() < risk.minimumAsks()) {
            return reject("Only " + others.size() + " other asks (need " + risk.minimumAsks() + ")");
        }
        others.sort(Comparator.naturalOrder());
        double floor = others.get(0);
        // Median of the cheapest third: one stale overpriced ask can't inflate the reference.
        double reference = RobustStats.median(others.subList(0, Math.max(1, others.size() / 3)));
        long sell = (long) Math.floor(floor - risk.undercutAmount());
        if (sell <= 0) return reject("No viable resale price");
        double profit = fees.netSale(sell) - candidate.totalPrice();
        double roi = profit / candidate.totalPrice() * 100.0;
        List<String> rejections = new ArrayList<>();
        if (candidate.totalPrice() > risk.maximumBuyPrice()) rejections.add("Over max buy price");
        if (profit < risk.minimumProfit()) rejections.add(String.format(Locale.ROOT, "Profit %.0f (need %d)", profit, risk.minimumProfit()));
        if (roi < risk.minimumRoiPercent()) rejections.add(String.format(Locale.ROOT, "ROI %.1f%% (need %.0f%%)", roi, risk.minimumRoiPercent()));
        if (!rejections.isEmpty()) return new Evaluation(Optional.empty(), rejections);

        List<Double> units = new ArrayList<>();
        for (double p : others) units.add(p);
        double spread = RobustStats.robustLogVolatility(units);
        double confidence = Math.min(0.35, 0.1 + others.size() / 60.0) / (1.0 + 4.0 * (Double.isNaN(spread) ? 0.5 : spread));
        double score = profit * confidence / candidate.totalPrice();
        List<String> reasons = List.of(
            String.format(Locale.ROOT, "%.0f%% under the cheapest other ask", (1.0 - candidate.totalPrice() / floor) * 100),
            String.format(Locale.ROOT, "%d other asks, low-third median %.0f", others.size(), reference),
            "Ask-based only: no sale history yet, alert only");
        MarketStats pseudo = new MarketStats(candidate.itemKey(), candidate.bucket(), 0, 0, 0, 0,
            floor, floor, reference, reference, others.get(others.size() - 1), 0, Double.isNaN(spread) ? 0 : spread,
            0, confidence, 0, now);
        return new Evaluation(Optional.of(new Opportunity(candidate, pseudo, Basis.ASKS, candidate.totalPrice(), sell,
            profit, roi, Double.NaN, 0.5, confidence, score, reasons)), List.of());
    }

    private static double stackTarget(double bandPrice, MarketStats stats, Listing candidate) {
        return stats.bucket() == StackBucket.OTHER ? bandPrice * candidate.count() : bandPrice;
    }

    /** A competitor's price expressed on the candidate's stack size. */
    static double comparableStackPrice(Listing competitor, Listing candidate) {
        return competitor.count() == candidate.count() ? competitor.totalPrice() : competitor.unitPrice() * candidate.count();
    }

    private static boolean isComparable(Listing l, Listing candidate) {
        if (l == null || !l.isValid() || l == candidate) return false;
        if (!l.itemKey().equals(candidate.itemKey()) || l.bucket() != candidate.bucket()) return false;
        return candidate.listingKey() == null || !candidate.listingKey().equals(l.listingKey());
    }

    private static Optional<Listing> nextComparable(Listing candidate, List<Listing> active) {
        return active.stream().filter(l -> isComparable(l, candidate))
            .min(Comparator.comparingDouble(l -> comparableStackPrice(l, candidate)));
    }

    /** Below quick-sale in a liquid market is very likely to fill; above fair value in a slow one is not. */
    private static double saleProbability(long sell, MarketStats stats, Listing candidate) {
        double quick = stackTarget(stats.quickSalePrice(), stats, candidate);
        double fair = stackTarget(stats.weightedMedian(), stats, candidate);
        double priceFactor = sell <= quick ? 0.9 : sell <= fair ? 0.75 : 0.55;
        double liquidity = Math.min(1.0, 0.5 + stats.salesPerHour() / 12.0);
        return Math.max(0.05, Math.min(0.98, priceFactor * liquidity));
    }

    private static Evaluation reject(String reason) {
        return new Evaluation(Optional.empty(), List.of(reason));
    }
}
