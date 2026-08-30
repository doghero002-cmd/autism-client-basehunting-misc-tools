package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ScannerStatus;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import com.example.donutflipscanner.market.scanner.MarketScannerConfig;
import com.example.donutflipscanner.market.scanner.MarketScannerSnapshot;

import java.util.Objects;
import java.util.function.Consumer;

public final class LiveScannerStatusProvider implements ScannerStatusController {
    private final MarketScanner scanner;
    private final Consumer<Boolean> enabledChangeListener;

    public LiveScannerStatusProvider(MarketScanner scanner) {
        this(scanner, ignored -> { });
    }

    public LiveScannerStatusProvider(MarketScanner scanner, Consumer<Boolean> enabledChangeListener) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.enabledChangeListener = Objects.requireNonNull(enabledChangeListener, "enabledChangeListener");
    }

    @Override
    public ScannerStatus getScannerStatus() {
        MarketScannerSnapshot snapshot = scanner.snapshot();
        return new ScannerStatus(
                scanner.configuration().scannerEnabled(), snapshot.state().name(),
                snapshot.pauseReason().name(), snapshot.lastSanitizedError()
        );
    }

    @Override
    public void setScannerEnabled(boolean enabled) {
        MarketScannerConfig updated = scanner.configuration().withScannerEnabled(enabled);
        scanner.updateConfiguration(updated);
        enabledChangeListener.accept(enabled);
    }
}
