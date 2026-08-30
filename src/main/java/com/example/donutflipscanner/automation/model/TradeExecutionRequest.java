package com.example.donutflipscanner.automation.model;

import com.example.donutflipscanner.market.risk.MarketRiskLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TradeExecutionRequest(
        String executionId,
        String opportunityId,
        String listingKey,
        String itemFingerprint,
        String itemId,
        int expectedItemCount,
        Optional<String> expectedSeller,
        BigDecimal expectedListingPrice,
        BigDecimal conservativeFairValue,
        BigDecimal estimatedNetProfit,
        BigDecimal roiPercent,
        int confidence,
        int comparableSales,
        MarketRiskLevel riskLevel,
        Instant opportunityDetectedAt,
        BigDecimal maximumAcceptablePurchasePrice,
        long maximumListingAgeSeconds,
        AutomationMode requestedMode,
        Optional<String> expectedItemName,
        Optional<String> normalizedItemMetadata
) {
    public TradeExecutionRequest {
        executionId = required(executionId, "executionId");
        opportunityId = required(opportunityId, "opportunityId");
        listingKey = required(listingKey, "listingKey");
        itemFingerprint = required(itemFingerprint, "itemFingerprint");
        itemId = required(itemId, "itemId");
        expectedSeller = Objects.requireNonNullElse(expectedSeller, Optional.empty());
        expectedListingPrice = positive(expectedListingPrice, "expectedListingPrice");
        conservativeFairValue = positive(conservativeFairValue, "conservativeFairValue");
        estimatedNetProfit = Objects.requireNonNull(estimatedNetProfit, "estimatedNetProfit");
        roiPercent = Objects.requireNonNull(roiPercent, "roiPercent");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(opportunityDetectedAt, "opportunityDetectedAt");
        maximumAcceptablePurchasePrice = positive(
                maximumAcceptablePurchasePrice, "maximumAcceptablePurchasePrice"
        );
        Objects.requireNonNull(requestedMode, "requestedMode");
        expectedItemName = boundedOptional(expectedItemName, "expectedItemName", 128);
        normalizedItemMetadata = boundedOptional(
                normalizedItemMetadata, "normalizedItemMetadata", 1_048_576
        );
        if (expectedItemCount < 1 || confidence < 0 || confidence > 100
                || comparableSales < 0 || maximumListingAgeSeconds < 1) {
            throw new IllegalArgumentException("execution counts, confidence, or listing age are invalid");
        }
    }

    public TradeExecutionRequest(
            String executionId,
            String opportunityId,
            String listingKey,
            String itemFingerprint,
            String itemId,
            int expectedItemCount,
            Optional<String> expectedSeller,
            BigDecimal expectedListingPrice,
            BigDecimal conservativeFairValue,
            BigDecimal estimatedNetProfit,
            BigDecimal roiPercent,
            int confidence,
            int comparableSales,
            MarketRiskLevel riskLevel,
            Instant opportunityDetectedAt,
            BigDecimal maximumAcceptablePurchasePrice,
            long maximumListingAgeSeconds,
            AutomationMode requestedMode
    ) {
        this(executionId, opportunityId, listingKey, itemFingerprint, itemId,
                expectedItemCount, expectedSeller, expectedListingPrice,
                conservativeFairValue, estimatedNetProfit, roiPercent, confidence,
                comparableSales, riskLevel, opportunityDetectedAt,
                maximumAcceptablePurchasePrice, maximumListingAgeSeconds, requestedMode,
                Optional.empty(), Optional.empty());
    }

    public AuctionListingCandidate expectedCandidate() {
        return new AuctionListingCandidate(
                listingKey, itemFingerprint, itemId, expectedItemCount, expectedSeller, expectedListingPrice
        );
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Optional<String> boundedOptional(
            Optional<String> value, String name, int maximumLength
    ) {
        Optional<String> safe = value == null ? Optional.empty()
                : value.map(String::strip).filter(text -> !text.isEmpty());
        safe.ifPresent(text -> {
            if (text.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is too long");
            }
        });
        return safe;
    }
}
