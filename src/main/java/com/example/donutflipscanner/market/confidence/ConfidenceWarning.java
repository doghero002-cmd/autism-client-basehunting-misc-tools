package com.example.donutflipscanner.market.confidence;

import java.util.Objects;

public record ConfidenceWarning(
        ConfidenceWarningCode code,
        String message
) {
    public ConfidenceWarning {
        Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }
}
