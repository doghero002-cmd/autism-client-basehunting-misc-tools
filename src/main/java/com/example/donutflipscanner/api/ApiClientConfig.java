package com.example.donutflipscanner.api;

import com.example.donutflipscanner.ModConstants;

import java.time.Duration;
import java.util.Objects;

public record ApiClientConfig(
        Duration connectTimeout,
        Duration requestTimeout,
        String userAgent,
        int rateLimitSafetyMargin,
        int maximumResponseCharacters,
        ApiRetryPolicy retryPolicy
) {
    public static final int DOCUMENTED_REQUESTS_PER_MINUTE = 250;

    public ApiClientConfig {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(userAgent, "userAgent");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        if (rateLimitSafetyMargin < 1 || rateLimitSafetyMargin >= DOCUMENTED_REQUESTS_PER_MINUTE) {
            throw new IllegalArgumentException("rateLimitSafetyMargin must be between 1 and 249");
        }
        if (maximumResponseCharacters < 1_024 || maximumResponseCharacters > 16 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "maximumResponseCharacters must be between 1024 and 16777216"
            );
        }
    }

    public int permittedRequestsPerMinute() {
        return DOCUMENTED_REQUESTS_PER_MINUTE - rateLimitSafetyMargin;
    }

    public static ApiClientConfig productionDefaults() {
        return new ApiClientConfig(
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                ModConstants.MOD_NAME.replace(' ', '-') + "/" + ModConstants.MOD_VERSION,
                25,
                4 * 1024 * 1024,
                ApiRetryPolicy.productionDefaults()
        );
    }
}
