package com.example.donutflipscanner.data;

import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.data.mock.MockApiConnectionStatusProvider;
import com.example.donutflipscanner.data.mock.MockItemSearchProvider;
import com.example.donutflipscanner.data.mock.MockMarketStatisticsProvider;
import com.example.donutflipscanner.data.mock.MockMarketChartProvider;
import com.example.donutflipscanner.data.mock.MockItemFilterController;
import com.example.donutflipscanner.data.mock.MockOpportunityHistoryProvider;
import com.example.donutflipscanner.data.mock.MockOpportunityProvider;
import com.example.donutflipscanner.data.mock.MockNotificationSettingsController;
import com.example.donutflipscanner.data.mock.MockScannerStatusProvider;
import com.example.donutflipscanner.data.mock.MockAutomationSettingsController;
import com.example.donutflipscanner.data.provider.ApiConnectionStatusProvider;
import com.example.donutflipscanner.data.provider.ItemSearchProvider;
import com.example.donutflipscanner.data.provider.ItemFilterController;
import com.example.donutflipscanner.data.provider.MarketStatisticsProvider;
import com.example.donutflipscanner.data.provider.MarketChartProvider;
import com.example.donutflipscanner.data.provider.OpportunityHistoryProvider;
import com.example.donutflipscanner.data.provider.OpportunityProvider;
import com.example.donutflipscanner.data.provider.ScannerStatusController;
import com.example.donutflipscanner.data.provider.DatabaseOpportunityHistoryProvider;
import com.example.donutflipscanner.data.provider.LiveApiConnectionStatusProvider;
import com.example.donutflipscanner.data.provider.LiveItemFilterController;
import com.example.donutflipscanner.data.provider.LiveMarketStatisticsProvider;
import com.example.donutflipscanner.data.provider.LiveScannerStatusProvider;
import com.example.donutflipscanner.data.provider.MarketOpportunityProvider;
import com.example.donutflipscanner.data.provider.LiveNotificationSettingsController;
import com.example.donutflipscanner.data.provider.NotificationSettingsController;
import com.example.donutflipscanner.data.provider.RegistryItemSearchProvider;
import com.example.donutflipscanner.data.provider.AutomationSettingsController;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import com.example.donutflipscanner.market.scanner.ScannerDataVersions;
import com.example.donutflipscanner.market.scanner.ScannerActivity;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Provider bundle passed into GUI screens. Replacing mock providers with live
 * providers later does not require rendering code to change.
 */
