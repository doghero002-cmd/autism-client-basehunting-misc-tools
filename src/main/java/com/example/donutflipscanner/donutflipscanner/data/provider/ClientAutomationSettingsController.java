package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.balance.BalanceProvider;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.data.AutomationSettingsSnapshot;
import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.OpportunityActionResult;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** UI adapter around the provider-neutral execution state machine. */
public class ClientAutomationSettingsController implements AutomationSettingsController {
    private final Supplier<AutomationConfig> configuration;
    private final Function<AutomationConfig, CompletableFuture<Void>> updateConfiguration;
    private final Supplier<String> currentServer;
    private final TradeAutomationCoordinator coordinator;
    private final BalanceProvider balanceProvider;
    private final Clock clock;

    public ClientAutomationSettingsController(
            Supplier<AutomationConfig> configuration,
            Function<AutomationConfig, CompletableFuture<Void>> updateConfiguration,
            Supplier<String> currentServer,
            TradeAutomationCoordinator coordinator
    ) {
        this(configuration, updateConfiguration, currentServer, coordinator,
                BalanceProvider.unavailable(), Clock.systemUTC());
    }

    public ClientAutomationSettingsController(
            Supplier<AutomationConfig> configuration,
            Function<AutomationConfig, CompletableFuture<Void>> updateConfiguration,
            Supplier<String> currentServer,
            TradeAutomationCoordinator coordinator,
            BalanceProvider balanceProvider
    ) {
        this(configuration, updateConfiguration, currentServer, coordinator,
                balanceProvider, Clock.systemUTC());
    }

