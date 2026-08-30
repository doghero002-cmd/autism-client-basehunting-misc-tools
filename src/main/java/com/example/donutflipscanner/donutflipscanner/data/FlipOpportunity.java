package com.example.donutflipscanner.data;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.Optional;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;

/** Immutable opportunity-card snapshot. Rendering never queries the backend. */
public record FlipOpportunity(
        String opportunityId,
        String listingKey,
        String itemFingerprint,
        String itemId,
        String itemName,
        int count,
        long listingPrice,
        long fairValue,
        long estimatedProfit,
        double roiPercent,
        double confidencePercent,
        int comparableSales,
        MarketRiskLevel riskLevel,
        long recentLowPrice,
        long recentHighPrice,
        String liquidity,
        String listingAge,
        String seller,
        String state,
        Instant detectedAt,
        Instant lastVerifiedAt,
        List<String> explanation,
        List<String> warnings,
        Optional<String> normalizedItemMetadata
) {
    public FlipOpportunity {
        opportunityId = Objects.requireNonNull(opportunityId, "opportunityId");
        listingKey = Objects.requireNonNull(listingKey, "listingKey");
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        itemId = Objects.requireNonNull(itemId, "itemId");
        itemName = Objects.requireNonNull(itemName, "itemName");
        liquidity = Objects.requireNonNull(liquidity, "liquidity");
        listingAge = Objects.requireNonNull(listingAge, "listingAge");
        seller = Objects.requireNonNull(seller, "seller");
        state = Objects.requireNonNull(state, "state");
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        lastVerifiedAt = Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        explanation = List.copyOf(Objects.requireNonNull(explanation, "explanation"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        normalizedItemMetadata = Objects.requireNonNullElse(normalizedItemMetadata, Optional.empty());
    }

    public FlipOpportunity(
            String opportunityId,
            String listingKey,
            String itemFingerprint,
            String itemId,
            String itemName,
            int count,
            long listingPrice,
            long fairValue,
            long estimatedProfit,
            double roiPercent,
            double confidencePercent,
            int comparableSales,
            MarketRiskLevel riskLevel,
            long recentLowPrice,
            long recentHighPrice,
            String liquidity,
            String listingAge,
            String seller,
            String state,
            Instant detectedAt,
            Instant lastVerifiedAt,
            List<String> explanation,
            List<String> warnings
    ) {
        this(opportunityId, listingKey, itemFingerprint, itemId, itemName, count,
                listingPrice, fairValue, estimatedProfit, roiPercent, confidencePercent,
                comparableSales, riskLevel, recentLowPrice, recentHighPrice, liquidity,
                listingAge, seller, state, detectedAt, lastVerifiedAt, explanation, warnings,
                Optional.empty());
    }

    public FlipOpportunity(
            String opportunityId,
            String itemId,
            String itemName,
            int count,
            long listingPrice,
            long fairValue,
            long estimatedProfit,
            double roiPercent,
            double confidencePercent,
            int comparableSales,
            long recentLowPrice,
            long recentHighPrice,
            String liquidity,
            String listingAge,
            String seller,
            String state,
            List<String> explanation,
            List<String> warnings
    ) {
        this(opportunityId, opportunityId, itemId, itemId, itemName, count, listingPrice,
                fairValue, estimatedProfit, roiPercent, confidencePercent, comparableSales,
                MarketRiskLevel.LOW, recentLowPrice, recentHighPrice, liquidity, listingAge, seller, state,
                Instant.now(), Instant.now(), explanation, warnings, Optional.empty());
    }

    public FlipOpportunity(
            String opportunityId,
            String itemId,
            String itemName,
            int count,
            long listingPrice,
            long estimatedProfit
    ) {
        this(opportunityId, opportunityId, itemId, itemId, itemName, count, listingPrice,
                Math.max(0L, listingPrice + estimatedProfit), estimatedProfit,
                0.0D, 0.0D, 0, MarketRiskLevel.LOW, 0L, 0L, "Unknown", "Unknown", "Hidden", "NEW",
                Instant.now(), Instant.now(), List.of("Mock opportunity"), List.of(), Optional.empty());
    }
}
