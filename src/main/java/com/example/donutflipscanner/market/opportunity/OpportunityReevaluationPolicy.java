package com.example.donutflipscanner.market.opportunity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OpportunityReevaluationPolicy {
    public ReevaluationDecision decide(
            OpportunityEvaluationRequest request,
            OpportunityRevision candidate,
            Optional<OpportunityRevision> previous,
            Optional<Instant> lastEvaluatedAt,
            Duration staleInterval,
            Instant now
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidate, "candidate");
        previous = Objects.requireNonNullElse(previous, Optional.empty());
        lastEvaluatedAt = Objects.requireNonNullElse(lastEvaluatedAt, Optional.empty());
        Objects.requireNonNull(staleInterval, "staleInterval");
        Objects.requireNonNull(now, "now");
        if (previous.isEmpty()) {
            return new ReevaluationDecision(true, List.of(ReevaluationReason.NEW_LISTING));
        }

        OpportunityRevision prior = previous.orElseThrow();
        List<ReevaluationReason> reasons = new ArrayList<>();
        if (candidate.listingPrice().compareTo(prior.listingPrice()) != 0) {
            reasons.add(ReevaluationReason.LISTING_PRICE_CHANGED);
        }
        if (candidate.listingState() != prior.listingState()) {
            reasons.add(ReevaluationReason.LISTING_STATE_CHANGED);
        }
        if (candidate.completedSalesVersion() != prior.completedSalesVersion()) {
            reasons.add(ReevaluationReason.COMPLETED_SALES_CHANGED);
        }
        if (!candidate.configurationVersion().equals(prior.configurationVersion())) {
            reasons.add(ReevaluationReason.CONFIGURATION_CHANGED);
        }
        if (!candidate.filterVersion().equals(prior.filterVersion())) {
            reasons.add(ReevaluationReason.FILTERS_CHANGED);
        }
        if (lastEvaluatedAt.isPresent()
                && !Duration.between(lastEvaluatedAt.orElseThrow(), now).isNegative()
                && Duration.between(lastEvaluatedAt.orElseThrow(), now).compareTo(staleInterval) >= 0) {
            reasons.add(ReevaluationReason.MARKET_DATA_STALE);
        }
        if (request.scannerRestarted() && request.listing().state().active()) {
            reasons.add(ReevaluationReason.ACTIVE_LISTING_AFTER_RESTART);
        }
        if (reasons.isEmpty()) {
            return new ReevaluationDecision(false, List.of(ReevaluationReason.UNCHANGED));
        }
        return new ReevaluationDecision(true, reasons);
    }
}
