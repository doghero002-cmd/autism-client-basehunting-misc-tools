package com.example.donutflipscanner.market.item.model;

import java.util.Objects;

public record ItemFingerprint(String sha256, String canonicalMetadata) {
    public ItemFingerprint {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(canonicalMetadata, "canonicalMetadata");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
    }
}
