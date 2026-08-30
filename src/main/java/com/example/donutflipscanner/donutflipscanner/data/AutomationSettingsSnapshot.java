package com.example.donutflipscanner.data;

import com.example.donutflipscanner.automation.model.TradeExecutionSnapshot;
import com.example.donutflipscanner.balance.BalanceSnapshot;
import com.example.donutflipscanner.configuration.AutomationConfig;

import java.util.Objects;

public record AutomationSettingsSnapshot(
        AutomationConfig configuration,
        TradeExecutionSnapshot execution,
        BalanceSnapshot balance,
        String currentServer,
        String warning
) {
    public AutomationSettingsSnapshot {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(balance, "balance");
        currentServer = Objects.requireNonNullElse(currentServer, "Not connected");
        warning = Objects.requireNonNullElse(warning, "");
    }
}
