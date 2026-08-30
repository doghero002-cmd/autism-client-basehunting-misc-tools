package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason;
import com.example.donutflipscanner.market.statistics.model.RejectedComparableSale;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OutlierFilter {
    private static final BigDecimal MODIFIED_Z_SCALE = new BigDecimal("0.6745");

    public Result filter(List<ComparableSale> input, MarketStatisticsConfig config) {
        List<ComparableSale> sales = List.copyOf(Objects.requireNonNull(input, "input"));
        Objects.requireNonNull(config, "config");
        if (sales.size() < config.outlierMinimumSample()) {
            return new Result(sales, List.of());
        }

        List<RejectedComparableSale> rejected = new ArrayList<>();
        List<ComparableSale> afterMad = filterMad(sales, config, rejected);
        List<ComparableSale> afterIqr = filterIqr(afterMad, config, rejected);
        return new Result(afterIqr, rejected);
    }

    private List<ComparableSale> filterMad(
            List<ComparableSale> input,
            MarketStatisticsConfig config,
            List<RejectedComparableSale> rejected
    ) {
        List<BigDecimal> prices = prices(input);
        BigDecimal median = StatisticalMath.median(prices);
        BigDecimal mad = StatisticalMath.medianAbsoluteDeviation(prices);
        if (mad.signum() == 0) {
            return input;
        }
        List<ComparableSale> accepted = new ArrayList<>();
        for (ComparableSale sale : input) {
            BigDecimal modifiedZ = sale.comparablePrice().subtract(median, StatisticalMath.MATH_CONTEXT)
                    .abs()
                    .multiply(MODIFIED_Z_SCALE, StatisticalMath.MATH_CONTEXT)
                    .divide(mad, StatisticalMath.MATH_CONTEXT);
            if (modifiedZ.compareTo(config.madModifiedZThreshold()) > 0) {
                rejected.add(rejection(sale, ComparableSaleRejectionReason.MAD_OUTLIER,
                        "Modified z-score " + modifiedZ.toPlainString()
                                + " exceeded " + config.madModifiedZThreshold().toPlainString()));
            } else {
                accepted.add(sale);
            }
        }
        return List.copyOf(accepted);
    }

    private List<ComparableSale> filterIqr(
            List<ComparableSale> input,
            MarketStatisticsConfig config,
            List<RejectedComparableSale> rejected
    ) {
        if (input.size() < config.outlierMinimumSample()) {
            return input;
        }
        List<BigDecimal> prices = prices(input);
        BigDecimal q1 = StatisticalMath.percentile(prices, new BigDecimal("0.25"));
        BigDecimal q3 = StatisticalMath.percentile(prices, new BigDecimal("0.75"));
        BigDecimal iqr = q3.subtract(q1, StatisticalMath.MATH_CONTEXT);
        BigDecimal spread = iqr.multiply(config.iqrMultiplier(), StatisticalMath.MATH_CONTEXT);
        BigDecimal lower = q1.subtract(spread, StatisticalMath.MATH_CONTEXT);
        BigDecimal upper = q3.add(spread, StatisticalMath.MATH_CONTEXT);
        List<ComparableSale> accepted = new ArrayList<>();
        for (ComparableSale sale : input) {
            if (sale.comparablePrice().compareTo(lower) < 0 || sale.comparablePrice().compareTo(upper) > 0) {
                rejected.add(rejection(sale, ComparableSaleRejectionReason.IQR_OUTLIER,
                        "Price was outside IQR bounds [" + lower.toPlainString()
                                + ", " + upper.toPlainString() + "]"));
            } else {
                accepted.add(sale);
            }
        }
        return List.copyOf(accepted);
    }

    private static RejectedComparableSale rejection(
            ComparableSale sale,
            ComparableSaleRejectionReason reason,
            String explanation
    ) {
        return new RejectedComparableSale(
                sale.saleKey(), reason, Optional.of(sale.comparablePrice()), explanation
        );
    }

    private static List<BigDecimal> prices(List<ComparableSale> sales) {
        return sales.stream().map(ComparableSale::comparablePrice).toList();
    }

    public record Result(List<ComparableSale> accepted, List<RejectedComparableSale> rejected) {
        public Result {
            accepted = List.copyOf(Objects.requireNonNull(accepted, "accepted"));
            rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
        }
    }
}
