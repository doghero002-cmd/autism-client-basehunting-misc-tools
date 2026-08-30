package com.example.donutflipscanner.database.entity;

import java.time.Instant;
import java.util.Objects;

public record ScannerMetadataEntity(String key, String value, Instant updatedAt) {
    public ScannerMetadataEntity {
        key = EntityChecks.text(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
