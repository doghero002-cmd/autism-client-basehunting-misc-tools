package com.example.donutflipscanner.data;

import com.example.donutflipscanner.data.provider.ApiConnectionStatusProvider;
import com.example.donutflipscanner.data.provider.ItemFilterController;
import com.example.donutflipscanner.data.provider.ItemSearchProvider;
import com.example.donutflipscanner.data.provider.MarketStatisticsProvider;
import com.example.donutflipscanner.data.provider.MarketChartProvider;
import com.example.donutflipscanner.data.provider.OpportunityHistoryProvider;
import com.example.donutflipscanner.data.provider.OpportunityProvider;
import com.example.donutflipscanner.data.provider.NotificationSettingsController;
import com.example.donutflipscanner.data.provider.ScannerStatusController;
import com.example.donutflipscanner.data.provider.AutomationSettingsController;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Stable GUI provider bundle whose delegate can switch between mock and live mode. */
public final class ClientUiDataSourceRouter {
    private final AtomicReference<ClientUiDataSources> selected;
    private final ClientUiDataSources routed;

    public ClientUiDataSourceRouter(ClientUiDataSources initial) {
        selected = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        OpportunityProvider opportunities = new OpportunityProvider() {
            @Override
            public List<FlipOpportunity> getOpportunities() {
                return selected.get().opportunities().getOpportunities();
            }

            @Override
            public CompletableFuture<OpportunityActionResult> reviewManually(String opportunityId) {
                return selected.get().opportunities().reviewManually(opportunityId);
            }

            @Override
            public CompletableFuture<Boolean> dismiss(String opportunityId) {
                return selected.get().opportunities().dismiss(opportunityId);
            }

            @Override
            public CompletableFuture<Boolean> markPurchasedManually(String opportunityId) {
                return selected.get().opportunities().markPurchasedManually(opportunityId);
            }
        };
        ScannerStatusController scanner = new ScannerStatusController() {
            @Override
            public ScannerStatus getScannerStatus() {
                return selected.get().scannerStatus().getScannerStatus();
            }

            @Override
            public void setScannerEnabled(boolean enabled) {
                selected.get().scannerStatus().setScannerEnabled(enabled);
            }
        };
        MarketStatisticsProvider statistics = () -> selected.get().marketStatistics().getMarketStatistics();
        MarketChartProvider chart = () -> selected.get().marketChart().getMarketChart();
        OpportunityHistoryProvider history = new OpportunityHistoryProvider() {
            @Override
            public List<OpportunityHistoryEntry> getHistory() {
                return selected.get().history().getHistory();
            }

            @Override
            public CompletableFuture<Integer> clearHistory() {
                return selected.get().history().clearHistory();
            }
        };
        ItemSearchProvider items = query -> selected.get().itemSearch().search(query);
        ApiConnectionStatusProvider api = () -> selected.get().apiConnectionStatus().getApiConnectionStatus();
        ItemFilterController filters = new ItemFilterController() {
            @Override
            public ItemFilterSnapshot getItemFilters() {
                return selected.get().itemFilters().getItemFilters();
            }

            @Override
            public CompletableFuture<Void> setMode(ItemFilterMode mode) {
                return selected.get().itemFilters().setMode(mode);
            }

            @Override
            public CompletableFuture<Void> setWhitelisted(String itemId, boolean whitelisted) {
                return selected.get().itemFilters().setWhitelisted(itemId, whitelisted);
            }

            @Override
            public CompletableFuture<Void> setBlacklisted(String itemId, boolean blacklisted) {
                return selected.get().itemFilters().setBlacklisted(itemId, blacklisted);
            }
        };
        NotificationSettingsController notifications = new NotificationSettingsController() {
            @Override
            public NotificationSettings getNotificationSettings() {
                return selected.get().notificationSettings().getNotificationSettings();
            }

            @Override
            public void setNotificationsEnabled(boolean enabled) {
                selected.get().notificationSettings().setNotificationsEnabled(enabled);
            }
        };
        AutomationSettingsController automation = new AutomationSettingsController() {
            @Override
            public AutomationSettingsSnapshot snapshot() {
                return selected.get().automationSettings().snapshot();
            }

            @Override
            public CompletableFuture<OpportunityActionResult> runDryRun(FlipOpportunity opportunity) {
                return selected.get().automationSettings().runDryRun(opportunity);
            }

            @Override
            public CompletableFuture<OpportunityActionResult> executeConfiguredMode(FlipOpportunity opportunity) {
                return selected.get().automationSettings().executeConfiguredMode(opportunity);
            }

            @Override
            public CompletableFuture<OpportunityActionResult> confirmPending() {
                return selected.get().automationSettings().confirmPending();
            }

            @Override
            public CompletableFuture<Void> setEnabled(boolean enabled) {
                return selected.get().automationSettings().setEnabled(enabled);
            }

            @Override
            public CompletableFuture<Void> setMode(AutomationMode mode) {
                return selected.get().automationSettings().setMode(mode);
            }

            @Override
            public CompletableFuture<Void> allowCurrentServer() {
                return selected.get().automationSettings().allowCurrentServer();
            }

            @Override
            public boolean armCurrentServer(String exactConfirmation) {
                return selected.get().automationSettings().armCurrentServer(exactConfirmation);
            }

            @Override
            public void disarm() {
                selected.get().automationSettings().disarm();
            }

            @Override
            public void emergencyStop() {
                selected.get().automationSettings().emergencyStop();
            }

            @Override
            public void clearEmergencyStop() {
                selected.get().automationSettings().clearEmergencyStop();
            }
        };
        routed = new ClientUiDataSources(
                opportunities, scanner, statistics, history, items, api, filters, chart, notifications, automation
        );
    }

    public ClientUiDataSources dataSources() {
        return routed;
    }

    public void select(ClientUiDataSources dataSources) {
        selected.set(Objects.requireNonNull(dataSources, "dataSources"));
    }

    public ClientUiDataSources selected() {
        return selected.get();
    }
}
