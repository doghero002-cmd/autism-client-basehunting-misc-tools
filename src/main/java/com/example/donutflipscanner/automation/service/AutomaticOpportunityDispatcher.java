package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.market.item.ItemFingerprintFactory;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.provider.LiveMarketSnapshot;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.provider.MarketOpportunitySnapshot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Converts immutable snapshot publications into bounded, single-flight automatic executions.
 * Every queued entry is re-read from the latest snapshot immediately before submission.
 */
public final class AutomaticOpportunityDispatcher implements AutoCloseable {
    public static final int MAXIMUM_QUEUED_OPPORTUNITIES = 8;
    private static final int MAXIMUM_REMEMBERED_IDENTITIES = 2_048;

    private final Supplier<AutomationConfig> configuration;
    private final TradeAutomationCoordinator coordinator;
    private final Supplier<LiveMarketSnapshot> latestSnapshot;
    private final Clock clock;
    private final Deque<QueuedOpportunity> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private boolean drainScheduled;
    private boolean closed;

    public AutomaticOpportunityDispatcher(
            Supplier<AutomationConfig> configuration,
            TradeAutomationCoordinator coordinator,
            Supplier<LiveMarketSnapshot> latestSnapshot
    ) {
        this(configuration, coordinator, latestSnapshot, Clock.systemUTC());
    }

