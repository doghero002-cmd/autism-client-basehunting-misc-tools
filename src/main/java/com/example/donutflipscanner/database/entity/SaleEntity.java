package com.example.donutflipscanner.database.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SaleEntity(
        String saleKey,
        Optional<String> remoteTransactionId,
        Optional<String> sellerUuid,
        Optional<String> sellerName,
        Optional<String> buyerUuid,
        Optional<String> buyerName,
        String itemFingerprint,
        String rawItemId,
        int itemCount,
        BigDecimal salePrice,
        Optional<BigDecimal> unitPrice,
        Instant soldAt,
        Instant importedAt,
        Optional<String> rawJson
) {
    public SaleEntity {
        saleKey = EntityChecks.text(saleKey, "saleKey");
        remoteTransactionId = EntityChecks.optional(remoteTransactionId);
        sellerUuid = EntityChecks.optional(sellerUuid);
        sellerName = EntityChecks.optional(sellerName);
        buyerUuid = EntityChecks.optional(buyerUuid);
        buyerName = EntityChecks.optional(buyerName);
        itemFingerprint = EntityChecks.text(itemFingerprint, "itemFingerprint");
        rawItemId = EntityChecks.text(rawItemId, "rawItemId");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        salePrice = EntityChecks.nonNegative(salePrice, "salePrice");
        unitPrice = EntityChecks.optional(unitPrice);
        unitPrice.ifPresent(value -> EntityChecks.nonNegative(value, "unitPrice"));
        Objects.requireNonNull(soldAt, "soldAt");
        Objects.requireNonNull(importedAt, "importedAt");
        rawJson = EntityChecks.optional(rawJson);
    }
}
