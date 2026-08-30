package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import com.example.donutflipscanner.diagnostics.PerformanceOperation;
import com.example.donutflipscanner.market.confidence.ConfidenceBreakdown;
import com.example.donutflipscanner.market.confidence.ConfidenceCalculationRequest;
import com.example.donutflipscanner.market.confidence.ConfidenceCalculator;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.profit.ItemProfitThresholds;
import com.example.donutflipscanner.market.profit.ProfitCalculator;
import com.example.donutflipscanner.market.profit.ProfitEvaluation;
import com.example.donutflipscanner.market.profit.ProfitEvaluationRequest;
import com.example.donutflipscanner.market.risk.ManipulationRiskAssessment;
import com.example.donutflipscanner.market.risk.ManipulationRiskDetector;
import com.example.donutflipscanner.market.risk.ManipulationRiskRequest;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import com.example.donutflipscanner.market.value.FairValueEstimate;
import com.example.donutflipscanner.market.value.FairValueEstimator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic Chunk 9 evaluation pipeline. It performs no I/O and no game actions. */
public final class OpportunityEvaluator {
    private final OpportunityIdFactory idFactory;
    private final ManipulationRiskDetector riskDetector;
    private final FairValueEstimator fairValueEstimator;
    private final ProfitCalculator profitCalculator;
    private final ConfidenceCalculator confidenceCalculator;
    private final PerformanceMetrics performanceMetrics;

    public OpportunityEvaluator() {
        this(new OpportunityIdFactory(), new ManipulationRiskDetector(), new FairValueEstimator(),
                new ProfitCalculator(), new ConfidenceCalculator(), new PerformanceMetrics());
    }

    public OpportunityEvaluator(PerformanceMetrics performanceMetrics) {
        this(new OpportunityIdFactory(), new ManipulationRiskDetector(), new FairValueEstimator(),
                new ProfitCalculator(), new ConfidenceCalculator(), performanceMetrics);
    }

    OpportunityEvaluator(
            OpportunityIdFactory idFactory,
            ManipulationRiskDetector riskDetector,
            FairValueEstimator fairValueEstimator,
            ProfitCalculator profitCalculator,
            ConfidenceCalculator confidenceCalculator,
            PerformanceMetrics performanceMetrics
    ) {
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
        this.riskDetector = Objects.requireNonNull(riskDetector, "riskDetector");
        this.fairValueEstimator = Objects.requireNonNull(fairValueEstimator, "fairValueEstimator");
        this.profitCalculator = Objects.requireNonNull(profitCalculator, "profitCalculator");
        this.confidenceCalculator = Objects.requireNonNull(confidenceCalculator, "confidenceCalculator");
        this.performanceMetrics = Objects.requireNonNull(performanceMetrics, "performanceMetrics");
    }

    public OpportunityEvaluation evaluate(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now
    ) {
        return evaluate(request, config, now, Optional.empty());
    }

    OpportunityEvaluation evaluate(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now,
            Optional<OpportunityState> previousState
    ) {
        return performanceMetrics.measure(PerformanceOperation.LISTING_EVALUATION, () ->
                evaluateUnmeasured(request, config, now, previousState));
    }

    private OpportunityEvaluation evaluateUnmeasured(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now,
            Optional<OpportunityState> previousState
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");
        previousState = Objects.requireNonNullElse(previousState, Optional.empty());
        ListingEntity listing = request.listing();
        String fingerprint = request.item().fingerprint().sha256();
        String opportunityId = idFactory.create(listing.listingKey(), config.evaluationVersion(), fingerprint);
        ItemEvaluationProfile profile = request.itemProfile().orElse(ItemEvaluationProfile.enabledDefaults());
        int minimumConfidence = profile.minimumConfidence().orElse(config.thresholds().minimumConfidence());
        int minimumComparableSales = profile.minimumComparableSales()
                .orElse(config.thresholds().minimumComparableSales());
        int acceptedSales = request.market().primary().comparableSaleCount();
        int rejectedSales = request.market().primary().comparableSales().rejected().size();
        List<String> passes = new ArrayList<>();
        List<OpportunityRejection> rejections = new ArrayList<>();

        FilterDecision filterDecision = new FilterDecision(
                false, "Filter evaluation did not run because listing validation failed."
        );
        if (!validListingMapping(request)) {
            rejections.add(rejection(OpportunityRejectionCode.INVALID_LISTING,
                    "Listing identity, item fingerprint, item ID, count, and market snapshot must agree."));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections,
                    OpportunityState.REJECTED_EVALUATION);
        }
        passes.add("Listing and normalized item data are internally consistent.");

