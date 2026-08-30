package com.example.donutflipscanner.api;

import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.api.model.ApiPlayerStats;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Asynchronous, authenticated client for the documented DonutSMP auction API.
 * It performs no work on Minecraft's render thread beyond immediately creating
 * and returning a {@link CompletableFuture}.
 */
public final class DonutApiClient implements AutoCloseable {
    private static final String RECENTLY_LISTED_BODY = "{\"sort\":\"recently_listed\"}";
    private static final List<Integer> RETRYABLE_STATUS_CODES = List.of(429, 500, 502, 503, 504);

    private final ApiCredentialsProvider credentialsProvider;
    private final ApiClientConfig config;
    private final ApiHttpTransport transport;
    private final ApiRequestScheduler scheduler;
    private final ApiRateLimiter rateLimiter;
    private final ApiResponseValidator validator;
    private final ApiConnectionStateTracker connectionTracker = new ApiConnectionStateTracker();

    public DonutApiClient(ApiCredentialsProvider credentialsProvider) {
        this(credentialsProvider, ApiClientConfig.productionDefaults());
    }

    public DonutApiClient(ApiCredentialsProvider credentialsProvider, ApiClientConfig config) {
        this(
                credentialsProvider,
                config,
                new JdkApiHttpTransport(
                        config.connectTimeout(), Math.multiplyExact(config.maximumResponseCharacters(), 4)
                ),
                new ApiRequestScheduler()
        );
    }

    public DonutApiClient(
            ApiCredentialsProvider credentialsProvider,
            ApiClientConfig config,
            ApiHttpTransport transport,
            ApiRequestScheduler scheduler
    ) {
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        rateLimiter = new ApiRateLimiter(config.permittedRequestsPerMinute(), scheduler);
        validator = new ApiResponseValidator(config.maximumResponseCharacters());
    }

    public CompletableFuture<ApiAuctionPage> fetchAuctionListings(int page) {
        URI endpoint = DonutApiEndpoints.auctionListings(page);
        return execute(endpoint, Optional.empty(), body -> validator.parseAuctionPage(body, page), 1);
    }

    /** Uses the official {@code recently_listed} sort value on the listings endpoint. */
    public CompletableFuture<ApiAuctionPage> fetchRecentlyListedAuctions(int page) {
        URI endpoint = DonutApiEndpoints.auctionListings(page);
        return execute(endpoint, Optional.of(RECENTLY_LISTED_BODY), body -> validator.parseAuctionPage(body, page), 1);
    }

    public CompletableFuture<ApiTransactionPage> fetchCompletedTransactions(int page) {
        URI endpoint = DonutApiEndpoints.auctionTransactions(page);
        return execute(endpoint, Optional.empty(), body -> validator.parseTransactionPage(body, page), 1);
    }

    /** Fetches the authenticated player's public stats record for passive balance display. */
    public CompletableFuture<ApiPlayerStats> fetchPlayerStats(String username) {
        URI endpoint = DonutApiEndpoints.playerStats(username);
        return execute(endpoint, Optional.empty(), validator::parsePlayerStats, 1);
    }

    public ApiConnectionSnapshot connectionSnapshot() {
        return connectionTracker.snapshot(rateLimiter.currentCooldown(), rateLimiter.requestsInCurrentWindow());
    }

    private <T> CompletableFuture<T> execute(
            URI endpoint,
            Optional<String> jsonBody,
            Function<String, T> parser,
            int attempt
    ) {
        return rateLimiter.acquire().thenCompose(ignored -> {
            HttpRequest request;
            try {
                request = buildRequest(endpoint, jsonBody);
            } catch (ApiAuthenticationException exception) {
                connectionTracker.disabled();
                return CompletableFuture.failedFuture(exception);
            }

            connectionTracker.connecting();
            return transport.sendAsync(request)
                    .handle((response, error) -> {
                        if (error != null) {
                            return handleTransportFailure(endpoint, jsonBody, parser, attempt, error);
                        }
                        return handleResponse(endpoint, jsonBody, parser, attempt, response);
                    })
                    .thenCompose(Function.identity());
        });
    }

