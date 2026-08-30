package com.example.donutflipscanner.market.risk;

import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.MarketItemFamilyPolicy;
import com.example.donutflipscanner.market.statistics.StatisticalMath;
import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.statistics.model.RejectedComparableSale;

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
import java.util.function.Function;

public final class ManipulationRiskDetector {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ManipulationRiskAssessment assess(
            ManipulationRiskRequest request,
            ManipulationRiskConfig config
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        List<MarketAnomalyIndicator> indicators = new ArrayList<>();
        ItemMarketStatistics primary = request.market().primary();

        detectExcludedExtremes(primary, indicators);
        detectPriceMovement(request, config, indicators);
        detectConcentration(primary, config, indicators);
        detectRepeatedUnusualTrades(request, config, indicators);
        detectParticipantPatterns(primary, config, indicators);
        detectActiveAskDivergence(request, config, indicators);
        detectVariantAndMetadata(request, config, indicators);

        int riskScore = Math.min(100, indicators.stream()
                .mapToInt(MarketAnomalyIndicator::riskPoints)
                .sum());
        boolean insufficientForAssessment = primary.comparableSaleCount() < config.minimumAssessmentSales()
                && request.market().longTerm().comparableSaleCount() < config.minimumAssessmentSales()
                && indicators.isEmpty();
        MarketRiskLevel level = insufficientForAssessment
                ? MarketRiskLevel.UNKNOWN
                : level(riskScore, config);
        RiskEffects effects = effects(level);
        Optional<String> rejection = level == MarketRiskLevel.SEVERE
                ? Optional.of(severeRejection(riskScore, indicators))
                : Optional.empty();

        return new ManipulationRiskAssessment(
                request.item().fingerprint().sha256(),
                level,
                riskScore,
                level == MarketRiskLevel.SEVERE,
                effects.suppressSoundAlerts(),
                effects.confidenceReduction(),
                effects.confidenceCap(),
                effects.safetyMultiplier(),
                indicators,
                rejection
        );
    }

    private static void detectExcludedExtremes(
            ItemMarketStatistics primary,
            List<MarketAnomalyIndicator> indicators
    ) {
        Optional<BigDecimal> median = primary.median();
        if (median.isEmpty()) {
            return;
        }
        int high = 0;
        int low = 0;
        for (RejectedComparableSale sale : primary.comparableSales().rejected()) {
            if ((sale.reason() == ComparableSaleRejectionReason.MAD_OUTLIER
                    || sale.reason() == ComparableSaleRejectionReason.IQR_OUTLIER)
                    && sale.comparablePrice().isPresent()) {
                if (sale.comparablePrice().orElseThrow().compareTo(median.orElseThrow()) > 0) {
                    high++;
                } else if (sale.comparablePrice().orElseThrow().compareTo(median.orElseThrow()) < 0) {
                    low++;
                }
            }
        }
        if (high > 0) {
            indicators.add(indicator(
                    MarketAnomalyType.EXTREME_HIGH_SALES,
                    MarketAnomalySeverity.MODERATE,
                    20,
                    "Unusual high completed sales",
                    high + " extreme high completed sale(s) were excluded by robust statistics."
            ));
        }
        if (low > 0) {
            indicators.add(indicator(
                    MarketAnomalyType.EXTREME_LOW_SALES,
                    MarketAnomalySeverity.MODERATE,
                    20,
                    "Unusual low completed sales",
                    low + " extreme low completed sale(s) were excluded by robust statistics."
            ));
        }
    }

