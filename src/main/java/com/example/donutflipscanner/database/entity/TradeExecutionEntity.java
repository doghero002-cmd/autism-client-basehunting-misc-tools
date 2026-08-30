package com.example.donutflipscanner.database.entity;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TradeExecutionEntity(
        String executionId,
        String opportunityId,
        String listingKey,
        AutomationMode mode,
        TradeExecutionState state,
        String itemId,
        String itemFingerprint,
        int expectedItemCount,
        BigDecimal expectedListingPrice,
        Optional<BigDecimal> relistPrice,
        boolean purchaseConfirmed,
        boolean listingConfirmed,
        String statusMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public TradeExecutionEntity {
        executionId = EntityChecks.text(executionId, "executionId");
        opportunityId = EntityChecks.text(opportunityId, "opportunityId");
        listingKey = EntityChecks.text(listingKey, "listingKey");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        itemId = EntityChecks.text(itemId, "itemId");
        itemFingerprint = EntityChecks.text(itemFingerprint, "itemFingerprint");
        if (expectedItemCount < 1) {
            throw new IllegalArgumentException("expectedItemCount must be positive");
        }
        expectedListingPrice = EntityChecks.nonNegative(expectedListingPrice, "expectedListingPrice");
        relistPrice = EntityChecks.optional(relistPrice);
        relistPrice.ifPresent(value -> EntityChecks.nonNegative(value, "relistPrice"));
        statusMessage = Objects.requireNonNullElse(statusMessage, "");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
