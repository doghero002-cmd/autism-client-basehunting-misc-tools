package com.example.donutflipscanner.database.entity;

import java.time.Instant;
import java.util.Objects;

public record ItemFingerprintEntity(
        String fingerprint,
        String baseItemId,
        String matchType,
        String normalizedMetadata,
        Instant createdAt
) {
    public ItemFingerprintEntity {
        fingerprint = EntityChecks.text(fingerprint, "fingerprint");
        baseItemId = EntityChecks.text(baseItemId, "baseItemId");
        matchType = EntityChecks.text(matchType, "matchType");
        Objects.requireNonNull(normalizedMetadata, "normalizedMetadata");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