    private HttpRequest buildRequest(URI endpoint, Optional<String> jsonBody) {
        char[] apiKey = credentialsProvider.copyApiKey().orElse(null);
        if (apiKey == null) {
            throw new ApiAuthenticationException("A DonutSMP API key has not been configured");
        }

        String authorizationValue;
        try {
            int first = 0;
            int last = apiKey.length;
            while (first < last && Character.isWhitespace(apiKey[first])) {
                first++;
            }
            while (last > first && Character.isWhitespace(apiKey[last - 1])) {
                last--;
            }
            if (first == last || last - first > 512) {
                throw new ApiAuthenticationException("The configured DonutSMP API key is invalid");
            }
            for (int index = first; index < last; index++) {
                char character = apiKey[index];
                if (Character.isISOControl(character) || character == '\r' || character == '\n') {
                    throw new ApiAuthenticationException("The configured DonutSMP API key is invalid");
                }
            }
            authorizationValue = "Bearer " + new String(apiKey, first, last - first);
        } finally {
            Arrays.fill(apiKey, '\0');
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", authorizationValue)
                .header("User-Agent", config.userAgent());
        if (jsonBody.isPresent()) {
            builder.header("Content-Type", "application/json")
                    .method("GET", HttpRequest.BodyPublishers.ofString(jsonBody.get()));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private <T> CompletableFuture<T> handleResponse(
            URI endpoint,
            Optional<String> jsonBody,
            Function<String, T> parser,
            int attempt,
            ApiHttpResponse response
    ) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            try {
                T result = parser.apply(response.body());
                connectionTracker.connected(response.latency());
                return CompletableFuture.completedFuture(result);
            } catch (ApiException exception) {
                connectionTracker.temporaryError();
                return CompletableFuture.failedFuture(exception);
            }
        }

        if (statusCode == 401 || statusCode == 403) {
            connectionTracker.authenticationFailed();
            return CompletableFuture.failedFuture(new ApiAuthenticationException(statusCode));
        }

        if (statusCode == 429) {
            Duration retryAfter = retryAfter(response).orElseGet(() -> config.retryPolicy().delayBeforeAttempt(attempt + 1));
            if (retryAfter.isZero()) {
                retryAfter = Duration.ofSeconds(2);
            }
            rateLimiter.enforceCooldown(retryAfter);
            connectionTracker.rateLimited();
            if (attempt < config.retryPolicy().maximumAttempts()) {
                Duration scheduledDelay = maximum(retryAfter, config.retryPolicy().delayBeforeAttempt(attempt + 1));
                return scheduleRetry(endpoint, jsonBody, parser, attempt, scheduledDelay);
            }
            return CompletableFuture.failedFuture(new ApiRateLimitException(retryAfter));
        }

        ApiException failure = new ApiException(
                "DonutSMP API request failed with HTTP " + statusCode,
                statusCode,
                RETRYABLE_STATUS_CODES.contains(statusCode)
        );
        connectionTracker.temporaryError();
        if (failure.retryable() && attempt < config.retryPolicy().maximumAttempts()) {
            return scheduleRetry(
                    endpoint,
                    jsonBody,
                    parser,
                    attempt,
                    config.retryPolicy().delayBeforeAttempt(attempt + 1)
            );
        }
        return CompletableFuture.failedFuture(failure);
    }

    private <T> CompletableFuture<T> handleTransportFailure(
            URI endpoint,
            Optional<String> jsonBody,
            Function<String, T> parser,
            int attempt,
            Throwable error
    ) {
        Throwable cause = unwrap(error);
        if (cause instanceof ApiResponseException responseFailure) {
            connectionTracker.temporaryError();
            return CompletableFuture.failedFuture(responseFailure);
        }
        boolean timeout = cause instanceof java.net.http.HttpTimeoutException || cause instanceof TimeoutException;
        ApiException failure = new ApiException(
                timeout ? "DonutSMP API request timed out" : "DonutSMP API connection failed",
                0,
                true,
                cause
        );
        connectionTracker.temporaryError();
        if (attempt < config.retryPolicy().maximumAttempts()) {
            return scheduleRetry(
                    endpoint,
                    jsonBody,
                    parser,
                    attempt,
                    config.retryPolicy().delayBeforeAttempt(attempt + 1)
            );
        }
        return CompletableFuture.failedFuture(failure);
    }

    private <T> CompletableFuture<T> scheduleRetry(
            URI endpoint,
            Optional<String> jsonBody,
            Function<String, T> parser,
            int currentAttempt,
            Duration delay
    ) {
        return scheduler.schedule(() -> execute(endpoint, jsonBody, parser, currentAttempt + 1), delay);
    }

    private Optional<Duration> retryAfter(ApiHttpResponse response) {
        return response.headerValues("Retry-After").stream()
                .findFirst()
                .flatMap(this::parseRetryAfter);
    }

    private Optional<Duration> parseRetryAfter(String value) {
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) {
                return Optional.of(Duration.ofSeconds(seconds));
            }
        } catch (NumberFormatException ignored) {
            // Retry-After may also be an RFC 1123 date.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration duration = Duration.between(Instant.now(), retryAt);
            return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private Duration maximum(Duration left, Duration right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        scheduler.close();
        transport.close();
    }
}
