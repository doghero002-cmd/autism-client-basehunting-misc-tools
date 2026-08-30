package com.example.donutflipscanner;

import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.ClientUiDataSourceRouter;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Client-side composition root for the DonutSMP flip scanner. Holds the shared
 * data-source router and the active market scanner so GUI and engine layers can
 * attach live or mock backends without referencing one another directly.
 *
 * <p>NOTE: the Fabric client entrypoint for this mod is
 * {@code com.autism.seedcracker.SeedcrackerInit}; this type is a plain holder /
 * bootstrap accessed statically by the engine.
 */
public final class DonutFlipScannerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);

    private static final ClientUiDataSourceRouter DATA_SOURCE_ROUTER =
            new ClientUiDataSourceRouter(ClientUiDataSources.createMock());
    private static volatile ClientUiDataSources dataSources = DATA_SOURCE_ROUTER.dataSources();
    private static volatile MarketScanner marketScanner;
    private static volatile String currentServerAddress = "";

    @Override
    public void onInitializeClient() {
        dataSources = DATA_SOURCE_ROUTER.dataSources();
        LOGGER.debug("DonutFlipScannerClient initialized (mock data sources active).");
    }

    /** Compatibility hook for integrations that compose an alternate live scanner. */
    public static void attachMarketScanner(MarketScanner scanner) {
        marketScanner = scanner;
    }

    /** Switch the routed UI data sources to a live backend and bind its scanner. */
    public static void useLiveDataSources(ClientUiDataSources liveSources, MarketScanner scanner) {
        Objects.requireNonNull(liveSources, "liveSources");
        Objects.requireNonNull(scanner, "scanner");
        DATA_SOURCE_ROUTER.select(liveSources);
        dataSources = DATA_SOURCE_ROUTER.dataSources();
        marketScanner = scanner;
        scanner.updateConfiguration(scanner.configuration().withMockDataMode(false));
    }

    /** Switch the routed UI data sources back to the offline mock backend. */
    public static void useMockDataSources() {
        DATA_SOURCE_ROUTER.select(ClientUiDataSources.createMock());
        dataSources = DATA_SOURCE_ROUTER.dataSources();
        MarketScanner scanner = marketScanner;
        if (scanner != null) {
            scanner.updateConfiguration(scanner.configuration().withMockDataMode(true));
        }
    }

    /** The currently routed UI data sources (live or mock). */
    public static ClientUiDataSources dataSources() {
        return dataSources;
    }

    /** The address of the server the client is currently connected to, or empty. */
    public static String currentServerAddress() {
        return currentServerAddress;
    }

    /** Update the tracked current-server address (called on client tick / connect). */
    public static void updateCurrentServerAddress(String address) {
        currentServerAddress = Objects.requireNonNullElse(address, "");
    }
}
