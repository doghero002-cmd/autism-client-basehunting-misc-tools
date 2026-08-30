package com.example.donutflipscanner.database.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record OpportunityEntity(
        String opportunityId,
        String listingKey,
        String itemFingerprint,
        Instant detectedAt,
        BigDecimal purchasePrice,
        BigDecimal fairValue,
        BigDecimal estimatedProfit,
        BigDecimal roiPercent,
        BigDecimal confidencePercent,
        String state,
        Optional<String> rejectionReason,
        Optional<String> evaluationJson,
        String evaluationVersion
) {
    public OpportunityEntity {
        opportunityId = EntityChecks.text(opportunityId, "opportunityId");
        listingKey = EntityChecks.text(listingKey, "listingKey");
        itemFingerprint = EntityChecks.text(itemFingerprint, "itemFingerprint");
        Objects.requireNonNull(detectedAt, "detectedAt");
        purchasePrice = EntityChecks.nonNegative(purchasePrice, "purchasePrice");
        fairValue = EntityChecks.nonNegative(fairValue, "fairValue");
        Objects.requireNonNull(estimatedProfit, "estimatedProfit");
        Objects.requireNonNull(roiPercent, "roiPercent");
        Objects.requireNonNull(confidencePercent, "confidencePercent");
        state = EntityChecks.text(state, "state");
        rejectionReason = EntityChecks.optional(rejectionReason);
        evaluationJson = EntityChecks.optional(evaluationJson);
        evaluationVersion = EntityChecks.text(evaluationVersion, "evaluationVersion");
    }
}
