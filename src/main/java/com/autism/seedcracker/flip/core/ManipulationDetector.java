package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.autism.seedcracker.flip.core.FlipModel.MarketStats;
import com.autism.seedcracker.flip.core.FlipModel.Sale;

/**
 * Heuristics for markets that look engineered. Sales don't expose buyers, so wash trading can't
 * be proven: these checks only ever lower confidence, never raise it.
 */
public final class ManipulationDetector {

    /** {@code penalty} is 1.0 when clean; multiply it into confidence. */
    public record Assessment(double penalty, List<String> warnings) {}

    public Assessment assess(MarketStats stats, List<Sale> kept) {
        List<String> warnings = new ArrayList<>();
        double penalty = 1.0;

        Map<String, Integer> bySeller = new HashMap<>();
        int known = 0;
        for (Sale s : kept) {
            if (MarketAnalyzer.unknownSeller(s.seller())) continue;
            bySeller.merge(s.seller(), 1, Integer::sum);
            known++;
        }
        // Unreadable sellers can't prove concentration either way, so only judge the known ones.
        if (known >= 5) {
            int top = bySeller.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double topShare = (double) top / known;
            if (bySeller.size() >= 2 && topShare > 0.6) {
                warnings.add(String.format(Locale.ROOT, "One seller made %.0f%% of sales", topShare * 100));
                penalty *= 0.6;
            }
            if (bySeller.size() == 1) {
                warnings.add("All sales come from one seller");
                penalty *= 0.4;
            }
        }

        if (stats.trend() > 0.30 && stats.salesPerHour() < 2.0) {
            warnings.add(String.format(Locale.ROOT, "Price up %.0f%% on low volume", stats.trend() * 100));
            penalty *= 0.6;
        }

        int total = stats.sampleCount() + stats.outlierCount();
        if (total > 0 && (double) stats.outlierCount() / total > 0.25) {
            warnings.add("High share of outlier-priced sales");
            penalty *= 0.7;
        }

        long newestAge = stats.calculatedAt() - stats.newestSaleAt();
        if (stats.newestSaleAt() > 0 && newestAge > 6L * MarketAnalyzer.HOUR) {
            warnings.add("Newest sale is over 6 hours old");
            penalty *= 0.5;
        }

        // Keyless mode: a vanished listing may have been cancelled, not bought.
        if (stats.inferredShare() > 0) {
            warnings.add(String.format(Locale.ROOT, "%.0f%% of sales inferred from vanished listings",
                stats.inferredShare() * 100));
            penalty *= 1.0 - 0.4 * stats.inferredShare();
        }
        return new Assessment(penalty, warnings);
    }
}
