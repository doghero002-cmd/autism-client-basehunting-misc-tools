package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.model.TradeExecutionState;

import java.time.Instant;

@FunctionalInterface
public interface TradeExecutionObserver {
    void onTransition(
            TradeExecutionRequest request,
            TradeExecutionState previous,
            TradeExecutionState current,
            String message,
            Instant transitionedAt
    );

    default void onOutcome(TradeExecutionRequest request, TradeExecutionResult result, Instant completedAt) {
    }

    static TradeExecutionObserver noOp() {
        return (request, previous, current, message, transitionedAt) -> { };
    }
}
