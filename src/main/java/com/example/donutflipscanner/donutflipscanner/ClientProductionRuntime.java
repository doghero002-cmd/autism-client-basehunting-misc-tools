package com.example.donutflipscanner;

import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.api.FileApiCredentialsStore;
import com.example.donutflipscanner.balance.ApiPlayerBalanceProvider;
import com.example.donutflipscanner.automation.service.DatabaseTradeExecutionObserver;
import com.example.donutflipscanner.automation.service.AutomaticOpportunityDispatcher;
import com.example.donutflipscanner.automation.service.DryRunAuctionInteractionAdapter;
import com.example.donutflipscanner.automation.service.RelistPricingService;
import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.automation.MinecraftAuctionInteractionAdapter;
import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.configuration.AppConfig;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.configuration.ConfigurationManager;
import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.LiveClientDataIntegration;
import com.example.donutflipscanner.data.provider.ClientAutomationSettingsController;
import com.example.donutflipscanner.data.provider.LiveItemFilterController;
import com.example.donutflipscanner.data.provider.LiveNotificationSettingsController;
import com.example.donutflipscanner.data.provider.ApiConnectionStatusProvider;
import com.example.donutflipscanner.data.ApiConnectionStatus;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.PersonalProfitRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.TradeExecutionRepository;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.example.donutflipscanner.market.opportunity.OpportunityDetector;
import com.example.donutflipscanner.market.opportunity.OpportunityEvaluationConfig;
import com.example.donutflipscanner.market.opportunity.SupportedItemPolicy;
import com.example.donutflipscanner.market.scanner.ApiMarketScanWork;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import com.example.donutflipscanner.market.scanner.MarketScannerConfig;
import com.example.donutflipscanner.market.statistics.RepositoryMarketStatisticsService;
import com.example.donutflipscanner.packet.AuctionHouseDataCapture;
import com.example.donutflipscanner.packet.PacketMarketScanWork;
import com.example.donutflipscanner.mixin.AuctionHousePacketListener;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.profit.PersonalProfitTracker;
import com.example.donutflipscanner.profit.PlayerIdentity;
import com.example.donutflipscanner.service.MarketRetentionPolicy;
import com.example.donutflipscanner.service.RepositoryMarketAnalysisService;
import com.example.donutflipscanner.service.RepositoryMarketDataIngestionService;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import java.time.Instant;

/** Owns the opt-in live API, analysis, database, and scanner lifecycle. */
public final class ClientProductionRuntime implements AutoCloseable {
    private static final int FIRST_ACTIVE_LISTING_PAGE = 1;
    private static final int LAST_ACTIVE_LISTING_PAGE = 10;

    private final ConfigurationManager configurationManager;
    private final AtomicReference<AppConfig> configuration;
    private final ExecutorService persistenceExecutor;
    private final DonutApiClient apiClient;
    private final DatabaseManager database;
    private final TradeExecutionRepository tradeExecutions;
    private final ClientAutomationSettingsController automationSettings;
    private final MinecraftAuctionInteractionAdapter authorizedAuctionAdapter;
    private final AutomaticOpportunityDispatcher automaticDispatcher;
    private final Runnable snapshotSubscription;
    private final MarketScanner scanner;
    private final LiveMarketSnapshotService snapshots;
    private final RepositoryMarketAnalysisService analysis;
    private final PersonalProfitTracker personalProfitTracker;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ClientProductionRuntime(
            ConfigurationManager configurationManager,
            AtomicReference<AppConfig> configuration,
            ExecutorService persistenceExecutor,
            DonutApiClient apiClient,
            DatabaseManager database,
            TradeExecutionRepository tradeExecutions,
            ClientAutomationSettingsController automationSettings,
            MinecraftAuctionInteractionAdapter authorizedAuctionAdapter,
            AutomaticOpportunityDispatcher automaticDispatcher,
            Runnable snapshotSubscription,
            MarketScanner scanner,
            LiveMarketSnapshotService snapshots,
            RepositoryMarketAnalysisService analysis,
            PersonalProfitTracker personalProfitTracker
    ) {
        this.configurationManager = configurationManager;
        this.configuration = configuration;
        this.persistenceExecutor = persistenceExecutor;
        this.apiClient = apiClient;
        this.database = database;
        this.tradeExecutions = tradeExecutions;
        this.automationSettings = automationSettings;
        this.authorizedAuctionAdapter = authorizedAuctionAdapter;
        this.automaticDispatcher = automaticDispatcher;
        this.snapshotSubscription = snapshotSubscription;
        this.scanner = scanner;
        this.snapshots = snapshots;
        this.analysis = analysis;
        this.personalProfitTracker = personalProfitTracker;
    }

