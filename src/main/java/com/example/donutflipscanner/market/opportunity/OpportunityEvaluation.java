package com.example.donutflipscanner.market.opportunity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OpportunityEvaluation(
        String opportunityId,
        String listingKey,
        String itemFingerprint,
        String itemId,
        String evaluationVersion,
        Instant evaluatedAt,
        BigDecimal listingPrice,
        int itemCount,
        OpportunityState state,
        boolean accepted,
        boolean highPriorityAlertEligible,
        List<AlertSuppressionReason> alertSuppressions,
        OpportunityExplanation explanation
) {
    public OpportunityEvaluation {
        opportunityId = required(opportunityId, "opportunityId");
        listingKey = required(listingKey, "listingKey");
        itemFingerprint = required(itemFingerprint, "itemFingerprint");
        itemId = required(itemId, "itemId");
        evaluationVersion = required(evaluationVersion, "evaluationVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(listingPrice, "listingPrice");
        if (listingPrice.signum() < 0 || itemCount < 1) {
            throw new IllegalArgumentException("listing price and item count are invalid");
        }
        Objects.requireNonNull(state, "state");
        alertSuppressions = List.copyOf(Objects.requireNonNull(alertSuppressions, "alertSuppressions"));
        Objects.requireNonNull(explanation, "explanation");
        if (highPriorityAlertEligible && (!accepted || !alertSuppressions.isEmpty())) {
            throw new IllegalArgumentException("alert-eligible evaluations must be accepted and unsuppressed");
        }
    }

    public OpportunityEvaluation withAlertDecision(boolean eligible, List<AlertSuppressionReason> suppressions) {
        return new OpportunityEvaluation(
                opportunityId, listingKey, itemFingerprint, itemId, evaluationVersion, evaluatedAt,
                listingPrice, itemCount, state, accepted, eligible, suppressions, explanation
        );
    }

    public OpportunityEvaluation withState(OpportunityState updatedState) {
        List<AlertSuppressionReason> suppressions = switch (updatedState) {
            case DISMISSED -> List.of(AlertSuppressionReason.OPPORTUNITY_DISMISSED);
            case EXPIRED -> List.of(AlertSuppressionReason.OPPORTUNITY_EXPIRED);
            case NO_LONGER_AVAILABLE -> List.of(AlertSuppressionReason.OPPORTUNITY_NO_LONGER_AVAILABLE);
            default -> alertSuppressions;
        };
        return new OpportunityEvaluation(
                opportunityId, listingKey, itemFingerprint, itemId, evaluationVersion, evaluatedAt,
                listingPrice, itemCount, updatedState, accepted, false, suppressions, explanation
        );
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
