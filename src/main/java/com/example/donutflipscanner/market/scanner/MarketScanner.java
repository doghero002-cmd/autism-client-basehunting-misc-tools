package com.example.donutflipscanner.market.scanner;

import com.example.donutflipscanner.api.ApiAuthenticationException;
import com.example.donutflipscanner.api.ApiException;
import com.example.donutflipscanner.api.ApiRateLimitException;
import com.example.donutflipscanner.database.DatabaseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns scanner lifecycle and a fixed set of recurring batch jobs. No network or database
 * operation executes inline on the caller or Minecraft client thread.
 */
public final class MarketScanner implements AutoCloseable {
    private static final int MAXIMUM_TRACKED_ACTIVE_LISTINGS = 100_000;
    private static final int MAXIMUM_PENDING_STATISTIC_FINGERPRINTS = 50_000;
    private static final List<ScannerActivity> SCHEDULED_ACTIVITIES = List.of(
            ScannerActivity.RECENT_LISTINGS,
            ScannerActivity.COMPLETED_TRANSACTIONS,
            ScannerActivity.ACTIVE_LISTING_REFRESH,
            ScannerActivity.RETENTION_CLEANUP
    );

    private final Object lifecycleLock = new Object();
    private final MarketScanWork work;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Executor callbackExecutor;
    private final CopyOnWriteArrayList<Consumer<MarketScannerSnapshot>> snapshotListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicReference<MarketScannerConfig> config;
    private final AtomicReference<MarketScannerState> state = new AtomicReference<>(MarketScannerState.STOPPED);
    private final EnumMap<ScannerActivity, ScheduledFuture<?>> scheduled = new EnumMap<>(ScannerActivity.class);
    private final EnumMap<ScannerActivity, AtomicBoolean> activityRunning = new EnumMap<>(ScannerActivity.class);
    private final EnumMap<ScannerActivity, AtomicBoolean> immediateQueued = new EnumMap<>(ScannerActivity.class);
    private final ConcurrentHashMap<ScannerActivity, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScannerActivity, AtomicLong> completedRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScannerActivity, AtomicLong> skippedRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScannerActivity, Instant> responseTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScannerActivity, String> pageHashes = new ConcurrentHashMap<>();
    private final Set<String> pendingStatisticFingerprints = ConcurrentHashMap.newKeySet();
    private final Set<String> knownActiveListingKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong listingVersion = new AtomicLong();
    private final AtomicLong completedSalesVersion = new AtomicLong();
    private final AtomicLong statisticsVersion = new AtomicLong();
    private final AtomicInteger consecutiveTemporaryFailures = new AtomicInteger();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();

    private volatile ScannerPauseReason pauseReason = ScannerPauseReason.NONE;
    private volatile Instant stateChangedAt = Instant.now();
    private volatile Instant startedAt;
    private volatile Instant lastSuccessfulScan;
    private volatile Instant lastFailedScan;
    private volatile String lastSanitizedError;
    private volatile String lastProcessedListing;
    private volatile String lastProcessedTransaction;
    private volatile ScheduledFuture<?> rateLimitResume;

    public MarketScanner(MarketScanWork work, MarketScannerConfig config) {
        this(work, config, Runnable::run, ignored -> { });
    }

