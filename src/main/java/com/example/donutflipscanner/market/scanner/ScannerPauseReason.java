package com.example.donutflipscanner.market.scanner;

public enum ScannerPauseReason {
    NONE,
    USER_REQUEST,
    SCANNER_DISABLED,
    CLIENT_NOT_RUNNING,
    API_KEY_MISSING,
    MOCK_DATA_MODE,
    SERVER_RESTRICTED,
    API_RATE_LIMIT,
    TEMPORARY_API_FAILURE,
    DATABASE_FAILURE,
    UNRECOVERABLE_ERROR,
    CLIENT_SHUTDOWN
}
