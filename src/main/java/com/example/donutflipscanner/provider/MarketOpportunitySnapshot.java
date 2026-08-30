package com.example.donutflipscanner.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;

public record MarketOpportunitySnapshot(
        String opportunityId,
        String listingKey,
        String itemFingerprint,
        String itemId,
        int itemCount,
        BigDecimal listingPrice,
        BigDecimal conservativeFairValue,
        BigDecimal estimatedProfit,
        BigDecimal roiPercent,
        BigDecimal confidencePercent,
        int comparableSales,
        MarketRiskLevel riskLevel,
        String state,
        Instant detectedAt,
        Optional<Instant> listedAt,
        Optional<String> sellerName,
        Instant lastVerifiedAt,
        Optional<String> rejectionReason,
        Optional<String> evaluationDetails,
        Optional<String> normalizedItemMetadata
) {
    public MarketOpportunitySnapshot {
        opportunityId = required(opportunityId, "opportunityId");
        listingKey = required(listingKey, "listingKey");
        itemFingerprint = required(itemFingerprint, "itemFingerprint");
        itemId = required(itemId, "itemId");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        Objects.requireNonNull(listingPrice, "listingPrice");
        Objects.requireNonNull(conservativeFairValue, "conservativeFairValue");
        Objects.requireNonNull(estimatedProfit, "estimatedProfit");
        Objects.requireNonNull(roiPercent, "roiPercent");
        Objects.requireNonNull(confidencePercent, "confidencePercent");
        if (comparableSales < 0) {
            throw new IllegalArgumentException("comparableSales must not be negative");
        }
        Objects.requireNonNull(riskLevel, "riskLevel");
        state = required(state, "state");
        Objects.requireNonNull(detectedAt, "detectedAt");
        listedAt = Objects.requireNonNullElse(listedAt, Optional.empty());
        sellerName = Objects.requireNonNullElse(sellerName, Optional.empty());
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        rejectionReason = Objects.requireNonNullElse(rejectionReason, Optional.empty());
        evaluationDetails = Objects.requireNonNullElse(evaluationDetails, Optional.empty());
        normalizedItemMetadata = Objects.requireNonNullElse(normalizedItemMetadata, Optional.empty());
    }

    public MarketOpportunitySnapshot(
            String opportunityId,
            String listingKey,
            String itemFingerprint,
            String itemId,
            int itemCount,
            BigDecimal listingPrice,
            BigDecimal conservativeFairValue,
            BigDecimal estimatedProfit,
            BigDecimal roiPercent,
            BigDecimal confidencePercent,
            int comparableSales,
            MarketRiskLevel riskLevel,
            String state,
            Instant detectedAt,
            Optional<Instant> listedAt,
            Optional<String> sellerName,
            Instant lastVerifiedAt,
            Optional<String> rejectionReason,
            Optional<String> evaluationDetails
    ) {
        this(opportunityId, listingKey, itemFingerprint, itemId, itemCount, listingPrice,
                conservativeFairValue, estimatedProfit, roiPercent, confidencePercent,
                comparableSales, riskLevel, state, detectedAt, listedAt, sellerName,
                lastVerifiedAt, rejectionReason, evaluationDetails, Optional.empty());
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