    public MarketScanner(
            MarketScanWork work,
            MarketScannerConfig config,
            Executor callbackExecutor,
            Consumer<MarketScannerSnapshot> snapshotListener
    ) {
        this.work = Objects.requireNonNull(work, "work");
        this.config = new AtomicReference<>(Objects.requireNonNull(config, "config"));
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        snapshotListeners.add(Objects.requireNonNull(snapshotListener, "snapshotListener"));
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "donut-market-scanner");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        for (ScannerActivity activity : ScannerActivity.values()) {
            activityRunning.put(activity, new AtomicBoolean());
            immediateQueued.put(activity, new AtomicBoolean());
            completedRuns.put(activity, new AtomicLong());
            skippedRuns.put(activity, new AtomicLong());
        }
    }

    public boolean start() {
        synchronized (lifecycleLock) {
            if (shutdown.get() || state.get() == MarketScannerState.RUNNING
                    || state.get() == MarketScannerState.STARTING) {
                return false;
            }
            MarketScannerConfig current = config.get();
            ScannerPauseReason ineligible = ineligibleReason(current);
            if (ineligible != ScannerPauseReason.NONE) {
                applyIneligibleState(ineligible);
                return false;
            }
            transition(MarketScannerState.STARTING, ScannerPauseReason.NONE, null);
            startedAt = Instant.now();
            scheduleActivities(current);
            transition(MarketScannerState.RUNNING, ScannerPauseReason.NONE, null);
            return true;
        }
    }

    public boolean pause() {
        synchronized (lifecycleLock) {
            if (state.get() != MarketScannerState.RUNNING
                    && state.get() != MarketScannerState.RATE_LIMITED) {
                return false;
            }
            cancelRateLimitResume();
            transition(MarketScannerState.PAUSED, ScannerPauseReason.USER_REQUEST, null);
            return true;
        }
    }

    public boolean resume() {
        synchronized (lifecycleLock) {
            if (state.get() != MarketScannerState.PAUSED || shutdown.get()) {
                return false;
            }
            ScannerPauseReason ineligible = ineligibleReason(config.get());
            if (ineligible != ScannerPauseReason.NONE) {
                applyIneligibleState(ineligible);
                return false;
            }
            if (scheduled.isEmpty()) {
                scheduleActivities(config.get());
            }
            transition(MarketScannerState.RUNNING, ScannerPauseReason.NONE, null);
            requestImmediateScan(ScannerActivity.RECENT_LISTINGS);
            return true;
        }
    }

    public CompletableFuture<Void> stopAsync() {
        return stopAsync(ScannerPauseReason.SCANNER_DISABLED);
    }

    private CompletableFuture<Void> stopAsync(ScannerPauseReason stoppedReason) {
        synchronized (lifecycleLock) {
            if (state.get() == MarketScannerState.STOPPED) {
                return CompletableFuture.completedFuture(null);
            }
            transition(MarketScannerState.STOPPING, pauseReason, null);
            cancelSchedules();
        }
        return awaitInFlight(config.get().shutdownGracePeriod()).handle((ignored, error) -> {
            synchronized (lifecycleLock) {
                transition(MarketScannerState.STOPPED, stoppedReason, null);
            }
            return null;
        });
    }

    /** Performs a non-blocking shutdown suitable for a Minecraft client-stopping callback. */
    public CompletableFuture<Void> shutdownAsync() {
        if (!shutdown.compareAndSet(false, true)) {
            return awaitInFlight(config.get().shutdownGracePeriod());
        }
        synchronized (lifecycleLock) {
            transition(MarketScannerState.STOPPING, ScannerPauseReason.CLIENT_SHUTDOWN, null);
            cancelSchedules();
        }
        Duration grace = config.get().shutdownGracePeriod();
        return awaitInFlight(grace)
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> CompletableFuture.allOf(
                        safeFuture(work.flushPendingWrites()), safeFuture(work.saveConfiguration())
                ).orTimeout(grace.toMillis(), TimeUnit.MILLISECONDS).exceptionally(error -> null))
                .whenComplete((ignored, error) -> {
                    closeResources();
                    synchronized (lifecycleLock) {
                        transition(MarketScannerState.STOPPED, ScannerPauseReason.CLIENT_SHUTDOWN, null);
                    }
                });
    }

    public void updateConfiguration(MarketScannerConfig updated) {
        Objects.requireNonNull(updated, "updated");
        MarketScannerConfig previous = config.getAndSet(updated);
        boolean shouldStart = false;
        synchronized (lifecycleLock) {
            ScannerPauseReason ineligible = ineligibleReason(updated);
            if (ineligible == ScannerPauseReason.SCANNER_DISABLED
                    || ineligible == ScannerPauseReason.CLIENT_NOT_RUNNING) {
                stopAsync(ineligible);
                return;
            }
            if (ineligible != ScannerPauseReason.NONE) {
                cancelSchedules();
                applyIneligibleState(ineligible);
                return;
            }
            if (state.get() == MarketScannerState.RUNNING && intervalsChanged(previous, updated)) {
                cancelSchedules();
                scheduleActivities(updated);
            } else if (state.get() == MarketScannerState.PAUSED
                    && pauseReason != ScannerPauseReason.USER_REQUEST) {
                scheduleActivities(updated);
                transition(MarketScannerState.RUNNING, ScannerPauseReason.NONE, null);
            } else if (state.get() == MarketScannerState.STOPPED
                    || (state.get() == MarketScannerState.ERROR
                    && pauseReason == ScannerPauseReason.API_KEY_MISSING)) {
                shouldStart = true;
            }
        }
        if (shouldStart) {
            start();
        }
        publishSnapshot();
    }

    public boolean requestImmediateScan(ScannerActivity activity) {
        Objects.requireNonNull(activity, "activity");
        if (activity == ScannerActivity.STATISTICS_RECALCULATION
                || state.get() != MarketScannerState.RUNNING || shutdown.get()) {
            return false;
        }
        AtomicBoolean queued = immediateQueued.get(activity);
        if (!queued.compareAndSet(false, true)) {
            return false;
        }
        try {
            scheduler.execute(() -> {
                queued.set(false);
                runActivity(activity);
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            queued.set(false);
            return false;
        }
    }

    public MarketScannerSnapshot snapshot() {
        EnumMap<ScannerActivity, Long> completed = new EnumMap<>(ScannerActivity.class);
        EnumMap<ScannerActivity, Long> skipped = new EnumMap<>(ScannerActivity.class);
        for (ScannerActivity activity : ScannerActivity.values()) {
            completed.put(activity, completedRuns.get(activity).get());
            skipped.put(activity, skippedRuns.get(activity).get());
        }
        int scheduledCount;
        synchronized (lifecycleLock) {
            scheduledCount = (int) scheduled.values().stream().filter(task -> !task.isCancelled()).count();
        }
        return new MarketScannerSnapshot(
                state.get(), pauseReason, Optional.ofNullable(startedAt), stateChangedAt,
                Optional.ofNullable(lastSuccessfulScan), Optional.ofNullable(lastFailedScan),
                Optional.ofNullable(lastSanitizedError), completed, skipped,
                Map.copyOf(responseTimestamps), Map.copyOf(pageHashes),
                Optional.ofNullable(lastProcessedListing), Optional.ofNullable(lastProcessedTransaction),
                Set.copyOf(knownActiveListingKeys),
                new ScannerDataVersions(
                        listingVersion.get(), completedSalesVersion.get(), statisticsVersion.get()
                ),
                scheduledCount, inFlight.size(), consecutiveTemporaryFailures.get()
        );
    }

    public MarketScannerConfig configuration() {
        return config.get();
    }

    public void addSnapshotListener(Consumer<MarketScannerSnapshot> listener) {
        snapshotListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        shutdownAsync();
    }

    private void scheduleActivities(MarketScannerConfig current) {
        if (!scheduled.isEmpty()) {
            return;
        }
        for (ScannerActivity activity : SCHEDULED_ACTIVITIES) {
            Duration interval = current.interval(activity);
            long initialDelay = activity == ScannerActivity.RETENTION_CLEANUP
                    ? interval.toNanos() : 0L;
            ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                    () -> runActivity(activity), initialDelay, interval.toNanos(), TimeUnit.NANOSECONDS
            );
            scheduled.put(activity, task);
        }
    }

    private void runActivity(ScannerActivity activity) {
        if (state.get() != MarketScannerState.RUNNING || shutdown.get()) {
            return;
        }
        AtomicBoolean gate = activityRunning.get(activity);
        if (!gate.compareAndSet(false, true)) {
            skippedRuns.get(activity).incrementAndGet();
            publishSnapshot();
            return;
        }
        CompletableFuture<ScanBatchResult> future;
        try {
            future = Objects.requireNonNull(dispatch(activity), "scan work future");
        } catch (Throwable error) {
            gate.set(false);
            handleFailure(error);
            return;
        }
        inFlight.put(activity, future);
        future.whenComplete((result, error) -> {
            inFlight.remove(activity, future);
            gate.set(false);
            if (error != null) {
                handleFailure(error);
            } else {
                handleSuccess(activity, Objects.requireNonNull(result, "scan result"));
            }
            if (activity == ScannerActivity.STATISTICS_RECALCULATION
                    && !pendingStatisticFingerprints.isEmpty()) {
                runStatisticsRecalculation();
            }
        });
    }

    private CompletableFuture<ScanBatchResult> dispatch(ScannerActivity activity) {
        return switch (activity) {
            case RECENT_LISTINGS -> work.pollRecentlyListed();
            case COMPLETED_TRANSACTIONS -> work.pollCompletedTransactions();
            case ACTIVE_LISTING_REFRESH -> work.refreshActiveListings();
            case RETENTION_CLEANUP -> work.runRetentionCleanup();
            case STATISTICS_RECALCULATION -> throw new IllegalArgumentException(
                    "statistics require a fingerprint batch"
            );
        };
    }

    private void runStatisticsRecalculation() {
        if (state.get() != MarketScannerState.RUNNING || pendingStatisticFingerprints.isEmpty()) {
            return;
        }
        AtomicBoolean gate = activityRunning.get(ScannerActivity.STATISTICS_RECALCULATION);
        if (!gate.compareAndSet(false, true)) {
            return;
        }
        Set<String> batch = Set.copyOf(pendingStatisticFingerprints);
        pendingStatisticFingerprints.removeAll(batch);
        CompletableFuture<ScanBatchResult> future;
        try {
            future = Objects.requireNonNull(work.recalculateStatistics(batch), "statistics future");
        } catch (Throwable error) {
            pendingStatisticFingerprints.addAll(batch);
            gate.set(false);
            handleFailure(error);
            return;
        }
        inFlight.put(ScannerActivity.STATISTICS_RECALCULATION, future);
        future.whenComplete((result, error) -> {
            inFlight.remove(ScannerActivity.STATISTICS_RECALCULATION, future);
            gate.set(false);
            if (error != null) {
                pendingStatisticFingerprints.addAll(batch);
                handleFailure(error);
            } else {
                handleSuccess(ScannerActivity.STATISTICS_RECALCULATION, result);
            }
            if (!pendingStatisticFingerprints.isEmpty()) {
                runStatisticsRecalculation();
            }
        });
    }

    private void handleSuccess(ScannerActivity activity, ScanBatchResult result) {
        completedRuns.get(activity).incrementAndGet();
        responseTimestamps.put(activity, result.responseReceivedAt());
        result.pageHash().ifPresent(hash -> pageHashes.put(activity, hash));
        lastSuccessfulScan = Instant.now();
        lastSanitizedError = null;
        consecutiveTemporaryFailures.set(0);
        if (activity == ScannerActivity.RECENT_LISTINGS
                || activity == ScannerActivity.ACTIVE_LISTING_REFRESH) {
            result.lastProcessedKey().ifPresent(value -> lastProcessedListing = value);
            addBounded(knownActiveListingKeys, result.knownActiveListingKeys(), MAXIMUM_TRACKED_ACTIVE_LISTINGS);
            if (result.changedRecords() > 0) {
                listingVersion.incrementAndGet();
            }
        } else if (activity == ScannerActivity.COMPLETED_TRANSACTIONS) {
            result.lastProcessedKey().ifPresent(value -> lastProcessedTransaction = value);
            if (result.changedRecords() > 0) {
                completedSalesVersion.incrementAndGet();
            }
        } else if (activity == ScannerActivity.STATISTICS_RECALCULATION
                && result.changedRecords() > 0) {
            statisticsVersion.incrementAndGet();
        }
        if ((activity == ScannerActivity.RECENT_LISTINGS
                || activity == ScannerActivity.ACTIVE_LISTING_REFRESH
                || activity == ScannerActivity.COMPLETED_TRANSACTIONS)
                && !result.changedFingerprints().isEmpty()) {
            addBounded(
                    pendingStatisticFingerprints,
                    result.changedFingerprints(),
                    MAXIMUM_PENDING_STATISTIC_FINGERPRINTS
            );
            runStatisticsRecalculation();
        }
        publishSnapshot();
    }

    private void handleFailure(Throwable error) {
        Throwable cause = unwrap(error);
        lastFailedScan = Instant.now();
        if (cause instanceof ApiAuthenticationException) {
            failScanner(ScannerPauseReason.API_KEY_MISSING, "API authentication failed.");
            return;
        }
        if (cause instanceof ApiRateLimitException rateLimited) {
            enterRateLimit(rateLimited.retryAfter());
            return;
        }
        if (cause instanceof DatabaseException) {
            failScanner(ScannerPauseReason.DATABASE_FAILURE, "Market database operation failed.");
            return;
        }
        if (cause instanceof ApiException apiFailure && apiFailure.retryable()) {
            int failures = consecutiveTemporaryFailures.incrementAndGet();
            lastSanitizedError = "Market API is temporarily unavailable.";
            if (failures >= config.get().maximumConsecutiveTemporaryFailures()) {
                failScanner(ScannerPauseReason.TEMPORARY_API_FAILURE,
                        "Market API remained unavailable after controlled retries.");
            } else {
                publishSnapshot();
            }
            return;
        }
        failScanner(ScannerPauseReason.UNRECOVERABLE_ERROR, "Scanner activity failed.");
    }

    private void enterRateLimit(Duration retryAfter) {
        synchronized (lifecycleLock) {
            Duration safeDelay = retryAfter.isNegative() || retryAfter.isZero()
                    ? Duration.ofMillis(1) : retryAfter;
            transition(MarketScannerState.RATE_LIMITED, ScannerPauseReason.API_RATE_LIMIT,
                    "API rate limit reached; scanning will resume after cooldown.");
            cancelRateLimitResume();
            rateLimitResume = scheduler.schedule(() -> {
                synchronized (lifecycleLock) {
                    if (state.get() == MarketScannerState.RATE_LIMITED && !shutdown.get()) {
                        transition(MarketScannerState.RUNNING, ScannerPauseReason.NONE, null);
                        requestImmediateScan(ScannerActivity.RECENT_LISTINGS);
                    }
                }
            }, safeDelay.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private void failScanner(ScannerPauseReason reason, String sanitizedError) {
        synchronized (lifecycleLock) {
            cancelSchedules();
            transition(MarketScannerState.ERROR, reason, sanitizedError);
        }
    }

    private void applyIneligibleState(ScannerPauseReason reason) {
        MarketScannerState target = switch (reason) {
            case MOCK_DATA_MODE, SERVER_RESTRICTED -> MarketScannerState.PAUSED;
            case API_KEY_MISSING -> MarketScannerState.ERROR;
            default -> MarketScannerState.STOPPED;
        };
        String error = reason == ScannerPauseReason.API_KEY_MISSING
                ? "A valid API key is required before scanning can start." : null;
        transition(target, reason, error);
    }

    private static ScannerPauseReason ineligibleReason(MarketScannerConfig config) {
        if (!config.scannerEnabled()) {
            return ScannerPauseReason.SCANNER_DISABLED;
        }
        if (!config.clientRunning()) {
            return ScannerPauseReason.CLIENT_NOT_RUNNING;
        }
        if (!config.apiKeyConfigured()) {
            return ScannerPauseReason.API_KEY_MISSING;
        }
        if (config.mockDataMode()) {
            return ScannerPauseReason.MOCK_DATA_MODE;
        }
        if (!config.serverAllowed()) {
            return ScannerPauseReason.SERVER_RESTRICTED;
        }
        return ScannerPauseReason.NONE;
    }

    private static boolean intervalsChanged(MarketScannerConfig left, MarketScannerConfig right) {
        return !left.recentListingsInterval().equals(right.recentListingsInterval())
                || !left.completedTransactionsInterval().equals(right.completedTransactionsInterval())
                || !left.activeListingsRefreshInterval().equals(right.activeListingsRefreshInterval())
                || !left.retentionInterval().equals(right.retentionInterval());
    }

    private CompletableFuture<Void> awaitInFlight(Duration timeout) {
        CompletableFuture<?>[] futures = inFlight.values().toArray(CompletableFuture[]::new);
        if (futures.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(error -> null);
    }

    private void cancelSchedules() {
        scheduled.values().forEach(task -> task.cancel(false));
        scheduled.clear();
        cancelRateLimitResume();
    }

    private void cancelRateLimitResume() {
        ScheduledFuture<?> task = rateLimitResume;
        if (task != null) {
            task.cancel(false);
            rateLimitResume = null;
        }
    }

    private void transition(MarketScannerState next, ScannerPauseReason reason, String sanitizedError) {
        state.set(next);
        pauseReason = Objects.requireNonNull(reason, "reason");
        stateChangedAt = Instant.now();
        if (sanitizedError != null) {
            lastSanitizedError = sanitizedError;
            lastFailedScan = stateChangedAt;
        }
        publishSnapshot();
    }

    private void publishSnapshot() {
        try {
            callbackExecutor.execute(() -> {
                MarketScannerSnapshot current = snapshot();
                for (Consumer<MarketScannerSnapshot> listener : snapshotListeners) {
                    try {
                        listener.accept(current);
                    } catch (RuntimeException ignored) {
                        // One GUI/provider listener must not prevent the others from receiving updates.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // A client callback must never terminate scanner scheduling.
        }
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdown();
        try {
            work.close();
        } catch (RuntimeException ignored) {
            // Shutdown remains best-effort and bounded.
        }
    }

    private static CompletableFuture<Void> safeFuture(CompletableFuture<Void> future) {
        return Objects.requireNonNull(future, "shutdown future").exceptionally(error -> null);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void addBounded(Set<String> target, Set<String> additions, int maximumSize) {
        for (String addition : additions) {
            if (target.size() >= maximumSize && !target.contains(addition)) {
                break;
            }
            target.add(addition);
        }
    }
}
