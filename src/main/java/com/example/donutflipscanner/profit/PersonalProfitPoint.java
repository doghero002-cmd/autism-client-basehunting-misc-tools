package com.example.donutflipscanner.profit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PersonalProfitPoint(Instant soldAt, BigDecimal cumulativeProfit) {
    public PersonalProfitPoint {
        Objects.requireNonNull(soldAt, "soldAt");
        Objects.requireNonNull(cumulativeProfit, "cumulativeProfit");
    }
}
