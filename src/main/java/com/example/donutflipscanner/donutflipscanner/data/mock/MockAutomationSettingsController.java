package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.data.provider.ClientAutomationSettingsController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class MockAutomationSettingsController extends ClientAutomationSettingsController {
    private static AtomicReference<AutomationConfig> configuration() {
        return new AtomicReference<>(AutomationConfig.defaults());
    }

    public MockAutomationSettingsController() {
        this(configuration());
    }

    private MockAutomationSettingsController(AtomicReference<AutomationConfig> configuration) {
        super(configuration::get, updated -> {
            configuration.set(updated);
            return CompletableFuture.completedFuture(null);
        }, () -> "", new TradeAutomationCoordinator(configuration::get));
    }
}
