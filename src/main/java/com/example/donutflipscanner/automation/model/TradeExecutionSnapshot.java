package com.example.donutflipscanner.automation.model;

import java.util.Objects;
import java.util.Optional;
import java.math.BigDecimal;

public record TradeExecutionSnapshot(
        boolean sessionArmed,
        Optional<String> armedServer,
        boolean emergencyStopped,
        int purchasesThisSession,
        BigDecimal openExposure,
        Optional<String> activeExecutionId,
        Optional<TradeExecutionState> activeState,
        String statusMessage
) {
    public TradeExecutionSnapshot {
        armedServer = Objects.requireNonNullElse(armedServer, Optional.empty());
        activeExecutionId = Objects.requireNonNullElse(activeExecutionId, Optional.empty());
        activeState = Objects.requireNonNullElse(activeState, Optional.empty());
        Objects.requireNonNull(openExposure, "openExposure");
        statusMessage = Objects.requireNonNullElse(statusMessage, "");
    }

    public static TradeExecutionSnapshot idle() {
        return new TradeExecutionSnapshot(
                false, Optional.empty(), false, 0, BigDecimal.ZERO,
                Optional.empty(), Optional.empty(), "Automation is disarmed."
        );
    }
}