        if (listing.state() == ListingState.EXPIRED) {
            rejections.add(rejection(OpportunityRejectionCode.LISTING_EXPIRED, "Listing has expired."));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections, OpportunityState.EXPIRED);
        }
        if (!listing.state().active()) {
            rejections.add(rejection(OpportunityRejectionCode.LISTING_NOT_ACTIVE,
                    "Listing is no longer active: " + listing.state() + "."));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections,
                    OpportunityState.NO_LONGER_AVAILABLE);
        }
        passes.add("Listing is active.");

        filterDecision = config.filterPolicy().evaluate(request.item().itemId());
        if (!filterDecision.allowed()) {
            OpportunityRejectionCode code = config.filterPolicy().mode() == ItemFilterMode.WHITELIST_ONLY
                    ? OpportunityRejectionCode.ITEM_NOT_WHITELISTED
                    : OpportunityRejectionCode.ITEM_BLACKLISTED;
            rejections.add(rejection(code, filterDecision.explanation()));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections,
                    OpportunityState.REJECTED_BY_FILTER);
        }
        passes.add(filterDecision.explanation());

        if (!profile.enabled()) {
            rejections.add(rejection(OpportunityRejectionCode.ITEM_PROFILE_DISABLED,
                    "The item-specific evaluation profile is disabled."));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections,
                    OpportunityState.REJECTED_BY_FILTER);
        }
        if (!config.supportedItemPolicy().supports(request.item().matchQuality().matchType())) {
            rejections.add(rejection(OpportunityRejectionCode.ITEM_MATCH_UNSUPPORTED,
                    "Item match type " + request.item().matchQuality().matchType()
                            + " is not enabled by the supported-item policy."));
            return early(request, config, now, opportunityId, filterDecision, profile,
                    minimumConfidence, minimumComparableSales, passes, rejections,
                    OpportunityState.REJECTED_BY_FILTER);
        }
        passes.add("Item matching policy accepts " + request.item().matchQuality().matchType() + ".");

        ManipulationRiskAssessment risk = riskDetector.assess(
                new ManipulationRiskRequest(request.item(), request.market(), request.variantKnownAge()),
                config.riskConfig()
        );
        FairValueEstimate fairValue = risk.applySafetyBufferGuidance(
                fairValueEstimator.estimate(request.item(), request.market(), config.fairValueConfig())
        );
        Optional<ItemProfitThresholds> itemProfitThresholds = request.itemProfile()
                .map(ItemEvaluationProfile::profitThresholds);
        ProfitEvaluation profit = profitCalculator.evaluate(new ProfitEvaluationRequest(
                fingerprint, listing.listingPrice(), fairValue, config.profitConfig(),
                itemProfitThresholds, request.currentOpenExposure()
        ));
        Duration listingAge = nonNegativeBetween(listing.listedAt().orElse(listing.firstSeenAt()), now);
        ConfidenceBreakdown confidence = confidenceCalculator.calculate(
                new ConfidenceCalculationRequest(
                        request.item(), request.market(), listingAge, request.marketSnapshotAge(),
                        Optional.of(risk.confidenceConstraint())
                ),
                config.confidenceConfig()
        );

        if (risk.rejected()) {
            rejections.add(rejection(OpportunityRejectionCode.SEVERE_MARKET_RISK,
                    risk.rejectionExplanation().orElse("Severe market anomaly risk.")));
        } else {
            passes.add("Market anomaly assessment is below the severe rejection level ("
                    + risk.riskLevel() + ", score " + risk.riskScore() + ").");
        }
        if (!fairValue.sufficientData()) {
            rejections.add(rejection(OpportunityRejectionCode.FAIR_VALUE_UNAVAILABLE,
                    "Comparable evidence is insufficient for a conservative fair value."));
        } else {
            passes.add("A conservative fair value was produced from completed-sale evidence.");
        }
        if (!profit.accepted()) {
            profit.rejections().forEach(reason -> rejections.add(rejection(
                    OpportunityRejectionCode.PROFIT_REQUIREMENT_FAILED, reason.message()
            )));
        } else {
            passes.add("Gross profit, net profit, ROI, purchase price, and capital limits pass.");
        }
        if (confidence.totalScore() < minimumConfidence) {
            rejections.add(rejection(OpportunityRejectionCode.CONFIDENCE_BELOW_MINIMUM,
                    "Confidence " + confidence.totalScore() + "% is below required "
                            + minimumConfidence + "%."));
        } else {
            passes.add("Confidence " + confidence.totalScore() + "% meets required "
                    + minimumConfidence + "%.");
        }
        if (acceptedSales < minimumComparableSales) {
            rejections.add(rejection(OpportunityRejectionCode.COMPARABLE_SALES_BELOW_MINIMUM,
                    "Accepted comparable sales " + acceptedSales + " are below required "
                            + minimumComparableSales + "."));
        } else {
            passes.add("Accepted comparable sales " + acceptedSales + " meet required "
                    + minimumComparableSales + ".");
        }

        boolean accepted = rejections.isEmpty();
        OpportunityState state = resolveState(accepted, previousState);
        LinkedHashSet<AlertSuppressionReason> suppressions = alertSuppressions(
                accepted, state, risk, config.thresholds().maximumAlertRisk()
        );
        OpportunityExplanation explanation = new OpportunityExplanation(
                filterDecision, request.item().matchQuality().matchType(), Optional.of(risk),
                Optional.of(fairValue), Optional.of(profit), Optional.of(confidence),
                acceptedSales, rejectedSales, minimumConfidence, minimumComparableSales,
                profile.hasOverrides(), passes, rejections
        );
        return result(request, config, now, opportunityId, state, accepted,
                suppressions.isEmpty(), List.copyOf(suppressions), explanation);
    }

    private OpportunityEvaluation early(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now,
            String opportunityId,
            FilterDecision filterDecision,
            ItemEvaluationProfile profile,
            int minimumConfidence,
            int minimumComparableSales,
            List<String> passes,
            List<OpportunityRejection> rejections,
            OpportunityState state
    ) {
        OpportunityExplanation explanation = new OpportunityExplanation(
                filterDecision, request.item().matchQuality().matchType(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), request.market().primary().comparableSaleCount(),
                request.market().primary().comparableSales().rejected().size(), minimumConfidence,
                minimumComparableSales, profile.hasOverrides(), passes, rejections
        );
        AlertSuppressionReason reason = switch (state) {
            case EXPIRED -> AlertSuppressionReason.OPPORTUNITY_EXPIRED;
            case NO_LONGER_AVAILABLE -> AlertSuppressionReason.OPPORTUNITY_NO_LONGER_AVAILABLE;
            default -> AlertSuppressionReason.EVALUATION_REJECTED;
        };
        return result(request, config, now, opportunityId, state, false, false, List.of(reason), explanation);
    }

    private static OpportunityEvaluation result(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now,
            String opportunityId,
            OpportunityState state,
            boolean accepted,
            boolean alertEligible,
            List<AlertSuppressionReason> alertSuppressions,
            OpportunityExplanation explanation
    ) {
        return new OpportunityEvaluation(
                opportunityId, request.listing().listingKey(), request.item().fingerprint().sha256(),
                request.item().itemId(), config.evaluationVersion(), now, request.listing().listingPrice(),
                request.listing().itemCount(), state, accepted, alertEligible, alertSuppressions, explanation
        );
    }

    private static boolean validListingMapping(OpportunityEvaluationRequest request) {
        String fingerprint = request.item().fingerprint().sha256();
        return request.listing().itemFingerprint().equals(fingerprint)
                && request.listing().rawItemId().equals(request.item().itemId())
                && request.market().primary().itemFingerprint().equals(fingerprint)
                && (request.item().stackCount().isEmpty()
                    || request.item().stackCount().getAsInt() == request.listing().itemCount());
    }

    private static OpportunityState resolveState(boolean accepted, Optional<OpportunityState> previousState) {
        if (previousState.isPresent()) {
            OpportunityState prior = previousState.orElseThrow();
            if (prior == OpportunityState.DISMISSED || prior == OpportunityState.PURCHASED_MANUALLY
                    || prior == OpportunityState.REVIEWED) {
                return prior;
            }
        }
        return accepted ? OpportunityState.NEW : OpportunityState.REJECTED_EVALUATION;
    }

    private static LinkedHashSet<AlertSuppressionReason> alertSuppressions(
            boolean accepted,
            OpportunityState state,
            ManipulationRiskAssessment risk,
            MarketRiskLevel maximumAlertRisk
    ) {
        LinkedHashSet<AlertSuppressionReason> reasons = new LinkedHashSet<>();
        if (!accepted) {
            reasons.add(AlertSuppressionReason.EVALUATION_REJECTED);
        }
        if (riskRank(risk.riskLevel()) > riskRank(maximumAlertRisk)) {
            reasons.add(AlertSuppressionReason.RISK_ABOVE_ALERT_MAXIMUM);
        }
        if (risk.suppressSoundAlerts()) {
            reasons.add(AlertSuppressionReason.RISK_REQUIRES_SILENT_DISPLAY);
        }
        switch (state) {
            case DISMISSED -> reasons.add(AlertSuppressionReason.OPPORTUNITY_DISMISSED);
            case EXPIRED -> reasons.add(AlertSuppressionReason.OPPORTUNITY_EXPIRED);
            case NO_LONGER_AVAILABLE -> reasons.add(AlertSuppressionReason.OPPORTUNITY_NO_LONGER_AVAILABLE);
            case PURCHASED_MANUALLY -> reasons.add(AlertSuppressionReason.OPPORTUNITY_FINAL_STATE);
            default -> { }
        }
        return reasons;
    }

    private static int riskRank(MarketRiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MODERATE -> 1;
            case HIGH -> 2;
            case SEVERE -> 3;
            case UNKNOWN -> 4;
        };
    }

    private static Duration nonNegativeBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static OpportunityRejection rejection(OpportunityRejectionCode code, String explanation) {
        return new OpportunityRejection(code, explanation);
    }
}
