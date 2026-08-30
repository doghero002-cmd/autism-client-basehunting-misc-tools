package com.example.donutflipscanner.data.provider;

/** Mutable scanner state boundary used by GUI controls. */
public interface ScannerStatusController extends ScannerStatusProvider {
    void setScannerEnabled(boolean enabled);

    default void toggleScanner() {
        setScannerEnabled(!getScannerStatus().enabled());
    }
}