    private static void detectPriceMovement(
            ManipulationRiskRequest request,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        Optional<BigDecimal> recent = request.market().recent().median();
        Optional<BigDecimal> longTerm = request.market().longTerm().median();
        if (recent.isEmpty() || longTerm.isEmpty() || longTerm.orElseThrow().signum() <= 0) {
            return;
        }
        BigDecimal movement = recent.orElseThrow().subtract(longTerm.orElseThrow())
                .divide(longTerm.orElseThrow(), StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
        BigDecimal absolute = movement.abs();
        if (absolute.compareTo(config.suddenMovementPercent()) < 0) {
            return;
        }
        boolean supportedByVolume = request.market().recent().comparableSaleCount()
                > config.lowVolumeMaximumSales();
        boolean sharp = absolute.compareTo(config.sharpMovementPercent()) >= 0;
        int points = supportedByVolume ? 5 : (sharp ? 25 : 15);
        MarketAnomalyType type = movement.signum() >= 0
                ? MarketAnomalyType.SUDDEN_PRICE_JUMP
                : MarketAnomalyType.SUDDEN_PRICE_DROP;
        indicators.add(indicator(
                type,
                supportedByVolume ? MarketAnomalySeverity.LOW
                        : (sharp ? MarketAnomalySeverity.HIGH : MarketAnomalySeverity.MODERATE),
                points,
                movement.signum() >= 0 ? "Unusual price movement upward" : "Unusual price movement downward",
                "Recent completed-sale median differs from the long-term median by "
                        + display(movement) + "%"
                        + (supportedByVolume
                        ? " and is supported by substantial recent sale volume."
                        : " with limited recent sale volume.")
        ));
        if (!supportedByVolume) {
            indicators.add(indicator(
                    MarketAnomalyType.LOW_VOLUME_PRICE_SPIKE,
                    MarketAnomalySeverity.HIGH,
                    30,
                    "Low-volume price movement",
                    "Only " + request.market().recent().comparableSaleCount()
                            + " recent completed sales support a " + display(absolute) + "% movement."
            ));
        }
    }

    private static void detectConcentration(
            ItemMarketStatistics primary,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        concentration(
                primary.comparableSales().accepted(),
                ComparableSale::sellerIdentity,
                MarketAnomalyType.SELLER_CONCENTRATION,
                "Concentrated seller activity",
                config,
                indicators
        );
        concentration(
                primary.comparableSales().accepted(),
                ComparableSale::buyerIdentity,
                MarketAnomalyType.BUYER_CONCENTRATION,
                "Insufficient buyer diversity",
                config,
                indicators
        );
    }

    private static void concentration(
            List<ComparableSale> sales,
            Function<ComparableSale, Optional<String>> identity,
            MarketAnomalyType type,
            String label,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        Map<String, Integer> counts = new HashMap<>();
        for (ComparableSale sale : sales) {
            identity.apply(sale).ifPresent(value -> counts.merge(value, 1, Integer::sum));
        }
        int identified = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (identified < config.minimumAssessmentSales()) {
            return;
        }
        int largest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        BigDecimal share = percentage(largest, identified);
        if (share.compareTo(config.concentrationWarningPercent()) < 0) {
            return;
        }
        boolean high = share.compareTo(config.concentrationHighPercent()) >= 0;
        indicators.add(indicator(
                type,
                high ? MarketAnomalySeverity.HIGH : MarketAnomalySeverity.MODERATE,
                high ? 30 : 15,
                label,
                display(share) + "% of identified completed sales involve one participant in this role."
        ));
    }

    private static void detectRepeatedUnusualTrades(
            ManipulationRiskRequest request,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        Optional<BigDecimal> baseline = request.market().longTerm().median()
                .filter(value -> value.signum() > 0);
        if (baseline.isEmpty()) {
            return;
        }
        Map<String, PriceGroup> groups = new HashMap<>();
        for (ComparableSale sale : request.market().primary().comparableSales().accepted()) {
            String key = sale.comparablePrice().stripTrailingZeros().toPlainString();
            groups.compute(key, (ignored, current) -> current == null
                    ? new PriceGroup(sale.comparablePrice(), 1)
                    : new PriceGroup(current.price(), current.count() + 1));
        }
        PriceGroup unusual = groups.values().stream()
                .filter(group -> group.count() >= config.repeatedIdenticalTradeCount())
                .filter(group -> group.count() <= config.lowVolumeMaximumSales())
                .filter(group -> deviationPercent(group.price(), baseline.orElseThrow())
                        .compareTo(config.unusualTradeDeviationPercent()) >= 0)
                .max(Comparator.comparingInt(PriceGroup::count))
                .orElse(null);
        if (unusual != null) {
            indicators.add(indicator(
                    MarketAnomalyType.REPEATED_IDENTICAL_UNUSUAL_TRADES,
                    MarketAnomalySeverity.HIGH,
                    20,
                    "Repeated unusual transaction price",
                    unusual.count() + " completed sales share price " + display(unusual.price())
                            + ", which differs from the long-term median by "
                            + display(deviationPercent(unusual.price(), baseline.orElseThrow())) + "%."
            ));
        }
    }

    private static void detectParticipantPatterns(
            ItemMarketStatistics primary,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        List<ComparableSale> sales = primary.comparableSales().accepted().stream()
                .filter(sale -> sale.sellerIdentity().isPresent() && sale.buyerIdentity().isPresent())
                .sorted(Comparator.comparing(ComparableSale::soldAt))
                .toList();
        Map<ParticipantPair, Instant> latestDirectedTrade = new HashMap<>();
        int circularReversals = 0;
        int alternatingStreak = 0;
        int maximumAlternatingStreak = 0;
        ComparableSale previous = null;
        for (ComparableSale sale : sales) {
            String seller = sale.sellerIdentity().orElseThrow();
            String buyer = sale.buyerIdentity().orElseThrow();
            if (seller.equals(buyer)) {
                previous = sale;
                continue;
            }
            ParticipantPair current = new ParticipantPair(seller, buyer);
            Instant reverseTime = latestDirectedTrade.get(new ParticipantPair(buyer, seller));
            if (reverseTime != null
                    && Duration.between(reverseTime, sale.soldAt()).compareTo(config.circularTradeWindow()) <= 0) {
                circularReversals++;
            }
            latestDirectedTrade.put(current, sale.soldAt());

            if (previous != null
                    && previous.sellerIdentity().orElseThrow().equals(buyer)
                    && previous.buyerIdentity().orElseThrow().equals(seller)
                    && Duration.between(previous.soldAt(), sale.soldAt())
                    .compareTo(config.rapidAlternationWindow()) <= 0) {
                alternatingStreak++;
                maximumAlternatingStreak = Math.max(maximumAlternatingStreak, alternatingStreak);
            } else {
                alternatingStreak = 0;
            }
            previous = sale;
        }
        if (circularReversals > 0) {
            indicators.add(indicator(
                    MarketAnomalyType.CIRCULAR_TRADING_PATTERN,
                    MarketAnomalySeverity.HIGH,
                    25,
                    "Potential circular transaction pattern",
                    circularReversals + " rapid reverse-direction participant pair(s) were observed."
            ));
        }
        if (maximumAlternatingStreak >= config.rapidAlternationTransitions()) {
            indicators.add(indicator(
                    MarketAnomalyType.RAPID_ALTERNATING_TRANSACTIONS,
                    MarketAnomalySeverity.HIGH,
                    25,
                    "Rapid alternating transaction pattern",
                    maximumAlternatingStreak + " consecutive reverse-direction transitions occurred within "
                            + config.rapidAlternationWindow().toMinutes() + " minutes."
            ));
        }
    }

    private static void detectActiveAskDivergence(
            ManipulationRiskRequest request,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        if (!request.item().matchQuality().matchType().unitPriceBased()
                || request.market().primary().median().isEmpty()
                || request.market().primary().median().orElseThrow().signum() <= 0
                || request.market().primary().activeAsks().lowestAsk().isEmpty()) {
            return;
        }
        BigDecimal ratio = request.market().primary().activeAsks().lowestAsk().orElseThrow()
                .divide(request.market().primary().median().orElseThrow(), StatisticalMath.MATH_CONTEXT);
        if (ratio.compareTo(config.lowActiveAskRatio()) < 0) {
            indicators.add(indicator(
                    MarketAnomalyType.ACTIVE_ASKS_FAR_BELOW_SALES,
                    MarketAnomalySeverity.HIGH,
                    20,
                    "Active ask far below completed sales",
                    "Lowest active ask is " + display(ratio.multiply(ONE_HUNDRED))
                            + "% of the primary completed-sale median."
            ));
        } else if (ratio.compareTo(config.highActiveAskRatio()) > 0) {
            indicators.add(indicator(
                    MarketAnomalyType.ACTIVE_ASKS_FAR_ABOVE_SALES,
                    MarketAnomalySeverity.MODERATE,
                    10,
                    "Active asks far above completed sales",
                    "Lowest active ask is " + display(ratio.multiply(ONE_HUNDRED))
                            + "% of the primary completed-sale median."
            ));
        }
    }

    private static void detectVariantAndMetadata(
            ManipulationRiskRequest request,
            ManipulationRiskConfig config,
            List<MarketAnomalyIndicator> indicators
    ) {
        boolean customVariant = request.item().customName().isPresent()
                || !request.item().lore().isEmpty()
                || !request.item().enchantments().isEmpty()
                || request.item().armorTrim().isPresent()
                || !request.item().contents().isEmpty();
        if (customVariant
                && request.variantKnownAge().isPresent()
                && request.variantKnownAge().orElseThrow().compareTo(config.newVariantMaximumAge()) <= 0
                && request.market().primary().comparableSaleCount() <= config.newVariantMaximumSales()) {
            indicators.add(indicator(
                    MarketAnomalyType.NEW_CUSTOM_ITEM_VARIANT,
                    MarketAnomalySeverity.MODERATE,
                    15,
                    "New custom item variant",
                    "This metadata fingerprint is newly observed and has limited completed-sale history."
            ));
        }

        ItemMatchType matchType = request.item().matchQuality().matchType();
        if (matchType == ItemMatchType.UNSUPPORTED) {
            indicators.add(indicator(
                    MarketAnomalyType.MISSING_VALUE_METADATA,
                    MarketAnomalySeverity.HIGH,
                    30,
                    "Missing value-affecting metadata",
                    "The item is unsupported because its value-affecting metadata cannot be matched safely."
            ));
        } else if (matchType == ItemMatchType.VISIBLE_METADATA
                && !MarketItemFamilyPolicy.isTool(request.item().itemId())) {
            indicators.add(indicator(
                    MarketAnomalyType.MISSING_VALUE_METADATA,
                    MarketAnomalySeverity.MODERATE,
                    20,
                    "API-visible metadata only",
                    "The item is matched by visible API metadata; hidden durability or component data may differ."
            ));
        } else if (matchType == ItemMatchType.APPROXIMATE) {
            indicators.add(indicator(
                    MarketAnomalyType.MISSING_VALUE_METADATA,
                    MarketAnomalySeverity.MODERATE,
                    20,
                    "Incomplete item metadata",
                    "The item uses approximate matching because metadata is incomplete or newly unclassified."
            ));
        }
    }

    private static MarketRiskLevel level(int score, ManipulationRiskConfig config) {
        if (score >= config.severeRiskScore()) {
            return MarketRiskLevel.SEVERE;
        }
        if (score >= config.highRiskScore()) {
            return MarketRiskLevel.HIGH;
        }
        if (score >= config.moderateRiskScore()) {
            return MarketRiskLevel.MODERATE;
        }
        return MarketRiskLevel.LOW;
    }

    private static RiskEffects effects(MarketRiskLevel level) {
        return switch (level) {
            case LOW -> new RiskEffects(0, 100, BigDecimal.ONE, false);
            case MODERATE -> new RiskEffects(5, 85, new BigDecimal("1.10"), false);
            case HIGH -> new RiskEffects(15, 60, new BigDecimal("1.25"), true);
            case SEVERE -> new RiskEffects(25, 25, new BigDecimal("1.50"), true);
            case UNKNOWN -> new RiskEffects(10, 35, new BigDecimal("1.25"), true);
        };
    }

    private static String severeRejection(int score, List<MarketAnomalyIndicator> indicators) {
        String evidence = indicators.stream()
                .sorted(Comparator.comparingInt(MarketAnomalyIndicator::riskPoints).reversed())
                .map(MarketAnomalyIndicator::label)
                .limit(4)
                .reduce((left, right) -> left + "; " + right)
                .orElse("insufficient trustworthy market evidence");
        return "Rejected: severe market anomaly risk (score " + score + "). Review required: "
                + evidence + ".";
    }

    private static MarketAnomalyIndicator indicator(
            MarketAnomalyType type,
            MarketAnomalySeverity severity,
            int points,
            String label,
            String explanation
    ) {
        return new MarketAnomalyIndicator(type, severity, points, label, explanation);
    }

    private static BigDecimal percentage(int part, int total) {
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
    }

    private static BigDecimal deviationPercent(BigDecimal value, BigDecimal baseline) {
        return value.subtract(baseline).abs()
                .divide(baseline, StatisticalMath.MATH_CONTEXT)
                .multiply(ONE_HUNDRED, StatisticalMath.MATH_CONTEXT);
    }

    private static String display(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0).toPlainString() : rounded.toPlainString();
    }

    private record ParticipantPair(String seller, String buyer) {
    }

    private record PriceGroup(BigDecimal price, int count) {
    }

    private record RiskEffects(
            int confidenceReduction,
            int confidenceCap,
            BigDecimal safetyMultiplier,
            boolean suppressSoundAlerts
    ) {
    }
}