    AutomaticOpportunityDispatcher(
            Supplier<AutomationConfig> configuration,
            TradeAutomationCoordinator coordinator,
            Supplier<LiveMarketSnapshot> latestSnapshot,
            Clock clock
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.latestSnapshot = Objects.requireNonNull(latestSnapshot, "latestSnapshot");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Called by the snapshot publication path, never by a render callback. */
    public void onSnapshotPublished(LiveMarketSnapshot published) {
        Objects.requireNonNull(published, "published");
        if (!published.databaseAvailable() || !acceptingAutomaticWork()) {
            return;
        }
        java.util.ArrayList<TradeExecutionRequest> overflow = new java.util.ArrayList<>();
        int queued;
        synchronized (this) {
            if (closed) {
                return;
            }
            for (MarketOpportunitySnapshot opportunity : published.activeOpportunities()) {
                if (!OpportunityState.NEW.name().equals(opportunity.state())) {
                    continue;
                }
                String identity = identity(opportunity);
                if (seen.contains(identity)) {
                    continue;
                }
                remember(identity);
                TradeExecutionRequest request;
                try {
                    request = request(opportunity, UUID.randomUUID().toString(), configuration.get());
                } catch (RuntimeException failure) {
                    continue;
                }
                if (queue.size() >= MAXIMUM_QUEUED_OPPORTUNITIES) {
                    overflow.add(request);
                    continue;
                }
                queue.addLast(new QueuedOpportunity(opportunity, request));
            }
            queued = queue.size();
        }
        overflow.forEach(request -> coordinator.recordPreflightRejection(
                request, "Automatic opportunity queue is full; execution was not attempted."
        ));
        coordinator.reportAutomaticQueue(queued);
        scheduleDrain();
    }

    public synchronized int queuedCount() {
        return queue.size();
    }

    private void scheduleDrain() {
        synchronized (this) {
            if (closed || drainScheduled || queue.isEmpty()) {
                return;
            }
            drainScheduled = true;
        }
        coordinator.whenIdle().whenComplete((ignored, error) -> drainOne());
    }

    private void drainOne() {
        QueuedOpportunity queued;
        synchronized (this) {
            drainScheduled = false;
            if (closed || queue.isEmpty()) {
                return;
            }
            queued = queue.removeFirst();
        }
        coordinator.reportAutomaticQueue(queuedCount());

        Validation validation = revalidate(queued);
        CompletableFuture<TradeExecutionResult> execution;
        if (validation.request().isEmpty()) {
            execution = CompletableFuture.completedFuture(coordinator.recordPreflightRejection(
                    queued.request(), validation.message()
            ));
        } else {
            execution = coordinator.submit(validation.request().orElseThrow());
        }
        execution.whenComplete((result, error) -> {
            if (result != null && result.message().contains("Another execution is already active.")) {
                synchronized (this) {
                    queue.addFirst(queued);
                }
            }
            scheduleDrain();
        });
    }

    private Validation revalidate(QueuedOpportunity queued) {
        if (!acceptingAutomaticWork()) {
            return Validation.rejected(
                    "Automatic mode was disabled or disarmed while this opportunity was queued."
            );
        }
        LiveMarketSnapshot current = latestSnapshot.get();
        if (current == null || !current.databaseAvailable()) {
            return Validation.rejected("The live market snapshot is unavailable.");
        }
        Optional<MarketOpportunitySnapshot> found = current.activeOpportunities().stream()
                .filter(value -> value.opportunityId().equals(queued.opportunity().opportunityId()))
                .filter(value -> value.listingKey().equals(queued.opportunity().listingKey()))
                .findFirst();
        if (found.isEmpty()) {
            return Validation.rejected("The queued opportunity is no longer active.");
        }
        MarketOpportunitySnapshot latest = found.orElseThrow();
        if (!OpportunityState.NEW.name().equals(latest.state())) {
            return Validation.rejected("The queued opportunity is no longer in the NEW state.");
        }
        if (!sameImmutableListing(queued.opportunity(), latest)) {
            return Validation.rejected("The queued opportunity changed before execution.");
        }
        Duration verificationAge = Duration.between(latest.lastVerifiedAt(), clock.instant());
        if (verificationAge.isNegative()
                || verificationAge.compareTo(LiveMarketSnapshotService.MAXIMUM_ACTIONABLE_VERIFICATION_AGE) > 0) {
            return Validation.rejected("The queued listing is no longer freshly verified.");
        }
        if (latest.sellerName().isEmpty()) {
            return Validation.rejected("The listing seller is unavailable, so exact automatic matching is impossible.");
        }
        if (!validFingerprintMetadata(latest)) {
            return Validation.rejected("Canonical item metadata is unavailable or does not match its fingerprint.");
        }
        try {
            return Validation.accepted(request(
                    latest, queued.request().executionId(), configuration.get()
            ));
        } catch (RuntimeException failure) {
            return Validation.rejected("The refreshed opportunity is invalid: " + failure.getMessage());
        }
    }

    private boolean acceptingAutomaticWork() {
        AutomationConfig config = configuration.get();
        var execution = coordinator.snapshot();
        return config.enabled()
                && config.mode() == AutomationMode.AUTOMATIC_AUTHORIZED_SERVER
                && execution.sessionArmed()
                && !execution.emergencyStopped();
    }

    private static boolean sameImmutableListing(
            MarketOpportunitySnapshot expected, MarketOpportunitySnapshot actual
    ) {
        return expected.opportunityId().equals(actual.opportunityId())
                && expected.listingKey().equals(actual.listingKey())
                && expected.itemFingerprint().equals(actual.itemFingerprint())
                && expected.itemId().equals(actual.itemId())
                && expected.itemCount() == actual.itemCount()
                && expected.listingPrice().compareTo(actual.listingPrice()) == 0
                && expected.sellerName().equals(actual.sellerName())
                && expected.normalizedItemMetadata().equals(actual.normalizedItemMetadata());
    }

    private static boolean validFingerprintMetadata(MarketOpportunitySnapshot value) {
        try {
            return value.normalizedItemMetadata()
                    .map(metadata -> new ItemFingerprintFactory().fromCanonicalMetadata(metadata).sha256())
                    .filter(value.itemFingerprint()::equals)
                    .isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static TradeExecutionRequest request(
            MarketOpportunitySnapshot value, String executionId, AutomationConfig config
    ) {
        BigDecimal price = value.listingPrice().max(BigDecimal.ONE);
        BigDecimal fairValue = value.conservativeFairValue().max(price);
        return new TradeExecutionRequest(
                executionId, value.opportunityId(), value.listingKey(), value.itemFingerprint(),
                value.itemId(), value.itemCount(), value.sellerName(), price, fairValue,
                value.estimatedProfit(), value.roiPercent(),
                Math.max(0, Math.min(100, value.confidencePercent().intValue())),
                value.comparableSales(), value.riskLevel(),
                value.listedAt().orElse(value.detectedAt()), price,
                Math.max(1, config.maximumListingAgeSeconds()),
                AutomationMode.AUTOMATIC_AUTHORIZED_SERVER,
                Optional.of(itemName(value.itemId())), value.normalizedItemMetadata()
        );
    }

    private static String itemName(String itemId) {
        String path = itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
        StringBuilder result = new StringBuilder(path.length());
        boolean capitalize = true;
        for (char character : path.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    private static String identity(MarketOpportunitySnapshot value) {
        return value.opportunityId() + '\u0000' + value.listingKey();
    }

    private void remember(String identity) {
        seen.add(identity);
        if (seen.size() <= MAXIMUM_REMEMBERED_IDENTITIES) {
            return;
        }
        Iterator<String> values = seen.iterator();
        if (values.hasNext()) {
            values.next();
            values.remove();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
    }

    private record QueuedOpportunity(
            MarketOpportunitySnapshot opportunity, TradeExecutionRequest request
    ) {
    }

    private record Validation(Optional<TradeExecutionRequest> request, String message) {
        private static Validation accepted(TradeExecutionRequest request) {
            return new Validation(Optional.of(request), "Opportunity was revalidated.");
        }

        private static Validation rejected(String message) {
            return new Validation(Optional.empty(), Objects.requireNonNullElse(message, "Rejected."));
        }
    }
}