    /**
     * Creates and attaches live providers only when a separate credential exists.
     * A missing key is a normal mock-mode result, not an initialization failure.
     */
    public static CompletableFuture<Optional<ClientProductionRuntime>> startAsync(Path configurationDirectory) {
        return startAsync(configurationDirectory, "");
    }

    public static CompletableFuture<Optional<ClientProductionRuntime>> startAsync(
            Path configurationDirectory, String minecraftUsername
    ) {
        return startAsync(configurationDirectory, minecraftUsername, "");
    }

    public static CompletableFuture<Optional<ClientProductionRuntime>> startAsync(
            Path configurationDirectory, String minecraftUsername, String minecraftUuid
    ) {
        Path root = Objects.requireNonNull(configurationDirectory, "configurationDirectory")
                .toAbsolutePath().normalize();
        String username = Objects.requireNonNullElse(minecraftUsername, "").strip();
        String uuid = Objects.requireNonNullElse(minecraftUuid, "").strip();
        ExecutorService bootstrapExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "donut-live-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        return CompletableFuture.supplyAsync(() -> create(root, username, uuid), bootstrapExecutor)
                .thenCompose(optional -> optional
                        .map(runtime -> runtime.readyAndAttach().thenApply(ignored -> Optional.of(runtime)))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .whenComplete((ignored, error) -> bootstrapExecutor.shutdown());
    }

    /**
     * Starts the scanner in packet-interception mode (no API key required).
     * Auction house data is captured from Minecraft packets when the player opens /ah.
     */
    public static CompletableFuture<Optional<ClientProductionRuntime>> startPacketModeAsync(
            Path configurationDirectory, String minecraftUsername, String minecraftUuid
    ) {
        Path root = Objects.requireNonNull(configurationDirectory, "configurationDirectory")
                .toAbsolutePath().normalize();
        String username = Objects.requireNonNullElse(minecraftUsername, "").strip();
        String uuid = Objects.requireNonNullElse(minecraftUuid, "").strip();
        ExecutorService bootstrapExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "donut-packet-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        return CompletableFuture.supplyAsync(() -> createPacketMode(root, username, uuid), bootstrapExecutor)
                .thenCompose(optional -> optional
                        .map(runtime -> runtime.readyAndAttachPacketMode()
                                .thenApply(ignored -> Optional.of(runtime)))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .whenComplete((ignored, error) -> bootstrapExecutor.shutdown());
    }

    public MarketScanner scanner() {
        return scanner;
    }

    public LiveMarketSnapshotService snapshots() {
        return snapshots;
    }

    private static Optional<ClientProductionRuntime> create(
            Path root, String minecraftUsername, String minecraftUuid
    ) {
        FileApiCredentialsStore credentials = new FileApiCredentialsStore(root.resolve("api-key.txt"));
        char[] probe = credentials.copyApiKey().orElse(null);
        if (probe == null) {
            return Optional.empty();
        }
        Arrays.fill(probe, '\0');

        ConfigurationManager configurationManager = new ConfigurationManager(root.resolve("config.json"));
        AppConfig loaded = configurationManager.load().configuration();
        AppConfig liveConfiguration = withLiveMode(loaded);
        if (!liveConfiguration.equals(loaded)) {
            configurationManager.save(liveConfiguration);
        }
        AtomicReference<AppConfig> configuration = new AtomicReference<>(liveConfiguration);
        ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "donut-config-persistence");
            thread.setDaemon(true);
            return thread;
        });