    ClientAutomationSettingsController(
            Supplier<AutomationConfig> configuration,
            Function<AutomationConfig, CompletableFuture<Void>> updateConfiguration,
            Supplier<String> currentServer,
            TradeAutomationCoordinator coordinator,
            BalanceProvider balanceProvider,
            Clock clock
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.updateConfiguration = Objects.requireNonNull(updateConfiguration, "updateConfiguration");
        this.currentServer = Objects.requireNonNull(currentServer, "currentServer");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.balanceProvider = Objects.requireNonNull(balanceProvider, "balanceProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AutomationSettingsSnapshot snapshot() {
        String server = normalizedCurrentServer();
        coordinator.onServerChanged(server);
        AutomationConfig config = configuration.get();
        String warning = config.mode() == AutomationMode.DRY_RUN
                ? "Dry run records a simulation and never clicks, buys, or lists."
                : "Real modes require an exact allowlist entry and per-session authorization.";
        return new AutomationSettingsSnapshot(config, coordinator.snapshot(), balanceProvider.snapshot(),
                server.isBlank() ? "Not connected" : server, warning);
    }

    @Override
    public CompletableFuture<OpportunityActionResult> runDryRun(FlipOpportunity opportunity) {
        return coordinator.submit(request(opportunity, AutomationMode.DRY_RUN)).thenApply(this::actionResult);
    }

    @Override
    public CompletableFuture<OpportunityActionResult> executeConfiguredMode(FlipOpportunity opportunity) {
        AutomationMode mode = configuration.get().mode();
        if (mode == AutomationMode.DISABLED) {
            return CompletableFuture.completedFuture(new OpportunityActionResult(
                    false, "Automation is disabled. Use dry run or enable an authorized mode."
            ));
        }
        return coordinator.submit(request(opportunity, mode)).thenApply(this::actionResult);
    }

    @Override
    public CompletableFuture<OpportunityActionResult> confirmPending() {
        return coordinator.snapshot().activeExecutionId()
                .map(coordinator::confirm)
                .orElseGet(() -> CompletableFuture.completedFuture(new TradeExecutionResult(
                        "none", com.example.donutflipscanner.automation.model.TradeExecutionState.FAILED,
                        false, false, false, "No execution is waiting for confirmation.", Optional.empty()
                )))
                .thenApply(this::actionResult);
    }

    @Override
    public CompletableFuture<Void> setEnabled(boolean enabled) {
        AutomationConfig current = configuration.get();
        return updateConfiguration.apply(copy(current, enabled, current.mode(), current.allowedServerAddresses()));
    }

    @Override
    public CompletableFuture<Void> setMode(AutomationMode mode) {
        AutomationConfig current = configuration.get();
        coordinator.disarm("Mode changed; session authorization was cleared.");
        return updateConfiguration.apply(copy(current, current.enabled(), Objects.requireNonNull(mode, "mode"),
                current.allowedServerAddresses()));
    }

    @Override
    public CompletableFuture<Void> allowCurrentServer() {
        String server = normalizedCurrentServer();
        if (server.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to a server."));
        }
        AutomationConfig current = configuration.get();
        Set<String> allowed = new HashSet<>(current.allowedServerAddresses());
        allowed.add(server);
        coordinator.disarm("Allowlist changed; session authorization was cleared.");
        return updateConfiguration.apply(copy(current, current.enabled(), current.mode(), allowed));
    }

    @Override
    public boolean armCurrentServer(String exactConfirmation) {
        return coordinator.armSession(normalizedCurrentServer(), exactConfirmation);
    }

    @Override
    public void disarm() {
        coordinator.disarm("Disarmed by user.");
    }

    @Override
    public void emergencyStop() {
        coordinator.emergencyStop("Emergency stop activated by user.");
    }

    @Override
    public void clearEmergencyStop() {
        coordinator.clearEmergencyStop();
    }

    private TradeExecutionRequest request(FlipOpportunity value, AutomationMode mode) {
        Objects.requireNonNull(value, "opportunity");
        BigDecimal price = positive(value.listingPrice());
        BigDecimal fair = positive(Math.max(value.fairValue(), value.listingPrice()));
        long configuredAge = Math.max(1, configuration.get().maximumListingAgeSeconds());
        return new TradeExecutionRequest(
                UUID.randomUUID().toString(), value.opportunityId(), value.listingKey(),
                value.itemFingerprint(), value.itemId(), Math.max(1, value.count()),
                value.seller().isBlank() || value.seller().equalsIgnoreCase("hidden")
                        ? Optional.empty() : Optional.of(value.seller()),
                price, fair, BigDecimal.valueOf(value.estimatedProfit()),
                BigDecimal.valueOf(value.roiPercent()),
                (int) Math.max(0, Math.min(100, Math.round(value.confidencePercent()))),
                Math.max(0, value.comparableSales()),
                value.riskLevel(),
                value.detectedAt(), price, configuredAge, mode,
                Optional.of(value.itemName()), value.normalizedItemMetadata()
        );
    }

    private OpportunityActionResult actionResult(TradeExecutionResult result) {
        return new OpportunityActionResult(result.successful(), result.message());
    }

    private String normalizedCurrentServer() {
        return Objects.requireNonNullElse(currentServer.get(), "").strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static BigDecimal positive(long value) {
        return BigDecimal.valueOf(Math.max(1L, value));
    }

    private static AutomationConfig copy(
            AutomationConfig value, boolean enabled, AutomationMode mode, Set<String> allowedServers
    ) {
        return new AutomationConfig(
                enabled, mode, allowedServers, value.maximumPurchasePrice(), value.maximumOpenExposure(),
                value.maximumPurchasesPerSession(), value.minimumConfidence(), value.minimumComparableSales(),
                value.minimumNetProfit(), value.minimumRoi(), value.maximumListingAgeSeconds(),
                value.purchaseCooldownSeconds(), value.relistPricingStrategy(), value.targetRoi(),
                value.fixedMarkupPercent(), value.requireInventoryVerification(),
                value.requireBalanceVerification(), value.requireListingVerification(),
                value.showExecutionNotifications()
                , value.interactionProfile()
        );
    }
}
