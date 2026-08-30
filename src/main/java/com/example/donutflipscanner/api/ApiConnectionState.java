package com.example.donutflipscanner.api;

public enum ApiConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RATE_LIMITED,
    AUTHENTICATION_FAILED,
    TEMPORARY_ERROR,
    DISABLED
}