        DatabaseManager database = null;
        DonutApiClient apiClient = null;
        try {
            database = new DatabaseManager(root.resolve("market-data.db"));
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            ListingRepository listings = new ListingRepository(database);
            SaleRepository sales = new SaleRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            Clock clock = Clock.systemUTC();
            PersonalProfitTracker personalProfitTracker = new PersonalProfitTracker(
                    new PersonalProfitRepository(database),
                    new PlayerIdentity(minecraftUsername, Optional.ofNullable(minecraftUuid)
                            .filter(value -> !value.isBlank())),
                    clock
            );
            OpportunityDetector detector = new OpportunityDetector();
            RepositoryMarketStatisticsService statistics =
                    new RepositoryMarketStatisticsService(sales, listings);
            ItemFilterPolicy filters = filters(liveConfiguration);
            RepositoryMarketAnalysisService analysis = new RepositoryMarketAnalysisService(
                    fingerprints, listings, opportunities, sales, statistics, detector,
                    withFilters(OpportunityEvaluationConfig.relaxedLiveDefaults(), filters)
            );
            AtomicReference<AppConfig> configReference = configuration;
            ConfigurationManager managerReference = configurationManager;
            ExecutorService executorReference = persistenceExecutor;
            RepositoryMarketDataIngestionService ingestion = new RepositoryMarketDataIngestionService(
                    database,
                    analysis,
                    () -> CompletableFuture.runAsync(
                            () -> managerReference.save(configReference.get()), executorReference
                    ),
                    MarketRetentionPolicy.defaults(),
                    new com.example.donutflipscanner.diagnostics.PerformanceMetrics(),
                    personalProfitTracker
            );
            apiClient = new DonutApiClient(credentials);
            ApiPlayerBalanceProvider balanceProvider = new ApiPlayerBalanceProvider(
                    apiClient, () -> minecraftUsername
            );
            ApiMarketScanWork work = new ApiMarketScanWork(
                    apiClient, ingestion, FIRST_ACTIVE_LISTING_PAGE, LAST_ACTIVE_LISTING_PAGE
            );
            MarketScannerConfig scannerConfig = MarketScannerConfig.defaults(true)
                    .withScannerEnabled(liveConfiguration.scannerEnabled());
            MarketScanner scanner = new MarketScanner(work, scannerConfig);
            LiveMarketSnapshotService snapshots = new LiveMarketSnapshotService(
                    listings, sales, opportunities, Optional.of(detector), personalProfitTracker
            );
            TradeExecutionRepository tradeExecutions = new TradeExecutionRepository(database);
            DatabaseTradeExecutionObserver executionObserver =
                    new DatabaseTradeExecutionObserver(tradeExecutions);
            MinecraftAuctionInteractionAdapter authorizedAuctionAdapter =
                    new MinecraftAuctionInteractionAdapter(
                            Minecraft.getInstance(),
                            () -> configuration.get().automation().interactionProfile()
                    );
            TradeAutomationCoordinator automation = new TradeAutomationCoordinator(
                    () -> configuration.get().automation(),
                    new DryRunAuctionInteractionAdapter(),
                    authorizedAuctionAdapter,
                    new RelistPricingService(), executionObserver, clock
            );
            ClientAutomationSettingsController automationSettings =
                    new ClientAutomationSettingsController(
                            () -> configuration.get().automation(),
                            updated -> updateAutomationConfiguration(
                                    configuration, configurationManager, persistenceExecutor, updated
                            ),
                            DonutFlipScannerClient::currentServerAddress,
                            automation,
                             balanceProvider
                     );
            AutomaticOpportunityDispatcher automaticDispatcher = new AutomaticOpportunityDispatcher(
                    () -> configuration.get().automation(), automation, snapshots::snapshot
            );
            Runnable snapshotSubscription = snapshots.subscribe(automaticDispatcher::onSnapshotPublished);
            return Optional.of(new ClientProductionRuntime(
                    configurationManager, configuration, persistenceExecutor, apiClient,
                    database, tradeExecutions, automationSettings, authorizedAuctionAdapter,
                    automaticDispatcher, snapshotSubscription,
                    scanner, snapshots, analysis, personalProfitTracker
            ));
        } catch (RuntimeException failure) {
            if (apiClient != null) {
                apiClient.close();
            }
            if (database != null) {
                database.close();
            }
            persistenceExecutor.shutdownNow();
            throw failure;
        }
    }

    /**
     * Creates a runtime using packet interception instead of the external API.
     * No API key is required — auction house data comes from intercepted Minecraft packets.
     */
    private static Optional<ClientProductionRuntime> createPacketMode(
            Path root, String minecraftUsername, String minecraftUuid
    ) {
        ConfigurationManager configurationManager = new ConfigurationManager(root.resolve("config.json"));
        AppConfig loaded = configurationManager.load().configuration();
        AppConfig liveConfiguration = withLiveMode(loaded);
        if (!liveConfiguration.equals(loaded)) {
            configurationManager.save(liveConfiguration);
        }
        AtomicReference<AppConfig> configuration = new AtomicReference<>(liveConfiguration);
        ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "donut-config-persistence");
            thread.setDaemon(true);
            return thread;
        });

        DatabaseManager database = null;
        try {
            database = new DatabaseManager(root.resolve("market-data.db"));
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            ListingRepository listings = new ListingRepository(database);
            SaleRepository sales = new SaleRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            Clock clock = Clock.systemUTC();
            PersonalProfitTracker personalProfitTracker = new PersonalProfitTracker(
                    new PersonalProfitRepository(database),
                    new PlayerIdentity(minecraftUsername, Optional.ofNullable(minecraftUuid)
                            .filter(value -> !value.isBlank())),
                    clock
            );
            OpportunityDetector detector = new OpportunityDetector();
            RepositoryMarketStatisticsService statistics =
                    new RepositoryMarketStatisticsService(sales, listings);
            ItemFilterPolicy filters = filters(liveConfiguration);
            RepositoryMarketAnalysisService analysis = new RepositoryMarketAnalysisService(
                    fingerprints, listings, opportunities, sales, statistics, detector,
                    withFilters(OpportunityEvaluationConfig.relaxedLiveDefaults(), filters)
            );
            AtomicReference<AppConfig> configReference = configuration;
            ConfigurationManager managerReference = configurationManager;
            ExecutorService executorReference = persistenceExecutor;
            RepositoryMarketDataIngestionService ingestion = new RepositoryMarketDataIngestionService(
                    database,
                    analysis,
                    () -> CompletableFuture.runAsync(
                            () -> managerReference.save(configReference.get()), executorReference
                    ),
                    MarketRetentionPolicy.defaults(),
                    new com.example.donutflipscanner.diagnostics.PerformanceMetrics(),
                    personalProfitTracker
            );

            AuctionInteractionProfile profile = liveConfiguration.automation().interactionProfile()
                    .orElse(null);
            AuctionHouseDataCapture capture = new AuctionHouseDataCapture(
                    () -> Optional.ofNullable(profile)
            );
            AuctionHousePacketListener.setCapture(capture);

            PacketMarketScanWork work = new PacketMarketScanWork(capture, ingestion);
            MarketScannerConfig scannerConfig = MarketScannerConfig.defaults(true)
                    .withScannerEnabled(liveConfiguration.scannerEnabled());
            MarketScanner scanner = new MarketScanner(work, scannerConfig);
            LiveMarketSnapshotService snapshots = new LiveMarketSnapshotService(
                    listings, sales, opportunities, Optional.of(detector), personalProfitTracker
            );
            TradeExecutionRepository tradeExecutions = new TradeExecutionRepository(database);
            DatabaseTradeExecutionObserver executionObserver =
                    new DatabaseTradeExecutionObserver(tradeExecutions);
            MinecraftAuctionInteractionAdapter authorizedAuctionAdapter =
                    new MinecraftAuctionInteractionAdapter(
                            Minecraft.getInstance(),
                            () -> configuration.get().automation().interactionProfile()
                    );
            TradeAutomationCoordinator automation = new TradeAutomationCoordinator(
                    () -> configuration.get().automation(),
                    new DryRunAuctionInteractionAdapter(),
                    authorizedAuctionAdapter,
                    new RelistPricingService(), executionObserver, clock
            );
            ClientAutomationSettingsController automationSettings =
                    new ClientAutomationSettingsController(
                            () -> configuration.get().automation(),
                            updated -> updateAutomationConfiguration(
                                    configuration, configurationManager, persistenceExecutor, updated
                            ),
                            DonutFlipScannerClient::currentServerAddress,
                            automation
                    );
            AutomaticOpportunityDispatcher automaticDispatcher = new AutomaticOpportunityDispatcher(
                    () -> configuration.get().automation(), automation, snapshots::snapshot
            );
            Runnable snapshotSubscription = snapshots.subscribe(automaticDispatcher::onSnapshotPublished);
            return Optional.of(new ClientProductionRuntime(
                    configurationManager, configuration, persistenceExecutor, null,
                    database, tradeExecutions, automationSettings, authorizedAuctionAdapter,
                    automaticDispatcher, snapshotSubscription,
                    scanner, snapshots, analysis, personalProfitTracker
            ));
        } catch (RuntimeException failure) {
            if (database != null) {
                database.close();
            }
            persistenceExecutor.shutdownNow();
            throw failure;
        }
    }

    private CompletableFuture<Void> readyAndAttach() {
        // The first snapshot queues behind database initialization. Registry-backed UI providers
        // are then constructed on Minecraft's client executor.
        return tradeExecutions.markInterruptedExecutionsForReview(Instant.now())
                .thenCompose(recovered -> personalProfitTracker.initialize())
                .thenCompose(recovered -> snapshots.refresh()).thenCompose(ignored -> {
            CompletableFuture<Void> attached = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if (!closed.get()) {
                        LiveClientDataIntegration.attach(
                                snapshots,
                                apiClient,
                                scanner,
                                filters(configuration.get()),
                                this::applyFiltersAsync,
                                this::setScannerEnabled,
                                configuration.get().notificationsEnabled(),
                                configuration.get().animationsEnabled(),
                                this::setNotificationsEnabled,
                                automationSettings,
                                personalProfitTracker
                        );
                        scanner.start();
                    }
                    attached.complete(null);
                } catch (RuntimeException failure) {
                    attached.completeExceptionally(failure);
                }
            });
            return attached;
        }).exceptionallyCompose(error -> {
            close();
            return CompletableFuture.failedFuture(error);
        });
    }

    /**
     * Attaches packet-mode data sources on the Minecraft client thread.
     * Similar to readyAndAttach but uses a packet-aware connection status provider.
     */
    private CompletableFuture<Void> readyAndAttachPacketMode() {
        return tradeExecutions.markInterruptedExecutionsForReview(Instant.now())
                .thenCompose(recovered -> personalProfitTracker.initialize())
                .thenCompose(recovered -> snapshots.refresh()).thenCompose(ignored -> {
            CompletableFuture<Void> attached = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if (!closed.get()) {
                        LiveItemFilterController itemFilters = new LiveItemFilterController(
                                filters(configuration.get()), this::applyFiltersAsync
                        );
                        snapshots.refresh();
                        ClientUiDataSources live = new ClientUiDataSources(
                                new com.example.donutflipscanner.data.provider.MarketOpportunityProvider(snapshots),
                                new com.example.donutflipscanner.data.provider.LiveScannerStatusProvider(
                                        scanner, this::setScannerEnabled
                                ),
                                new com.example.donutflipscanner.data.provider.LiveMarketStatisticsProvider(snapshots),
                                new com.example.donutflipscanner.data.provider.DatabaseOpportunityHistoryProvider(snapshots),
                                new com.example.donutflipscanner.data.provider.RegistryItemSearchProvider(),
                                new PacketConnectionStatusProvider(),
                                itemFilters,
                                com.example.donutflipscanner.data.MarketChartSnapshot::unavailable,
                                new LiveNotificationSettingsController(
                                        configuration.get().notificationsEnabled(),
                                        configuration.get().animationsEnabled(),
                                        this::setNotificationsEnabled
                                ),
                                automationSettings
                        );
                        DonutFlipScannerClient.useLiveDataSources(live, scanner);
                        scanner.start();
                    }
                    attached.complete(null);
                } catch (RuntimeException failure) {
                    attached.completeExceptionally(failure);
                }
            });
            return attached;
        }).exceptionallyCompose(error -> {
            close();
            return CompletableFuture.failedFuture(error);
        });
    }

    /**
     * Connection status provider for packet mode — always reports connected since
     * we're reading directly from Minecraft's network layer.
     */
    private static final class PacketConnectionStatusProvider implements ApiConnectionStatusProvider {
        @Override
        public ApiConnectionStatus getApiConnectionStatus() {
            return new ApiConnectionStatus("DonutSMP", "Packet interception active", true);
        }
    }

    private CompletableFuture<Void> applyFiltersAsync(ItemFilterPolicy policy) {
        AppConfig updated = withFilters(configuration.get(), policy);
        configuration.set(updated);
        CompletableFuture<Void> save = CompletableFuture.runAsync(
                () -> configurationManager.save(updated), persistenceExecutor
        );
        return CompletableFuture.allOf(save, analysis.updateFilterPolicy(policy));
    }

    private void setScannerEnabled(boolean enabled) {
        configuration.updateAndGet(current -> new AppConfig(
                current.formatVersion(), enabled, false, current.animationsEnabled(),
                current.notificationsEnabled(),
                current.interfaceScale(), current.filterMode(), current.whitelistedItems(),
                current.blacklistedItems(), current.itemThresholds(), current.automation()
        ));
        CompletableFuture.runAsync(
                () -> configurationManager.save(configuration.get()), persistenceExecutor
        );
    }

    private void setNotificationsEnabled(boolean enabled) {
        configuration.updateAndGet(current -> new AppConfig(
                current.formatVersion(), current.scannerEnabled(), false, current.animationsEnabled(),
                enabled, current.interfaceScale(), current.filterMode(), current.whitelistedItems(),
                current.blacklistedItems(), current.itemThresholds(), current.automation()
        ));
        CompletableFuture.runAsync(
                () -> configurationManager.save(configuration.get()), persistenceExecutor
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        snapshotSubscription.run();
        automaticDispatcher.close();
        scanner.shutdownAsync().whenComplete((ignored, error) -> {
            database.close();
            authorizedAuctionAdapter.close();
            persistenceExecutor.shutdown();
        });
    }

    private static ItemFilterPolicy filters(AppConfig configuration) {
        return new ItemFilterPolicy(
                configuration.filterMode(), configuration.whitelistedItems(), configuration.blacklistedItems()
        );
    }

    private static OpportunityEvaluationConfig withFilters(
            OpportunityEvaluationConfig current,
            ItemFilterPolicy filters
    ) {
        return new OpportunityEvaluationConfig(
                current.evaluationVersion(), current.configurationVersion(), "persisted-filters-v1", filters,
                SupportedItemPolicy.liveDefaults(), current.fairValueConfig(), current.profitConfig(),
                current.confidenceConfig(), current.riskConfig(), current.thresholds(),
                current.alertCooldown(), current.staleReevaluationInterval()
        );
    }

    private static AppConfig withLiveMode(AppConfig current) {
        return new AppConfig(
                current.formatVersion(), current.scannerEnabled(), false, current.animationsEnabled(),
                current.notificationsEnabled(),
                current.interfaceScale(), current.filterMode(), current.whitelistedItems(),
                current.blacklistedItems(), current.itemThresholds(), current.automation()
        );
    }

    private static AppConfig withFilters(AppConfig current, ItemFilterPolicy filters) {
        return new AppConfig(
                current.formatVersion(), current.scannerEnabled(), false, current.animationsEnabled(),
                current.notificationsEnabled(),
                current.interfaceScale(), filters.mode(), filters.whitelistedItemIds(),
                filters.blacklistedItemIds(), current.itemThresholds(), current.automation()
        );
    }

    private static CompletableFuture<Void> updateAutomationConfiguration(
            AtomicReference<AppConfig> configuration,
            ConfigurationManager manager,
            ExecutorService executor,
            AutomationConfig automation
    ) {
        AppConfig updated = configuration.updateAndGet(current -> new AppConfig(
                current.formatVersion(), current.scannerEnabled(), false, current.animationsEnabled(),
                current.notificationsEnabled(), current.interfaceScale(), current.filterMode(),
                current.whitelistedItems(), current.blacklistedItems(), current.itemThresholds(), automation
        ));
        return CompletableFuture.runAsync(() -> manager.save(updated), executor);
    }
}
