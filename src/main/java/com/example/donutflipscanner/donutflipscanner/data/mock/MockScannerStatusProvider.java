package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.ScannerStatus;
import com.example.donutflipscanner.data.provider.ScannerStatusController;

public final class MockScannerStatusProvider implements ScannerStatusController {
    private boolean enabled = true;

    @Override
    public ScannerStatus getScannerStatus() {
        return new ScannerStatus(enabled);
    }

    @Override
    public void setScannerEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
