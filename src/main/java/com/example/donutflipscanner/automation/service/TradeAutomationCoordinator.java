package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AuctionListingCandidate;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.model.TradeExecutionSnapshot;
import com.example.donutflipscanner.automation.model.TradeExecutionState;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Single-flight, fail-closed execution state machine. It never depends on GUI classes. */
public final class TradeAutomationCoordinator implements TradeExecutionProvider {
    public static final String ARM_CONFIRMATION = "I HAVE AUTHORIZATION";
    private static final long STEP_TIMEOUT_SECONDS = 60;

    private final Supplier<AutomationConfig> configuration;
    private final AuctionInteractionAdapter dryRunAdapter;
    private final AuctionInteractionAdapter authorizedAdapter;
    private final RelistPricingService pricingService;
    private final TradeExecutionObserver observer;
    private final Clock clock;
    private final Set<String> consumedListings = new HashSet<>();
    private final Set<String> consumedOpportunities = new HashSet<>();
    private ActiveExecution active;
    private CompletableFuture<Void> idleSignal = CompletableFuture.completedFuture(null);
    private String currentServer = "";
    private String armedServer = "";
    private boolean emergencyStopped;
    private int purchasesThisSession;
    private BigDecimal openExposure = BigDecimal.ZERO;
    private Instant lastPurchaseAt = Instant.EPOCH;
    private String statusMessage = "Automation is disarmed.";

    public TradeAutomationCoordinator(Supplier<AutomationConfig> configuration) {
        this(configuration, new DryRunAuctionInteractionAdapter(), new RejectingAuctionInteractionAdapter(),
                new RelistPricingService(), TradeExecutionObserver.noOp(), Clock.systemUTC());
    }

