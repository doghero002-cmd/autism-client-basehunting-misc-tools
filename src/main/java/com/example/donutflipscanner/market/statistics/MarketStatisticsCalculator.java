package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ActiveAskStatistics;
import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleSet;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.statistics.model.MarketDataStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MarketStatisticsCalculator {
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);

    private final ComparableSaleFinder comparableSaleFinder;
    private final ActiveAskCalculator activeAskCalculator;

    public MarketStatisticsCalculator() {
        this(new ComparableSaleFinder(), new ActiveAskCalculator());
    }

    public MarketStatisticsCalculator(
            ComparableSaleFinder comparableSaleFinder,
            ActiveAskCalculator activeAskCalculator
    ) {
        this.comparableSaleFinder = Objects.requireNonNull(comparableSaleFinder, "comparableSaleFinder");
        this.activeAskCalculator = Objects.requireNonNull(activeAskCalculator, "activeAskCalculator");
    }

    public ItemMarketStatistics calculate(
            NormalizedItem item,
            List<SaleEntity> sales,
            List<ListingEntity> activeListings,
            MarketStatisticsConfig config,
            Instant now
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(sales, "sales");
        Objects.requireNonNull(activeListings, "activeListings");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");

        ComparableSaleSet comparableSet = comparableSaleFinder.find(item, sales, config, now);
        ActiveAskStatistics asks = activeAskCalculator.calculate(item, activeListings);
        List<ComparableSale> comparables = comparableSet.accepted();
        List<BigDecimal> prices = comparables.stream().map(ComparableSale::comparablePrice).toList();
        Optional<Instant> mostRecent = comparables.stream()
                .map(ComparableSale::soldAt)
                .max(Comparator.naturalOrder());
        Optional<Duration> age = mostRecent.map(time -> Duration.between(time, now));
        boolean unsupported = item.matchQuality().matchType() == ItemMatchType.UNSUPPORTED;
        boolean lowData = comparables.size() < config.minimumComparableSales();
        boolean stale = age.map(value -> value.compareTo(config.staleAfter()) > 0).orElse(false);
        MarketDataStatus status = status(unsupported, comparables.isEmpty(), lowData, stale);

        Optional<BigDecimal> minimum = optional(prices, values -> values.stream().min(Comparator.naturalOrder()).orElseThrow());
        Optional<BigDecimal> maximum = optional(prices, values -> values.stream().max(Comparator.naturalOrder()).orElseThrow());
        Optional<BigDecimal> mean = optional(prices, StatisticalMath::mean);
        Optional<BigDecimal> median = optional(prices, StatisticalMath::median);
        Optional<BigDecimal> weightedMedian = comparables.isEmpty()
                ? Optional.empty() : Optional.of(StatisticalMath.weightedMedian(comparables));
        Optional<BigDecimal> p25 = optional(prices,
                values -> StatisticalMath.percentile(values, new BigDecimal("0.25")));
        Optional<BigDecimal> p40 = optional(prices,
                values -> StatisticalMath.percentile(values, new BigDecimal("0.40")));
        Optional<BigDecimal> p75 = optional(prices,
                values -> StatisticalMath.percentile(values, new BigDecimal("0.75")));
        Optional<BigDecimal> iqr = p25.flatMap(first -> p75.map(third ->
                third.subtract(first, StatisticalMath.MATH_CONTEXT)));
        Optional<BigDecimal> mad = optional(prices, StatisticalMath::medianAbsoluteDeviation);
        Optional<BigDecimal> standardDeviation = optional(prices, StatisticalMath::populationStandardDeviation);
        Optional<BigDecimal> coefficientOfVariation = mean.flatMap(average ->
                average.signum() == 0 ? Optional.empty() : standardDeviation.map(deviation ->
                        deviation.divide(average, StatisticalMath.MATH_CONTEXT)));
        Optional<BigDecimal> hours = observationHours(comparables, config, now);
        Optional<BigDecimal> salesPerHour = hours.map(value -> BigDecimal.valueOf(comparables.size())
                .divide(value, StatisticalMath.MATH_CONTEXT));
        Optional<BigDecimal> salesPerDay = salesPerHour.map(value ->
                value.multiply(HOURS_PER_DAY, StatisticalMath.MATH_CONTEXT));

        return new ItemMarketStatistics(
                item.fingerprint().sha256(),
                config.lookback(),
                sales.size(),
                comparables.size(),
                comparableSet.rejected().size(),
                uniqueIdentityCount(comparables, true),
                uniqueIdentityCount(comparables, false),
                minimum,
                maximum,
                mean,
                median,
                weightedMedian,
                p25,
                p40,
                p75,
                iqr,
                mad,
                standardDeviation,
                coefficientOfVariation,
                salesPerHour,
                salesPerDay,
                mostRecent,
                age,
                lowData,
                stale,
                status,
                explanation(status, comparables.size(), config.minimumComparableSales(), stale),
                comparableSet,
                asks,
                now
        );
    }

    private static Optional<BigDecimal> observationHours(
            List<ComparableSale> comparables,
            MarketStatisticsConfig config,
            Instant now
    ) {
        if (comparables.isEmpty()) {
            return Optional.empty();
        }
        Duration duration = config.lookback().duration().orElseGet(() -> {
            Instant oldest = comparables.stream().map(ComparableSale::soldAt)
                    .min(Comparator.naturalOrder()).orElseThrow();
            Duration observed = Duration.between(oldest, now);
            return observed.compareTo(Duration.ofHours(1)) < 0 ? Duration.ofHours(1) : observed;
        });
        BigDecimal hours = BigDecimal.valueOf(Math.max(1, duration.getSeconds()))
                .divide(SECONDS_PER_HOUR, StatisticalMath.MATH_CONTEXT);
        return Optional.of(hours);
    }

    private static int uniqueIdentityCount(List<ComparableSale> sales, boolean sellers) {
        return (int) sales.stream()
                .map(sale -> sellers ? sale.sellerIdentity() : sale.buyerIdentity())
                .flatMap(Optional::stream)
                .distinct()
                .count();
    }

    private static MarketDataStatus status(boolean unsupported, boolean empty, boolean lowData, boolean stale) {
        if (unsupported) {
            return MarketDataStatus.UNSUPPORTED;
        }
        if (empty) {
            return MarketDataStatus.EMPTY;
        }
        if (lowData) {
            return MarketDataStatus.LOW_DATA;
        }
        return stale ? MarketDataStatus.STALE : MarketDataStatus.SUFFICIENT;
    }

    private static String explanation(MarketDataStatus status, int found, int required, boolean stale) {
        return switch (status) {
            case UNSUPPORTED -> "This item cannot be matched safely; no market statistics were produced.";
            case EMPTY -> "No comparable completed sales were found in the selected lookback window.";
            case LOW_DATA -> "Only " + found + " comparable sales found; minimum required: " + required
                    + (stale ? ". The newest comparable is also stale." : ".");
            case STALE -> "Comparable history meets the sample requirement, but the newest sale is stale.";
            case SUFFICIENT -> "Comparable completed-sale history meets the configured data requirements.";
        };
    }

    private static Optional<BigDecimal> optional(
            List<BigDecimal> values,
            java.util.function.Function<List<BigDecimal>, BigDecimal> calculation
    ) {
        return values.isEmpty() ? Optional.empty() : Optional.of(calculation.apply(values));
    }
}
