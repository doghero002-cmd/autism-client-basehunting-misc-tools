package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.data.AutomationSettingsSnapshot;
import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.OpportunityActionResult;

import java.util.concurrent.CompletableFuture;

public interface AutomationSettingsController {
    AutomationSettingsSnapshot snapshot();

    CompletableFuture<OpportunityActionResult> runDryRun(FlipOpportunity opportunity);

    CompletableFuture<OpportunityActionResult> executeConfiguredMode(FlipOpportunity opportunity);

    CompletableFuture<OpportunityActionResult> confirmPending();

    CompletableFuture<Void> setEnabled(boolean enabled);

    CompletableFuture<Void> setMode(AutomationMode mode);

    CompletableFuture<Void> allowCurrentServer();

    boolean armCurrentServer(String exactConfirmation);

    void disarm();

    void emergencyStop();

    void clearEmergencyStop();
}