    public TradeAutomationCoordinator(
            Supplier<AutomationConfig> configuration,
            AuctionInteractionAdapter dryRunAdapter,
            AuctionInteractionAdapter authorizedAdapter,
            RelistPricingService pricingService,
            TradeExecutionObserver observer,
            Clock clock
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.dryRunAdapter = Objects.requireNonNull(dryRunAdapter, "dryRunAdapter");
        this.authorizedAdapter = Objects.requireNonNull(authorizedAdapter, "authorizedAdapter");
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean armSession(String serverAddress, String confirmation) {
        AutomationConfig config = configuration.get();
        String normalized = normalizeServer(serverAddress);
        if (!AutomationServerPolicy.permitsGameplayAutomation(normalized)) {
            statusMessage = "Gameplay automation is blocked on DonutSMP by its published server rules.";
            return false;
        }
        if (normalized.isEmpty() || !ARM_CONFIRMATION.equals(confirmation) || emergencyStopped || !config.enabled()
                || config.mode() == AutomationMode.DISABLED || config.mode() == AutomationMode.DRY_RUN
                || !config.serverAllowed(normalized)) {
            statusMessage = "Session arming rejected by authorization gates.";
            return false;
        }
        currentServer = normalized;
        armedServer = normalized;
        statusMessage = "Automation armed for this session on an explicitly allowlisted server.";
        return true;
    }

    public synchronized void onServerChanged(String serverAddress) {
        String normalized = serverAddress == null || serverAddress.isBlank() ? "" : normalizeServer(serverAddress);
        if (!normalized.equals(currentServer)) {
            disarm("Server changed or disconnected.");
            currentServer = normalized;
        }
    }

    public synchronized void disarm(String reason) {
        armedServer = "";
        statusMessage = Objects.requireNonNullElse(reason, "Automation disarmed.");
        if (active != null && active.request.requestedMode() != AutomationMode.DRY_RUN) {
            active.cancelled = true;
        }
    }

    public synchronized void clearEmergencyStop() {
        if (active == null) {
            emergencyStopped = false;
            statusMessage = "Emergency stop cleared; automation remains disarmed.";
        }
    }

    @Override
    public CompletableFuture<TradeExecutionResult> submit(TradeExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (this) {
            if (emergencyStopped) {
                return rejected(request, "Emergency stop is active.");
            }
            if (active != null) {
                return rejected(request, "Another execution is already active.");
            }
            if (consumedListings.contains(request.listingKey())
                    || consumedOpportunities.contains(request.opportunityId())) {
                return rejected(request, "This opportunity or listing has already been submitted during this session.");
            }
            consumedListings.add(request.listingKey());
            consumedOpportunities.add(request.opportunityId());
            AutomationConfig config = configuration.get();
            active = new ActiveExecution(request, config);
            idleSignal = new CompletableFuture<>();
            transition(active, TradeExecutionState.PRECHECK, "Running immutable prechecks.");
            Optional<String> rejection = precheck(request, config);
            if (rejection.isPresent()) {
                return CompletableFuture.completedFuture(fail(active, rejection.orElseThrow(), false, false));
            }
            if (request.requestedMode() == AutomationMode.CONFIRM_EACH) {
                transition(active, TradeExecutionState.WAITING_FOR_CONFIRMATION,
                        "Explicit confirmation is required before any Minecraft interaction.");
                TradeExecutionResult waiting = result(active, false, false, false,
                        "Waiting for explicit confirmation.", Optional.empty());
                return CompletableFuture.completedFuture(waiting);
            }
        }
        return executeActive();
    }

    public CompletableFuture<TradeExecutionResult> confirm(String executionId) {
        synchronized (this) {
            if (active == null || !active.request.executionId().equals(executionId)
                    || active.state != TradeExecutionState.WAITING_FOR_CONFIRMATION) {
                return CompletableFuture.completedFuture(new TradeExecutionResult(
                        executionId, TradeExecutionState.FAILED, false, false, false,
                        "No matching execution is waiting for confirmation.", Optional.empty()
                ));
            }
            AutomationConfig confirmedConfiguration = configuration.get();
            Optional<String> rejection = precheck(active.request, confirmedConfiguration);
            if (rejection.isPresent()) {
                return CompletableFuture.completedFuture(fail(active, rejection.orElseThrow(), false, false));
            }
            active.configuration = confirmedConfiguration;
        }
        return executeActive();
    }

    @Override
    public synchronized CompletableFuture<Boolean> cancel(String executionId) {
        if (active == null || !active.request.executionId().equals(executionId)) {
            return CompletableFuture.completedFuture(false);
        }
        active.cancelled = true;
        if (active.state == TradeExecutionState.WAITING_FOR_CONFIRMATION
                || active.state == TradeExecutionState.PRECHECK) {
                transition(active, TradeExecutionState.CANCELLED, "Cancelled before interaction.");
                active = null;
                signalIdle();
            }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized TradeExecutionSnapshot snapshot() {
        return new TradeExecutionSnapshot(
                !armedServer.isEmpty(), armedServer.isEmpty() ? Optional.empty() : Optional.of(armedServer),
                emergencyStopped, purchasesThisSession,
                openExposure,
                active == null ? Optional.empty() : Optional.of(active.request.executionId()),
                active == null ? Optional.empty() : Optional.of(active.state), statusMessage
        );
    }

    /** Completes when the state machine has no active execution. */
    public synchronized CompletableFuture<Void> whenIdle() {
        return active == null ? CompletableFuture.completedFuture(null) : idleSignal;
    }

    /** Records a provider-layer rejection without performing any Minecraft interaction. */
    public synchronized TradeExecutionResult recordPreflightRejection(
            TradeExecutionRequest request, String message
    ) {
        Objects.requireNonNull(request, "request");
        String safeMessage = Objects.requireNonNullElse(message, "Automatic opportunity was rejected.");
        Instant at = clock.instant();
        observer.onTransition(request, null, TradeExecutionState.PRECHECK, safeMessage, at);
        observer.onTransition(request, TradeExecutionState.PRECHECK, TradeExecutionState.FAILED, safeMessage, at);
        TradeExecutionResult result = new TradeExecutionResult(
                request.executionId(), TradeExecutionState.FAILED, false, false, false,
                safeMessage, Optional.empty()
        );
        observer.onOutcome(request, result, at);
        if (active == null) {
            statusMessage = safeMessage;
        }
        return result;
    }

    /** Makes bounded automatic-queue activity visible through the existing HUD snapshot. */
    public synchronized void reportAutomaticQueue(int queued) {
        if (active == null && !emergencyStopped) {
            statusMessage = queued == 1
                    ? "1 automatic opportunity is queued."
                    : queued + " automatic opportunities are queued.";
        }
    }

    @Override
    public synchronized void emergencyStop(String reason) {
        emergencyStopped = true;
        armedServer = "";
        statusMessage = Objects.requireNonNullElse(reason, "Emergency stop activated.");
        if (active != null) {
            active.cancelled = true;
        }
    }

    private CompletableFuture<TradeExecutionResult> executeActive() {
        ActiveExecution execution;
        AuctionInteractionAdapter adapter;
        synchronized (this) {
            execution = active;
            if (execution == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("no active execution"));
            }
            adapter = execution.request.requestedMode() == AutomationMode.DRY_RUN
                    ? dryRunAdapter : authorizedAdapter;
            transition(execution, TradeExecutionState.OPENING_AUCTION_HOUSE, "Beginning bounded auction workflow.");
        }

        return checked(execution, adapter.locateListing(execution.request))
                .thenCompose(located -> {
                    synchronized (this) {
                        transition(execution, TradeExecutionState.LOCATING_LISTING, located.message());
                    }
                    if (!located.located()) {
                        return CompletableFuture.failedFuture(new ExecutionFailure(located.message()));
                    }
                    AuctionListingCandidate candidate = located.candidate().orElseThrow();
                    return checked(execution, adapter.verifyListing(execution.request, candidate))
                            .thenCompose(verified -> {
                                synchronized (this) {
                                    transition(execution, TradeExecutionState.VERIFYING_LISTING, verified.message());
                                }
                                if (!verified.verified() || !matches(execution.request, candidate)) {
                                    return CompletableFuture.failedFuture(new ExecutionFailure(
                                            verified.verified() ? "Located listing differs from the request." : verified.message()
                                    ));
                                }
                                synchronized (this) {
                                    transition(execution, TradeExecutionState.PURCHASING,
                                            "Listing verified immediately before purchase.");
                                }
                                return checked(execution, adapter.purchase(execution.request, candidate));
                            });
                })
                .thenCompose(purchase -> {
                    if (!purchase.attempted() || !purchase.acknowledged()) {
                        return CompletableFuture.failedFuture(new ExecutionFailure(purchase.message()));
                    }
                    execution.purchaseConfirmed = true;
                    if (execution.request.requestedMode() != AutomationMode.DRY_RUN) {
                        synchronized (this) {
                            purchasesThisSession++;
                            openExposure = openExposure.add(execution.request.expectedListingPrice());
                            lastPurchaseAt = clock.instant();
                        }
                    }
                    synchronized (this) {
                        transition(execution, TradeExecutionState.VERIFYING_PURCHASE, purchase.message());
                    }
                    return checked(execution, adapter.verifyPurchase(execution.request));
                })
                .thenCompose(inventory -> {
                    if (!inventory.verified() || inventory.ambiguous()) {
                        return CompletableFuture.failedFuture(new ExecutionFailure(inventory.message()));
                    }
                    RelistPlan plan = pricingService.plan(execution.request, execution.configuration);
                    execution.relistPlan = Optional.of(plan);
                    synchronized (this) {
                        transition(execution, TradeExecutionState.CALCULATING_RELIST_PRICE, plan.explanation());
                        transition(execution, TradeExecutionState.LISTING_FOR_SALE,
                                "Submitting the bounded resale plan.");
                    }
                    return checked(execution, adapter.listForSale(execution.request, plan));
                })
                .thenCompose(listing -> {
                    if (!listing.submitted()) {
                        return CompletableFuture.failedFuture(new ExecutionFailure(listing.message()));
                    }
                    synchronized (this) {
                        transition(execution, TradeExecutionState.VERIFYING_LISTING_CREATED, listing.message());
                    }
                    return checked(execution, adapter.verifyListingCreated(
                            execution.request, execution.relistPlan.orElseThrow()
                    ));
                })
                .thenApply(verification -> {
                    if (!verification.verified()) {
                        throw new CompletionException(new ExecutionFailure(verification.message()));
                    }
                    execution.listingConfirmed = true;
                    synchronized (this) {
                        transition(execution, TradeExecutionState.COMPLETED, verification.message());
                        TradeExecutionResult completed = result(
                                execution, true, true, true, "Execution completed.", execution.relistPlan
                        );
                        if (execution.request.requestedMode() != AutomationMode.DRY_RUN) {
                            openExposure = openExposure.subtract(execution.request.expectedListingPrice())
                                    .max(BigDecimal.ZERO);
                        }
                        observer.onOutcome(execution.request, completed, clock.instant());
                        active = null;
                        signalIdle();
                        return completed;
                    }
                })
                .exceptionally(error -> {
                    String message = failureMessage(error);
                    adapter.returnToSafeScreen();
                    synchronized (this) {
                        return fail(execution, message, execution.purchaseConfirmed, execution.listingConfirmed);
                    }
                });
    }

    private Optional<String> precheck(TradeExecutionRequest request, AutomationConfig config) {
        Instant now = clock.instant();
        if (Duration.between(request.opportunityDetectedAt(), now).isNegative()
                || Duration.between(request.opportunityDetectedAt(), now).getSeconds()
                > Math.min(request.maximumListingAgeSeconds(), config.maximumListingAgeSeconds())) {
            return Optional.of("Opportunity is outside the permitted listing-age window.");
        }
        if (request.confidence() < config.minimumConfidence()) {
            return Optional.of("Confidence is below the automation minimum.");
        }
        if (request.comparableSales() < config.minimumComparableSales()) {
            return Optional.of("Comparable-sale count is below the automation minimum.");
        }
        if (request.estimatedNetProfit().compareTo(config.minimumNetProfit()) < 0
                || request.roiPercent().compareTo(config.minimumRoi()) < 0) {
            return Optional.of("Profit or ROI is below the automation minimum.");
        }
        if (request.riskLevel() == MarketRiskLevel.SEVERE || request.riskLevel() == MarketRiskLevel.UNKNOWN) {
            return Optional.of("Severe or unknown market risk is never executable.");
        }
        if (request.expectedListingPrice().compareTo(request.maximumAcceptablePurchasePrice()) > 0) {
            return Optional.of("Listing price exceeds the request maximum.");
        }
        if (request.requestedMode() == AutomationMode.DRY_RUN) {
            return Optional.empty();
        }
        if (!config.enabled() || config.mode() != request.requestedMode()
                || config.mode() == AutomationMode.DISABLED || config.mode() == AutomationMode.DRY_RUN) {
            return Optional.of("Real execution mode is disabled or does not match configuration.");
        }
        if (!AutomationServerPolicy.permitsGameplayAutomation(currentServer)) {
            return Optional.of("Gameplay automation is blocked on DonutSMP by its published server rules.");
        }
        if (config.requireBalanceVerification()) {
            return Optional.of("Balance verification requires a transactional private-server provider; "
                    + "the API stats balance is read-only.");
        }
        if (currentServer.isEmpty() || !config.serverAllowed(currentServer)
                || !currentServer.equals(armedServer)) {
            return Optional.of("Current server is not allowlisted and armed for this session.");
        }
        if (config.maximumPurchasesPerSession() < 1
                || purchasesThisSession >= config.maximumPurchasesPerSession()) {
            return Optional.of("Session purchase limit prohibits execution.");
        }
        if (config.maximumPurchasePrice().signum() <= 0
                || request.expectedListingPrice().compareTo(config.maximumPurchasePrice()) > 0) {
            return Optional.of("Configured maximum purchase price prohibits execution.");
        }
        if (config.maximumOpenExposure().signum() <= 0
                || openExposure.add(request.expectedListingPrice())
                .compareTo(config.maximumOpenExposure()) > 0) {
            return Optional.of("Configured exposure limit prohibits execution.");
        }
        if (Duration.between(lastPurchaseAt, now).getSeconds() < config.purchaseCooldownSeconds()) {
            return Optional.of("Purchase cooldown is active.");
        }
        return Optional.empty();
    }

    private <T> CompletableFuture<T> checked(ActiveExecution execution, CompletableFuture<T> future) {
        synchronized (this) {
            if (execution.cancelled || emergencyStopped) {
                return CompletableFuture.failedFuture(new ExecutionFailure("Execution was cancelled."));
            }
        }
        return future.orTimeout(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS).thenApply(value -> {
            synchronized (this) {
                if (execution.cancelled || emergencyStopped) {
                    throw new CompletionException(new ExecutionFailure("Execution was cancelled."));
                }
            }
            return value;
        });
    }

    private synchronized void transition(ActiveExecution execution, TradeExecutionState state, String message) {
        TradeExecutionState previous = execution.state;
        execution.state = state;
        statusMessage = message;
        observer.onTransition(execution.request, previous, state, message, clock.instant());
    }

    private synchronized TradeExecutionResult fail(
            ActiveExecution execution,
            String message,
            boolean purchaseConfirmed,
            boolean listingConfirmed
    ) {
        if (active != execution && execution.state.terminal()) {
            return result(execution, false, purchaseConfirmed, listingConfirmed, message, execution.relistPlan);
        }
        TradeExecutionState terminal = execution.cancelled
                ? TradeExecutionState.CANCELLED : TradeExecutionState.FAILED;
        transition(execution, terminal, message);
        TradeExecutionResult result = result(
                execution, false, purchaseConfirmed, listingConfirmed, message, execution.relistPlan
        );
        observer.onOutcome(execution.request, result, clock.instant());
        if (active == execution) {
            active = null;
            signalIdle();
        }
        return result;
    }

    private CompletableFuture<TradeExecutionResult> rejected(TradeExecutionRequest request, String message) {
        return CompletableFuture.completedFuture(new TradeExecutionResult(
                request.executionId(), TradeExecutionState.FAILED, false, false, false,
                message, Optional.empty()
        ));
    }

    private static boolean matches(TradeExecutionRequest request, AuctionListingCandidate candidate) {
        return request.listingKey().equals(candidate.listingKey())
                && request.itemFingerprint().equals(candidate.itemFingerprint())
                && request.itemId().equals(candidate.itemId())
                && request.expectedItemCount() == candidate.itemCount()
                && request.expectedListingPrice().compareTo(candidate.listingPrice()) == 0
                && (request.expectedSeller().isEmpty()
                    || request.expectedSeller().equals(candidate.seller()));
    }

    private TradeExecutionResult result(
            ActiveExecution execution,
            boolean successful,
            boolean purchaseConfirmed,
            boolean listingConfirmed,
            String message,
            Optional<RelistPlan> relistPlan
    ) {
        return new TradeExecutionResult(
                execution.request.executionId(), execution.state, successful,
                purchaseConfirmed, listingConfirmed, message, relistPlan
        );
    }

    private static String failureMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String normalizeServer(String value) {
        return Objects.requireNonNull(value, "serverAddress").strip().toLowerCase(java.util.Locale.ROOT);
    }

    private void signalIdle() {
        if (!idleSignal.isDone()) {
            idleSignal.complete(null);
        }
    }

    private static final class ActiveExecution {
        private final TradeExecutionRequest request;
        private AutomationConfig configuration;
        private TradeExecutionState state = TradeExecutionState.QUEUED;
        private boolean cancelled;
        private boolean purchaseConfirmed;
        private boolean listingConfirmed;
        private Optional<RelistPlan> relistPlan = Optional.empty();

        private ActiveExecution(TradeExecutionRequest request, AutomationConfig configuration) {
            this.request = request;
            this.configuration = configuration;
        }
    }

    private static final class ExecutionFailure extends RuntimeException {
        private ExecutionFailure(String message) {
            super(message == null || message.isBlank() ? "Auction execution failed closed." : message);
        }
    }
}
