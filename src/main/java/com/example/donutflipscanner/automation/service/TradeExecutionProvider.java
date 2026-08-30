package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.model.TradeExecutionSnapshot;

import java.util.concurrent.CompletableFuture;

public interface TradeExecutionProvider {
    CompletableFuture<TradeExecutionResult> submit(TradeExecutionRequest request);

    CompletableFuture<Boolean> cancel(String executionId);

    TradeExecutionSnapshot snapshot();

    void emergencyStop(String reason);
}
