package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ProfitRejection(
        ProfitRejectionCode code,
        String message,
        Optional<BigDecimal> actualValue,
        Optional<BigDecimal> requiredValue
) {
    public ProfitRejection {
        Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        actualValue = Objects.requireNonNullElse(actualValue, Optional.empty());
        requiredValue = Objects.requireNonNullElse(requiredValue, Optional.empty());
    }
}
