package com.example.donutflipscanner.market.confidence;

import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.statistics.StatisticalMath;
import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ConfidenceCalculator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ConfidenceBreakdown calculate(
            ConfidenceCalculationRequest request,
            ConfidenceConfig config
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        List<ConfidenceWarning> warnings = new ArrayList<>();
        ConfidenceWeights weights = config.weights();
        ItemMarketStatistics primary = request.market().primary();

        CategoryResult comparable = comparableSales(primary.comparableSaleCount(), warnings);
        CategoryResult sellers = sellerDiversity(primary, warnings);
        CategoryResult stability = priceStability(request, warnings);
        CategoryResult match = itemMatch(request, warnings);
        CategoryResult liquidity = liquidity(request, warnings);
        CategoryResult freshness = freshness(request, warnings);

        int comparableScore = scale(comparable, weights.comparableSales());
        int sellerScore = scale(sellers, weights.sellerDiversity());
        int stabilityScore = scale(stability, weights.priceStability());
        int matchScore = scale(match, weights.itemMatch());
        int liquidityScore = scale(liquidity, weights.liquidity());
        int freshnessScore = scale(freshness, weights.freshness());
        int rawScore = comparableScore + sellerScore + stabilityScore
                + matchScore + liquidityScore + freshnessScore;

        List<AppliedConfidenceAdjustment> adjustments = adjustments(request);
        int totalReduction = adjustments.stream()
                .mapToInt(AppliedConfidenceAdjustment::reductionPoints)
                .sum();
        int scoreAfterAdjustments = Math.max(0, rawScore - totalReduction);
        List<AppliedConfidenceCap> caps = caps(request, config, warnings);
        int maximumAllowedScore = caps.stream()
                .mapToInt(AppliedConfidenceCap::maximumScore)
                .min()
                .orElse(100);
        int totalScore = Math.min(scoreAfterAdjustments, maximumAllowedScore);

        List<ConfidenceCategoryDetail> details = List.of(
                detail(ConfidenceCategory.COMPARABLE_SALES, comparableScore, weights.comparableSales(), comparable),
                detail(ConfidenceCategory.SELLER_DIVERSITY, sellerScore, weights.sellerDiversity(), sellers),
                detail(ConfidenceCategory.PRICE_STABILITY, stabilityScore, weights.priceStability(), stability),
                detail(ConfidenceCategory.ITEM_MATCH, matchScore, weights.itemMatch(), match),
                detail(ConfidenceCategory.LIQUIDITY, liquidityScore, weights.liquidity(), liquidity),
                detail(ConfidenceCategory.FRESHNESS, freshnessScore, weights.freshness(), freshness)
        );
        return new ConfidenceBreakdown(
                comparableScore,
                sellerScore,
                stabilityScore,
                matchScore,
                liquidityScore,
                freshnessScore,
                rawScore,
                scoreAfterAdjustments,
                totalScore,
                details,
                adjustments,
                caps,
                warnings
        );
    }

    private static CategoryResult comparableSales(int count, List<ConfidenceWarning> warnings) {
        int base = switch (count) {
            case 0 -> 0;
            case 1, 2 -> 3;
            case 3, 4, 5 -> 8;
            case 6, 7, 8, 9, 10 -> 15;
            default -> count <= 20 ? 21 : 25;
        };
        if (count < 3) {
            warn(warnings, ConfidenceWarningCode.LOW_SAMPLE,
                    "Fewer than three accepted comparable sales sharply limit confidence.");
        }
        return new CategoryResult(
                BigDecimal.valueOf(base), 25,
                List.of(count + " accepted completed sales contributed " + base + "/25 base points.")
        );
    }

    private static CategoryResult sellerDiversity(
            ItemMarketStatistics primary,
            List<ConfidenceWarning> warnings
    ) {
        List<ComparableSale> sales = primary.comparableSales().accepted();
        Map<String, Integer> counts = new HashMap<>();
        for (ComparableSale sale : sales) {
            sale.sellerIdentity().ifPresent(seller -> counts.merge(seller, 1, Integer::sum));
        }
        int identified = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (identified == 0) {
            warn(warnings, ConfidenceWarningCode.PARTICIPANT_DATA_MISSING,
                    "No seller identities are available for diversity scoring.");
            return new CategoryResult(
                    BigDecimal.ZERO, 15,
                    List.of("0 seller identities were available; no diversity points were awarded.")
            );
        }
        if (identified < sales.size()) {
            warn(warnings, ConfidenceWarningCode.PARTICIPANT_DATA_MISSING,
                    (sales.size() - identified) + " comparable sales have no seller identity.");
        }
        List<Integer> descending = counts.values().stream().sorted(Comparator.reverseOrder()).toList();
        BigDecimal largestShare = percentage(descending.getFirst(), identified);
        int topThreeCount = descending.stream().limit(3).mapToInt(Integer::intValue).sum();
        BigDecimal topThreeShare = percentage(topThreeCount, identified);
        BigDecimal uniqueCountPoints = BigDecimal.valueOf(Math.min(counts.size(), 5));
        BigDecimal largestSharePoints = inverseLinear(
                largestShare, new BigDecimal("20"), ONE_HUNDRED, new BigDecimal("5")
        );
        BigDecimal topThreePoints = inverseLinear(
                topThreeShare, new BigDecimal("50"), ONE_HUNDRED, new BigDecimal("5")
        );
        if (largestShare.compareTo(new BigDecimal("60")) >= 0
                || topThreeShare.compareTo(new BigDecimal("90")) >= 0) {
            warn(warnings, ConfidenceWarningCode.SELLER_CONCENTRATION,
                    "Completed sales are concentrated among too few sellers.");
        }
        return new CategoryResult(
                uniqueCountPoints.add(largestSharePoints).add(topThreePoints),
                15,
                List.of(
                        counts.size() + " unique sellers across " + identified + " identified sales.",
                        "Largest seller share: " + display(largestShare) + "%.",
                        "Top-three seller share: " + display(topThreeShare) + "%."
                )
        );
    }

    private static CategoryResult priceStability(
            ConfidenceCalculationRequest request,
            List<ConfidenceWarning> warnings
    ) {
        ItemMarketStatistics primary = request.market().primary();
        List<String> evidence = new ArrayList<>();
        BigDecimal points = BigDecimal.ZERO;
        Optional<BigDecimal> median = primary.median().filter(value -> value.signum() > 0);

        if (primary.coefficientOfVariation().isPresent()) {
            BigDecimal coefficient = primary.coefficientOfVariation().orElseThrow();
            points = points.add(inverseLinear(
                    coefficient, new BigDecimal("0.10"), new BigDecimal("0.50"), new BigDecimal("5")
            ));
            evidence.add("Coefficient of variation: " + display(coefficient) + ".");
            if (coefficient.compareTo(new BigDecimal("0.30")) > 0) {
                warn(warnings, ConfidenceWarningCode.HIGH_VOLATILITY,
                        "Completed-sale coefficient of variation is high.");
            }
        } else {
            warn(warnings, ConfidenceWarningCode.PRICE_DATA_MISSING,
                    "Coefficient of variation is unavailable.");
        }

        if (median.isPresent() && primary.medianAbsoluteDeviation().isPresent()) {
            BigDecimal ratio = primary.medianAbsoluteDeviation().orElseThrow()
                    .divide(median.orElseThrow(), StatisticalMath.MATH_CONTEXT);
            points = points.add(inverseLinear(
                    ratio, new BigDecimal("0.05"), new BigDecimal("0.30"), new BigDecimal("5")
            ));
            evidence.add("MAD/median ratio: " + display(ratio) + ".");
        } else {
            warn(warnings, ConfidenceWarningCode.PRICE_DATA_MISSING,
                    "MAD stability evidence is unavailable.");
        }

        if (median.isPresent() && primary.interquartileRange().isPresent()) {
            BigDecimal ratio = primary.interquartileRange().orElseThrow()
                    .divide(median.orElseThrow(), StatisticalMath.MATH_CONTEXT);
            points = points.add(inverseLinear(
                    ratio, new BigDecimal("0.15"), new BigDecimal("0.80"), new BigDecimal("5")
            ));
            evidence.add("IQR/median ratio: " + display(ratio) + ".");
        } else {
            warn(warnings, ConfidenceWarningCode.PRICE_DATA_MISSING,
                    "IQR stability evidence is unavailable.");
        }

        Optional<BigDecimal> divergence = trendDivergence(request);
        if (divergence.isPresent()) {
            points = points.add(inverseLinear(
                    divergence.orElseThrow().abs(), new BigDecimal("5"), new BigDecimal("30"),
                    new BigDecimal("5")
            ));
            evidence.add("Recent-to-long-term median divergence: "
                    + display(divergence.orElseThrow()) + "%.");
        } else {
            warn(warnings, ConfidenceWarningCode.TREND_DATA_MISSING,
                    "Recent versus long-term trend evidence is unavailable.");
        }
        return new CategoryResult(points, 20, evidence);
    }

    private static CategoryResult itemMatch(
            ConfidenceCalculationRequest request,
            List<ConfidenceWarning> warnings
    ) {
        ItemMatchType type = request.item().matchQuality().matchType();
        int points = switch (type) {
            case EXACT -> request.item().matchQuality().issues().isEmpty() ? 20 : 17;
            case COMMODITY -> 15;
            case VISIBLE_METADATA -> 12;
            case APPROXIMATE -> 8;
            case UNSUPPORTED -> 0;
        };
        if (type == ItemMatchType.VISIBLE_METADATA) {
            warn(warnings, ConfidenceWarningCode.APPROXIMATE_ITEM_MATCH,
                    "Only API-visible metadata is matched; hidden value metadata may differ.");
        } else if (type == ItemMatchType.APPROXIMATE) {
            warn(warnings, ConfidenceWarningCode.APPROXIMATE_ITEM_MATCH,
                    "Approximate item metadata limits confidence.");
        } else if (type == ItemMatchType.UNSUPPORTED) {
            warn(warnings, ConfidenceWarningCode.UNSUPPORTED_ITEM,
                    "Unsupported item metadata cannot be valued safely.");
        }
        return new CategoryResult(
                BigDecimal.valueOf(points), 20,
                List.of(type + " item match contributed " + points + "/20 base points.")
        );
    }

    private static CategoryResult liquidity(
            ConfidenceCalculationRequest request,
            List<ConfidenceWarning> warnings
    ) {
        ItemMarketStatistics primary = request.market().primary();
        List<String> evidence = new ArrayList<>();
        BigDecimal salesPerDay = primary.salesPerDay().orElse(BigDecimal.ZERO).max(BigDecimal.ZERO);
        BigDecimal salesPoints = salesPerDay
                .divide(new BigDecimal("12"), StatisticalMath.MATH_CONTEXT)
                .multiply(BigDecimal.TEN, StatisticalMath.MATH_CONTEXT)
                .min(BigDecimal.TEN);
        int recentCount = request.market().recent().comparableSaleCount();
        BigDecimal recentPoints = BigDecimal.valueOf(recentCount)
                .divide(new BigDecimal("8"), StatisticalMath.MATH_CONTEXT)
                .multiply(new BigDecimal("3"), StatisticalMath.MATH_CONTEXT)
                .min(new BigDecimal("3"));
        Optional<BigDecimal> medianIntervalHours = medianSaleIntervalHours(
                primary.comparableSales().accepted()
        );
        BigDecimal intervalPoints = medianIntervalHours.map(ConfidenceCalculator::intervalPoints)
                .orElse(BigDecimal.ZERO);
        BigDecimal points = salesPoints.add(recentPoints).add(intervalPoints);

        evidence.add("Sales per day: " + display(salesPerDay) + ".");
        evidence.add("Recent accepted sales: " + recentCount + ".");
        medianIntervalHours.ifPresent(value -> evidence.add(
                "Median interval between sales: " + display(value) + " hours."
        ));
        evidence.add("Active asks are supply evidence and add no liquidity points.");

        if (primary.comparableSaleCount() > 0 && primary.activeAsks().listingCount() > 0) {
            BigDecimal supplyRatio = BigDecimal.valueOf(primary.activeAsks().listingCount())
                    .divide(BigDecimal.valueOf(primary.comparableSaleCount()), StatisticalMath.MATH_CONTEXT);
            evidence.add("Active-listing/completed-sale ratio: " + display(supplyRatio) + ".");
            if (supplyRatio.compareTo(BigDecimal.ONE) > 0) {
                BigDecimal penalty = supplyRatio.subtract(BigDecimal.ONE)
                        .multiply(new BigDecimal("3"), StatisticalMath.MATH_CONTEXT)
                        .min(new BigDecimal("3"));
                points = points.subtract(penalty).max(BigDecimal.ZERO);
                warn(warnings, ConfidenceWarningCode.HIGH_ACTIVE_SUPPLY,
                        "Active supply is high relative to completed-sale evidence.");
            }
        }
        if (points.compareTo(new BigDecimal("7.5")) < 0) {
            warn(warnings, ConfidenceWarningCode.LOW_LIQUIDITY,
                    "Completed-sale activity indicates low liquidity.");
        }
        return new CategoryResult(points, 15, evidence);
    }

    private static CategoryResult freshness(
            ConfidenceCalculationRequest request,
            List<ConfidenceWarning> warnings
    ) {
        List<String> evidence = new ArrayList<>();
        BigDecimal latestSalePoints = request.market().primary().timeSinceMostRecentSale()
                .map(ConfidenceCalculator::latestSaleFreshness)
                .orElse(BigDecimal.ZERO);
        BigDecimal snapshotPoints = snapshotFreshness(request.snapshotAge());
        BigDecimal listingPoints = listingFreshness(request.listingAge());
        request.market().primary().timeSinceMostRecentSale().ifPresent(age ->
                evidence.add("Newest completed sale age: " + displayHours(age) + " hours."));
        evidence.add("Statistics snapshot age: " + displayHours(request.snapshotAge()) + " hours.");
        evidence.add("Listing age: " + displayHours(request.listingAge()) + " hours.");
        if (request.market().primary().stale() || latestSalePoints.signum() == 0) {
            warn(warnings, ConfidenceWarningCode.STALE_MARKET,
                    "Completed-sale evidence is stale or unavailable.");
        }
        return new CategoryResult(
                latestSalePoints.add(snapshotPoints).add(listingPoints), 5, evidence
        );
    }

    private static List<AppliedConfidenceCap> caps(
            ConfidenceCalculationRequest request,
            ConfidenceConfig config,
            List<ConfidenceWarning> warnings
    ) {
        List<AppliedConfidenceCap> caps = new ArrayList<>();
        ItemMarketStatistics primary = request.market().primary();
        if (primary.comparableSaleCount() < config.lowSampleThreshold()) {
            caps.add(new AppliedConfidenceCap(
                    ConfidenceCapReason.LOW_SAMPLE,
                    config.lowSampleMaximumScore(),
                    "Fewer than " + config.lowSampleThreshold() + " comparable sales."
            ));
        }
        ItemMatchType type = request.item().matchQuality().matchType();
        if (type == ItemMatchType.VISIBLE_METADATA) {
            caps.add(new AppliedConfidenceCap(
                    ConfidenceCapReason.APPROXIMATE_ITEM,
                    config.approximateItemMaximumScore(),
                    "Visible-metadata-only item match."
            ));
        } else if (type == ItemMatchType.APPROXIMATE) {
            caps.add(new AppliedConfidenceCap(
                    ConfidenceCapReason.APPROXIMATE_ITEM,
                    config.approximateItemMaximumScore(),
                    "Approximate item match."
            ));
        } else if (type == ItemMatchType.UNSUPPORTED) {
            caps.add(new AppliedConfidenceCap(
                    ConfidenceCapReason.UNSUPPORTED_ITEM,
                    config.unsupportedItemMaximumScore(),
                    "Unsupported item match."
            ));
        }
        primary.coefficientOfVariation().ifPresent(coefficient -> {
            if (coefficient.compareTo(config.extremeVolatilityCoefficient()) >= 0) {
                caps.add(new AppliedConfidenceCap(
                        ConfidenceCapReason.EXTREME_VOLATILITY,
                        config.extremeVolatilityMaximumScore(),
                        "Coefficient of variation reached the extreme-volatility threshold."
                ));
            }
        });
        request.externalCap()
                .filter(external -> external.maximumScore() < 100 || external.reductionPoints() > 0)
                .ifPresent(external -> {
            caps.add(new AppliedConfidenceCap(
                    ConfidenceCapReason.EXTERNAL_RISK,
                    external.maximumScore(),
                    external.code() + ": " + external.explanation()
            ));
            warn(warnings, ConfidenceWarningCode.EXTERNAL_RISK, external.explanation());
                });
        return List.copyOf(caps);
    }

    private static List<AppliedConfidenceAdjustment> adjustments(ConfidenceCalculationRequest request) {
        return request.externalCap()
                .filter(external -> external.reductionPoints() > 0)
                .map(external -> List.of(new AppliedConfidenceAdjustment(
                        external.code(), external.reductionPoints(), external.explanation()
                )))
                .orElseGet(List::of);
    }

    private static Optional<BigDecimal> trendDivergence(ConfidenceCalculationRequest request) {
        Optional<BigDecimal> recent = request.market().recent().median();
        Optional<BigDecimal> longTerm = request.market().longTerm().median();
        if (recent.isEmpty() || longTerm.isEmpty() || longTerm.orElseThrow().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(recent.orElseThrow().subtract(longTerm.orElseThrow())
                .divide(longTerm.orElseThrow(), StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT));
    }

    private static Optional<BigDecimal> medianSaleIntervalHours(List<ComparableSale> sales) {
        if (sales.size() < 2) {
            return Optional.empty();
        }
        List<Instant> times = sales.stream().map(ComparableSale::soldAt).sorted().toList();
        List<BigDecimal> intervals = new ArrayList<>();
        for (int index = 1; index < times.size(); index++) {
            long seconds = Duration.between(times.get(index - 1), times.get(index)).getSeconds();
            intervals.add(BigDecimal.valueOf(Math.max(0, seconds))
                    .divide(BigDecimal.valueOf(3_600), StatisticalMath.MATH_CONTEXT));
        }
        return Optional.of(StatisticalMath.median(intervals));
    }

    private static BigDecimal intervalPoints(BigDecimal hours) {
        if (hours.compareTo(BigDecimal.ONE) <= 0) {
            return new BigDecimal("2");
        }
        if (hours.compareTo(new BigDecimal("6")) <= 0) {
            return new BigDecimal("1.5");
        }
        if (hours.compareTo(new BigDecimal("24")) <= 0) {
            return BigDecimal.ONE;
        }
        if (hours.compareTo(new BigDecimal("72")) <= 0) {
            return new BigDecimal("0.5");
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal latestSaleFreshness(Duration age) {
        if (age.compareTo(Duration.ofHours(1)) <= 0) {
            return new BigDecimal("3");
        }
        if (age.compareTo(Duration.ofHours(6)) <= 0) {
            return new BigDecimal("2");
        }
        if (age.compareTo(Duration.ofHours(24)) <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal snapshotFreshness(Duration age) {
        if (age.compareTo(Duration.ofMinutes(1)) <= 0) {
            return BigDecimal.ONE;
        }
        if (age.compareTo(Duration.ofMinutes(5)) <= 0) {
            return new BigDecimal("0.75");
        }
        if (age.compareTo(Duration.ofMinutes(15)) <= 0) {
            return new BigDecimal("0.5");
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal listingFreshness(Duration age) {
        if (age.compareTo(Duration.ofMinutes(5)) <= 0) {
            return BigDecimal.ONE;
        }
        if (age.compareTo(Duration.ofMinutes(30)) <= 0) {
            return new BigDecimal("0.75");
        }
        if (age.compareTo(Duration.ofHours(2)) <= 0) {
            return new BigDecimal("0.5");
        }
        if (age.compareTo(Duration.ofHours(24)) <= 0) {
            return new BigDecimal("0.25");
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal inverseLinear(
            BigDecimal value,
            BigDecimal goodMaximum,
            BigDecimal badMinimum,
            BigDecimal maximumPoints
    ) {
        if (value.compareTo(goodMaximum) <= 0) {
            return maximumPoints;
        }
        if (value.compareTo(badMinimum) >= 0) {
            return BigDecimal.ZERO;
        }
        return badMinimum.subtract(value)
                .divide(badMinimum.subtract(goodMaximum), StatisticalMath.MATH_CONTEXT)
                .multiply(maximumPoints, StatisticalMath.MATH_CONTEXT);
    }

    private static BigDecimal percentage(int part, int total) {
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
    }

    private static int scale(CategoryResult result, int configuredMaximum) {
        BigDecimal bounded = result.basePoints().max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(result.baseMaximum()));
        return bounded.multiply(BigDecimal.valueOf(configuredMaximum), StatisticalMath.MATH_CONTEXT)
                .divide(BigDecimal.valueOf(result.baseMaximum()), StatisticalMath.MATH_CONTEXT)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static ConfidenceCategoryDetail detail(
            ConfidenceCategory category,
            int score,
            int maximum,
            CategoryResult result
    ) {
        return new ConfidenceCategoryDetail(category, score, maximum, result.evidence());
    }

    private static void warn(
            List<ConfidenceWarning> warnings,
            ConfidenceWarningCode code,
            String message
    ) {
        if (warnings.stream().noneMatch(warning -> warning.code() == code)) {
            warnings.add(new ConfidenceWarning(code, message));
        }
    }

    private static String display(BigDecimal value) {
        BigDecimal rounded = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0).toPlainString() : rounded.toPlainString();
    }

    private static String displayHours(Duration duration) {
        BigDecimal hours = BigDecimal.valueOf(duration.toSeconds())
                .divide(BigDecimal.valueOf(3_600), StatisticalMath.MATH_CONTEXT);
        return display(hours);
    }

    private record CategoryResult(
            BigDecimal basePoints,
            int baseMaximum,
            List<String> evidence
    ) {
        private CategoryResult {
            Objects.requireNonNull(basePoints, "basePoints");
            evidence = List.copyOf(evidence);
        }
    }
}
