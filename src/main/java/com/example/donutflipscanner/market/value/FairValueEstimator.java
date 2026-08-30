package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.StatisticalMath;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FairValueEstimator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public FairValueEstimate estimate(
            NormalizedItem item,
            FairValueMarketContext market,
            FairValueConfig config
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(config, "config");
        if (!item.fingerprint().sha256().equals(market.primary().itemFingerprint())) {
            throw new IllegalArgumentException("normalized item and market fingerprint must match");
        }
        if (market.primary().lookback() != config.primaryLookback()
                || market.recent().lookback() != config.recentLookback()
                || market.longTerm().lookback() != config.longTermLookback()) {
            throw new IllegalArgumentException("market snapshots do not match the configured fair-value windows");
        }

        ItemMarketStatistics primary = market.primary();
        boolean unitPriceBased = item.matchQuality().matchType().unitPriceBased();
        int targetCount = unitPriceBased ? item.stackCount().orElse(1) : 1;
        BigDecimal totalMultiplier = BigDecimal.valueOf(targetCount);
        Set<FairValueWarning> warnings = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        List<FairValueAdjustment> adjustments = new ArrayList<>();

        TrendResult trendResult = detectTrend(market.recent(), market.longTerm(), config);
        MarketTrend trend = trendResult.trend();
        if (trend == MarketTrend.FALLING) {
            warnings.add(FairValueWarning.FALLING_MARKET);
        }
        if (trendResult.rapidRise()) {
            warnings.add(FairValueWarning.RAPID_RISE);
        }

        AskResult ask = evaluateSecondAsk(primary, config);
        if (ask.status() == ActiveAskEvidenceStatus.REJECTED_BELOW_COMPLETED_RANGE) {
            warnings.add(FairValueWarning.ACTIVE_ASK_BELOW_COMPLETED_RANGE);
        } else if (ask.status() == ActiveAskEvidenceStatus.REJECTED_ABOVE_COMPLETED_RANGE) {
            warnings.add(FairValueWarning.ACTIVE_ASK_ABOVE_COMPLETED_RANGE);
        }

        boolean highVolatility = primary.coefficientOfVariation()
                .map(value -> value.compareTo(config.highVolatilityCoefficientThreshold()) > 0)
                .orElse(false);
        if (highVolatility) {
            warnings.add(FairValueWarning.HIGH_VOLATILITY);
        }
        boolean highSupply = isHighSupply(primary, config);
        if (highSupply) {
            warnings.add(FairValueWarning.HIGH_ACTIVE_SUPPLY);
        }

        boolean supported = item.matchQuality().matchType() != ItemMatchType.UNSUPPORTED;
        boolean validCommodityCount = !unitPriceBased || item.stackCount().isPresent();
        boolean enoughSales = primary.comparableSaleCount() >= config.minimumCompletedSales();
        boolean requiredStatisticsPresent = primary.weightedMedian().isPresent()
                && primary.percentile40().isPresent()
                && primary.median().isPresent()
                && primary.percentile75().isPresent();
        boolean freshEnough = !config.rejectStalePrimaryData() || !primary.stale();
        boolean sufficient = supported && validCommodityCount && enoughSales
                && requiredStatisticsPresent && freshEnough;

        if (!enoughSales || !supported || !validCommodityCount || !requiredStatisticsPresent) {
            warnings.add(FairValueWarning.LOW_DATA);
        }
        if (!freshEnough) {
            warnings.add(FairValueWarning.STALE_MARKET);
        }

        BigDecimal recommendedSafetyMultiplier = BigDecimal.ONE;
        if (trend == MarketTrend.FALLING || highSupply) {
            recommendedSafetyMultiplier = max(
                    recommendedSafetyMultiplier, config.cautionSafetyBufferMultiplier()
            );
        }
        if (trendResult.rapidRise() || highVolatility) {
            recommendedSafetyMultiplier = max(
                    recommendedSafetyMultiplier, config.elevatedRiskSafetyBufferMultiplier()
            );
        }

        Optional<BigDecimal> observedLow = scale(primary.minimum(), totalMultiplier);
        Optional<BigDecimal> observedHigh = scale(primary.maximum(), totalMultiplier);
        if (!sufficient) {
            notes.add(insufficientExplanation(
                    primary, config, supported, validCommodityCount, requiredStatisticsPresent, freshEnough
            ));
            return new FairValueEstimate(
                    item.fingerprint().sha256(),
                    targetCount,
                    unitPriceBased,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    observedLow,
                    observedHigh,
                    trend,
                    false,
                    recommendedSafetyMultiplier,
                    List.copyOf(warnings),
                    explanation(primary, market, ask, trendResult, totalMultiplier, adjustments, notes)
            );
        }

        BigDecimal weightedMedian = primary.weightedMedian().orElseThrow();
        BigDecimal percentile40 = primary.percentile40().orElseThrow();
        BigDecimal centralUnitValue = primary.median().orElseThrow();
        BigDecimal optimisticUnitValue = primary.percentile75().orElseThrow();
        List<BigDecimal> conservativeCandidates = new ArrayList<>(List.of(weightedMedian, percentile40));
        ask.crediblePrice().ifPresent(conservativeCandidates::add);
        BigDecimal conservativeUnitValue = conservativeCandidates.stream().min(BigDecimal::compareTo).orElseThrow();

        if (trend == MarketTrend.FALLING) {
            conservativeUnitValue = reduce(
                    conservativeUnitValue,
                    config.fallingMarketReductionPercent(),
                    "FALLING_MARKET",
                    "Recent completed-sale median is below the long-term median.",
                    adjustments
            );
        }
        if (highVolatility) {
            conservativeUnitValue = reduce(
                    conservativeUnitValue,
                    config.highVolatilityReductionPercent(),
                    "HIGH_VOLATILITY",
                    "Completed-sale coefficient of variation exceeds the configured threshold.",
                    adjustments
            );
        }
        if (highSupply) {
            conservativeUnitValue = reduce(
                    conservativeUnitValue,
                    config.highSupplyReductionPercent(),
                    "HIGH_ACTIVE_SUPPLY",
                    "Active listing count is high relative to accepted completed sales.",
                    adjustments
            );
        }
        conservativeUnitValue = conservativeUnitValue.min(centralUnitValue);
        optimisticUnitValue = optimisticUnitValue.max(centralUnitValue);

        if (trend == MarketTrend.RISING) {
            notes.add("Rising prices were not extrapolated upward.");
        }
        if (ask.status() != ActiveAskEvidenceStatus.CREDIBLE) {
            notes.add("The second-lowest active ask was not used as completed-sale value evidence.");
        }
        notes.add("The conservative value is intended for future profit evaluation; active asks are not sales.");

        return new FairValueEstimate(
                item.fingerprint().sha256(),
                targetCount,
                unitPriceBased,
                Optional.of(scale(conservativeUnitValue, totalMultiplier)),
                Optional.of(scale(centralUnitValue, totalMultiplier)),
                Optional.of(scale(optimisticUnitValue, totalMultiplier)),
                observedLow,
                observedHigh,
                trend,
                true,
                recommendedSafetyMultiplier,
                List.copyOf(warnings),
                explanation(primary, market, ask, trendResult, totalMultiplier, adjustments, notes)
        );
    }

    private static TrendResult detectTrend(
            ItemMarketStatistics recent,
            ItemMarketStatistics longTerm,
            FairValueConfig config
    ) {
        if (recent.comparableSaleCount() < config.minimumTrendSales()
                || longTerm.comparableSaleCount() < config.minimumTrendSales()
                || recent.median().isEmpty()
                || longTerm.median().isEmpty()
                || longTerm.median().orElseThrow().signum() <= 0) {
            return new TrendResult(MarketTrend.UNKNOWN, Optional.empty(), false);
        }
        BigDecimal changePercent = recent.median().orElseThrow()
                .subtract(longTerm.median().orElseThrow(), StatisticalMath.MATH_CONTEXT)
                .divide(longTerm.median().orElseThrow(), StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
        MarketTrend trend;
        if (changePercent.abs().compareTo(config.stableTrendBoundaryPercent()) <= 0) {
            trend = MarketTrend.STABLE;
        } else if (changePercent.signum() > 0) {
            trend = MarketTrend.RISING;
        } else {
            trend = MarketTrend.FALLING;
        }
        boolean rapidRise = changePercent.compareTo(config.rapidRiseThresholdPercent()) >= 0;
        return new TrendResult(trend, Optional.of(changePercent), rapidRise);
    }

    private static AskResult evaluateSecondAsk(ItemMarketStatistics primary, FairValueConfig config) {
        Optional<BigDecimal> secondAsk = primary.activeAsks().secondLowestAsk();
        if (secondAsk.isEmpty() || primary.percentile25().isEmpty() || primary.percentile75().isEmpty()) {
            return new AskResult(ActiveAskEvidenceStatus.NOT_AVAILABLE, Optional.empty(), secondAsk);
        }
        BigDecimal lowerBound = primary.percentile25().orElseThrow()
                .multiply(config.activeAskLowerCredibilityRatio(), StatisticalMath.MATH_CONTEXT);
        BigDecimal upperBound = primary.percentile75().orElseThrow()
                .multiply(config.activeAskUpperCredibilityRatio(), StatisticalMath.MATH_CONTEXT);
        if (secondAsk.orElseThrow().compareTo(lowerBound) < 0) {
            return new AskResult(
                    ActiveAskEvidenceStatus.REJECTED_BELOW_COMPLETED_RANGE, Optional.empty(), secondAsk
            );
        }
        if (secondAsk.orElseThrow().compareTo(upperBound) > 0) {
            return new AskResult(
                    ActiveAskEvidenceStatus.REJECTED_ABOVE_COMPLETED_RANGE, Optional.empty(), secondAsk
            );
        }
        return new AskResult(ActiveAskEvidenceStatus.CREDIBLE, secondAsk, secondAsk);
    }

    private static boolean isHighSupply(ItemMarketStatistics primary, FairValueConfig config) {
        if (primary.comparableSaleCount() == 0 || primary.activeAsks().listingCount() == 0) {
            return false;
        }
        BigDecimal ratio = BigDecimal.valueOf(primary.activeAsks().listingCount())
                .divide(BigDecimal.valueOf(primary.comparableSaleCount()), StatisticalMath.MATH_CONTEXT);
        return ratio.compareTo(config.highSupplyListingToSaleRatio()) > 0;
    }

    private static BigDecimal reduce(
            BigDecimal value,
            BigDecimal reductionPercent,
            String code,
            String reason,
            List<FairValueAdjustment> adjustments
    ) {
        adjustments.add(new FairValueAdjustment(code, reductionPercent, reason));
        return value.multiply(
                BigDecimal.ONE.subtract(reductionPercent.divide(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT)),
                StatisticalMath.MATH_CONTEXT
        );
    }

    private static FairValueExplanation explanation(
            ItemMarketStatistics primary,
            FairValueMarketContext market,
            AskResult ask,
            TrendResult trend,
            BigDecimal multiplier,
            List<FairValueAdjustment> adjustments,
            List<String> notes
    ) {
        return new FairValueExplanation(
                primary.comparableSaleCount(),
                primary.excludedSaleCount(),
                scale(primary.weightedMedian(), multiplier),
                scale(primary.percentile40(), multiplier),
                scale(primary.median(), multiplier),
                scale(market.recent().median(), multiplier),
                scale(market.longTerm().median(), multiplier),
                scale(ask.observedPrice(), multiplier),
                ask.status(),
                trend.changePercent(),
                adjustments,
                notes
        );
    }

    private static String insufficientExplanation(
            ItemMarketStatistics primary,
            FairValueConfig config,
            boolean supported,
            boolean validCommodityCount,
            boolean requiredStatisticsPresent,
            boolean freshEnough
    ) {
        if (!supported) {
            return "Unsupported item matching cannot produce a fair value.";
        }
        if (!validCommodityCount) {
            return "Commodity stack count is missing, so a listing-total fair value cannot be calculated.";
        }
        if (!freshEnough) {
            return "Primary completed-sale history is stale; no fair value was produced.";
        }
        if (!requiredStatisticsPresent) {
            return "Required completed-sale statistics are missing; no fair value was produced.";
        }
        return "Only " + primary.comparableSaleCount() + " accepted completed sales found; minimum required: "
                + config.minimumCompletedSales() + ".";
    }

    private static Optional<BigDecimal> scale(Optional<BigDecimal> value, BigDecimal multiplier) {
        return value.map(price -> scale(price, multiplier));
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal multiplier) {
        return value.multiply(multiplier, StatisticalMath.MATH_CONTEXT);
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.max(right);
    }

    private record TrendResult(
            MarketTrend trend,
            Optional<BigDecimal> changePercent,
            boolean rapidRise
    ) {
    }

    private record AskResult(
            ActiveAskEvidenceStatus status,
            Optional<BigDecimal> crediblePrice,
            Optional<BigDecimal> observedPrice
    ) {
    }
}
