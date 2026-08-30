package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.ApiConnectionStatus;
import com.example.donutflipscanner.data.provider.ApiConnectionStatusProvider;

public final class MockApiConnectionStatusProvider implements ApiConnectionStatusProvider {
    private static final ApiConnectionStatus STATUS =
            new ApiConnectionStatus("Mock Data", "2s ago", true);

    @Override
    public ApiConnectionStatus getApiConnectionStatus() {
        return STATUS;
    }
}

