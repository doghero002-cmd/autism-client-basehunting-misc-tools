package com.example.donutflipscanner.api.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Documented subset of the DonutSMP player stats response needed for balance display. */
public record ApiPlayerStats(int status, BigDecimal money) {
    public ApiPlayerStats {
        money = Objects.requireNonNull(money, "money");
        if (status < 200 || status >= 300 || money.signum() < 0) {
            throw new IllegalArgumentException("player stats response is invalid");
        }
    }
}
