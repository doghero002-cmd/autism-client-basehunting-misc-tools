package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.autism.seedcracker.flip.core.FlipModel.MarketStats;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.autism.seedcracker.flip.core.FlipModel.StackBucket;
import com.autism.seedcracker.flip.core.RobustStats.OutlierSplit;
import com.autism.seedcracker.flip.core.RobustStats.WeightedValue;

/**
 * Turns completed sales into {@link MarketStats}. Strictly chronological: only sales at or before
 * {@code asOf} count, so a replay can't see the future.
 */
public final class MarketAnalyzer {

    public record Config(
        long halfLifeMillis,
        long windowMillis,
        long liquidityWindowMillis,
        double quickSalePercentile,
        double patientSalePercentile,
        double trendSplitFraction
    ) {
        public static Config defaults() {
            return new Config(6 * HOUR, 7 * 24 * HOUR, 3 * HOUR, 0.375, 0.625, 0.25);
        }

        public Config withHalfLifeHours(int hours) {
            return new Config(Math.max(1, hours) * HOUR, windowMillis, liquidityWindowMillis,
                quickSalePercentile, patientSalePercentile, trendSplitFraction);
        }
    }

    public record Analysis(MarketStats stats, List<Sale> keptSales, List<Sale> outliers) {
        public Analysis {
            keptSales = List.copyOf(keptSales);
            outliers = List.copyOf(outliers);
        }
    }

    static final long HOUR = 3_600_000L;

    private final Config config;

    public MarketAnalyzer(Config config) {
        this.config = config;
    }

    public Analysis analyze(String itemKey, StackBucket bucket, List<Sale> sales, long asOf) {
        List<Sale> inScope = new ArrayList<>();
        for (Sale s : sales) {
            if (!s.isValid() || !s.itemKey().equals(itemKey)) continue;
            if (s.soldAt() > asOf || asOf - s.soldAt() > config.windowMillis()) continue;
            if (s.bucket() != bucket) continue;
            inScope.add(s);
        }

        // Unit prices, so a lucky x64 sale can't masquerade as a normal x16 price.
        OutlierSplit<Sale> split = RobustStats.splitOutliersByLogPrice(inScope, Sale::unitPrice);
        List<Sale> kept = split.kept();
        if (kept.isEmpty()) return new Analysis(emptyStats(itemKey, bucket, asOf, split.outliers().size()), List.of(), split.outliers());

        List<WeightedValue> weighted = new ArrayList<>(kept.size());
        Set<String> sellers = new HashSet<>();
        long newestSaleAt = 0;
        int inferred = 0;
        int unknownSellers = 0;
        for (Sale s : kept) {
            weighted.add(new WeightedValue(stackPrice(s, bucket), RobustStats.recencyWeight(asOf - s.soldAt(), config.halfLifeMillis())));
            if (unknownSeller(s.seller())) unknownSellers++;
            else sellers.add(s.seller());
            newestSaleAt = Math.max(newestSaleAt, s.soldAt());
            if (s.inferred()) inferred++;
        }
        // Unknown sellers count as a neutral 4 (half seller credit) rather than one wash-trader.
        int sellerCount = sellers.size() + (unknownSellers > 0 ? Math.min(unknownSellers, 4) : 0);

        double p25 = RobustStats.weightedPercentile(weighted, 0.25);
        double quick = RobustStats.weightedPercentile(weighted, config.quickSalePercentile());
        double median = RobustStats.weightedPercentile(weighted, 0.50);
        double patient = RobustStats.weightedPercentile(weighted, config.patientSalePercentile());
        double p75 = RobustStats.weightedPercentile(weighted, 0.75);

        // MAD can't see a two-price market once one cluster passes half the weight; the quartile spread can.
        double volatility = Math.max(volatility(kept), spreadVolatility(p25, p75));
        double trend = trend(kept, asOf);
        double confidence = confidence(kept.size(), split.outliers().size(), sellerCount, volatility, asOf - newestSaleAt);

        MarketStats stats = new MarketStats(itemKey, bucket, kept.size(), split.outliers().size(), sellerCount,
            newestSaleAt, p25, quick, median, patient, p75, salesPerHour(kept, asOf), volatility, trend,
            confidence, (double) inferred / kept.size(), asOf);
        return new Analysis(stats, kept, split.outliers());
    }

    /** GUI lore without a seller line yields "?". */
    static boolean unknownSeller(String seller) {
        return seller == null || seller.isEmpty() || "?".equals(seller);
    }

    private static double stackPrice(Sale s, StackBucket bucket) {
        return bucket == StackBucket.OTHER ? s.unitPrice() : s.totalPrice();
    }

    private double salesPerHour(List<Sale> kept, long asOf) {
        long windowStart = asOf - config.liquidityWindowMillis();
        long count = kept.stream().filter(s -> s.soldAt() >= windowStart).count();
        return count / (config.liquidityWindowMillis() / (double) HOUR);
    }

    /** Quartile-spread scatter; 1.349 = IQR of a normal in sigmas, same units as the MAD estimate. */
    static double spreadVolatility(double p25, double p75) {
        if (!(p25 > 0) || !(p75 > 0) || p75 <= p25) return 0.0;
        double scatter = Math.expm1(Math.log(p75 / p25) / 1.349);
        return Double.isFinite(scatter) ? Math.max(0.0, scatter) : 0.0;
    }

    private static double volatility(List<Sale> kept) {
        List<Double> prices = new ArrayList<>(kept.size());
        for (Sale s : kept) prices.add(s.unitPrice());
        double v = RobustStats.robustLogVolatility(prices);
        return Double.isNaN(v) ? 0.0 : v;
    }

    /** Recent vs older median drift; -0.10 = recent prices run 10% lower. */
    private double trend(List<Sale> kept, long asOf) {
        long recentCutoff = asOf - (long) (config.windowMillis() * config.trendSplitFraction());
        List<Double> recent = new ArrayList<>();
        List<Double> older = new ArrayList<>();
        for (Sale s : kept) (s.soldAt() >= recentCutoff ? recent : older).add(s.unitPrice());
        if (recent.size() < 3 || older.size() < 3) return 0.0;
        double olderMedian = RobustStats.median(older);
        return olderMedian <= 0 ? 0.0 : RobustStats.median(recent) / olderMedian - 1.0;
    }

    /**
     * 0..1: grows with samples and seller diversity, shrinks with volatility, staleness and the
     * share of sales the outlier filter had to discard (tightness bought by discarding isn't earned).
     */
    static double confidence(int samples, int outliers, int uniqueSellers, double volatility, long newestSaleAgeMillis) {
        double sampleFactor = Math.min(1.0, samples / 30.0);
        double sellerFactor = Math.min(1.0, uniqueSellers / 8.0);
        double volatilityFactor = 1.0 / (1.0 + 6.0 * Math.max(0, volatility));
        double freshnessFactor = 1.0 / (1.0 + Math.max(0, newestSaleAgeMillis) / (double) HOUR / 4.0);
        int observed = Math.max(0, samples) + Math.max(0, outliers);
        double discarded = observed == 0 ? 0.0 : (double) Math.max(0, outliers) / observed;
        double agreementFactor = 1.0 / (1.0 + 4.0 * discarded);
        return Math.max(0.0, Math.min(1.0,
            sampleFactor * (0.5 + 0.5 * sellerFactor) * volatilityFactor * freshnessFactor * agreementFactor));
    }

    private MarketStats emptyStats(String itemKey, StackBucket bucket, long asOf, int outliers) {
        return new MarketStats(itemKey, bucket, 0, outliers, 0, 0,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, 0, 0, asOf);
    }
}
