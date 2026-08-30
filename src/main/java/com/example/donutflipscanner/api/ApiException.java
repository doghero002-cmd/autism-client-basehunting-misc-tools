package com.example.donutflipscanner.api;

/** Sanitized API failure. Messages never contain request headers or bodies. */
public class ApiException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;

    public ApiException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public ApiException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