public record ClientUiDataSources(
        OpportunityProvider opportunities,
        ScannerStatusController scannerStatus,
        MarketStatisticsProvider marketStatistics,
        OpportunityHistoryProvider history,
        ItemSearchProvider itemSearch,
        ApiConnectionStatusProvider apiConnectionStatus,
        ItemFilterController itemFilters,
        MarketChartProvider marketChart,
        NotificationSettingsController notificationSettings,
        AutomationSettingsController automationSettings
) {
    public ClientUiDataSources {
        Objects.requireNonNull(opportunities, "opportunities");
        Objects.requireNonNull(scannerStatus, "scannerStatus");
        Objects.requireNonNull(marketStatistics, "marketStatistics");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(itemSearch, "itemSearch");
        Objects.requireNonNull(apiConnectionStatus, "apiConnectionStatus");
        Objects.requireNonNull(itemFilters, "itemFilters");
        Objects.requireNonNull(marketChart, "marketChart");
        Objects.requireNonNull(notificationSettings, "notificationSettings");
        Objects.requireNonNull(automationSettings, "automationSettings");
    }

    public ClientUiDataSources(
            OpportunityProvider opportunities,
            ScannerStatusController scannerStatus,
            MarketStatisticsProvider marketStatistics,
            OpportunityHistoryProvider history,
            ItemSearchProvider itemSearch,
            ApiConnectionStatusProvider apiConnectionStatus,
            ItemFilterController itemFilters
    ) {
        this(opportunities, scannerStatus, marketStatistics, history, itemSearch,
                apiConnectionStatus, itemFilters, MarketChartSnapshot::unavailable,
                new MockNotificationSettingsController(), new MockAutomationSettingsController());
    }

    public ClientUiDataSources(
            OpportunityProvider opportunities,
            ScannerStatusController scannerStatus,
            MarketStatisticsProvider marketStatistics,
            OpportunityHistoryProvider history,
            ItemSearchProvider itemSearch,
            ApiConnectionStatusProvider apiConnectionStatus
    ) {
        this(opportunities, scannerStatus, marketStatistics, history, itemSearch,
                apiConnectionStatus, new MockItemFilterController(), MarketChartSnapshot::unavailable,
                new MockNotificationSettingsController(), new MockAutomationSettingsController());
    }

    public static ClientUiDataSources createMock() {
        OpportunityProvider opportunities = new MockOpportunityProvider();
        return new ClientUiDataSources(
                opportunities,
                new MockScannerStatusProvider(),
                new MockMarketStatisticsProvider(opportunities),
                new MockOpportunityHistoryProvider(),
                new MockItemSearchProvider(),
                new MockApiConnectionStatusProvider(),
                new MockItemFilterController(),
                new MockMarketChartProvider(),
                new MockNotificationSettingsController(),
                new MockAutomationSettingsController()
        );
    }

    public static ClientUiDataSources createLive(
            LiveMarketSnapshotService snapshots,
            MarketScanner scanner,
            DonutApiClient apiClient,
            LiveItemFilterController itemFilters
    ) {
        return createLive(snapshots, scanner, apiClient, itemFilters, ignored -> { },
                new LiveNotificationSettingsController(true, true, ignored -> { }),
                new MockAutomationSettingsController());
    }

    public static ClientUiDataSources createLive(
            LiveMarketSnapshotService snapshots,
            MarketScanner scanner,
            DonutApiClient apiClient,
            LiveItemFilterController itemFilters,
            Consumer<Boolean> scannerEnabledChangeListener
    ) {
        return createLive(snapshots, scanner, apiClient, itemFilters, scannerEnabledChangeListener,
                new LiveNotificationSettingsController(true, true, ignored -> { }),
                new MockAutomationSettingsController());
    }

    public static ClientUiDataSources createLive(
            LiveMarketSnapshotService snapshots,
            MarketScanner scanner,
            DonutApiClient apiClient,
            LiveItemFilterController itemFilters,
            Consumer<Boolean> scannerEnabledChangeListener,
            NotificationSettingsController notificationSettings,
            AutomationSettingsController automationSettings
    ) {
        return createLive(snapshots, scanner, apiClient, itemFilters,
                scannerEnabledChangeListener, notificationSettings, automationSettings,
                MarketChartSnapshot::unavailable);
    }

    public static ClientUiDataSources createLive(
            LiveMarketSnapshotService snapshots,
            MarketScanner scanner,
            DonutApiClient apiClient,
            LiveItemFilterController itemFilters,
            Consumer<Boolean> scannerEnabledChangeListener,
            NotificationSettingsController notificationSettings,
            AutomationSettingsController automationSettings,
            MarketChartProvider marketChart
    ) {
        snapshots.refresh();
        AtomicReference<ScannerDataVersions> observedVersions = new AtomicReference<>(
                scanner.snapshot().dataVersions()
        );
        AtomicLong observedRecentListingRuns = new AtomicLong(
                scanner.snapshot().completedRuns().getOrDefault(ScannerActivity.RECENT_LISTINGS, 0L)
        );
        scanner.addSnapshotListener(scannerSnapshot -> {
            ScannerDataVersions previous = observedVersions.getAndSet(scannerSnapshot.dataVersions());
            long recentRuns = scannerSnapshot.completedRuns()
                    .getOrDefault(ScannerActivity.RECENT_LISTINGS, 0L);
            boolean recentListingPollCompleted = observedRecentListingRuns.getAndSet(recentRuns) != recentRuns;
            if (!scannerSnapshot.dataVersions().equals(previous) || recentListingPollCompleted) {
                snapshots.refresh();
            }
        });
        return new ClientUiDataSources(
                new MarketOpportunityProvider(snapshots),
                new LiveScannerStatusProvider(scanner, scannerEnabledChangeListener),
                new LiveMarketStatisticsProvider(snapshots),
                new DatabaseOpportunityHistoryProvider(snapshots),
                new RegistryItemSearchProvider(),
                new LiveApiConnectionStatusProvider(apiClient),
                itemFilters,
                marketChart,
                notificationSettings,
                automationSettings
        );
    }
}
