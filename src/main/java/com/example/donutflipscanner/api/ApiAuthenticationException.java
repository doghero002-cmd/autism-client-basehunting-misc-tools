package com.example.donutflipscanner.api;

public final class ApiAuthenticationException extends ApiException {
    public ApiAuthenticationException(int statusCode) {
        super("DonutSMP API authentication was rejected", statusCode, false);
    }

    public ApiAuthenticationException(String message) {
        super(message, 401, false);
    }
}
