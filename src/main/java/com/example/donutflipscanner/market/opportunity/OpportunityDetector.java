package com.example.donutflipscanner.market.opportunity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stateful duplicate and alert gate around the pure evaluator. Scanner scheduling remains a later concern.
 * All methods are synchronized so a future worker can safely feed completed snapshots into it.
 */
public final class OpportunityDetector {
    public static final int MAXIMUM_TRACKED_OPPORTUNITIES = 10_000;
    private final OpportunityIdFactory idFactory;
    private final OpportunityEvaluator evaluator;
    private final OpportunityReevaluationPolicy reevaluationPolicy;
    private final Map<String, TrackedOpportunity> tracked = new LinkedHashMap<>();

    public OpportunityDetector() {
        this(new OpportunityIdFactory(), new OpportunityEvaluator(), new OpportunityReevaluationPolicy());
    }

    OpportunityDetector(
            OpportunityIdFactory idFactory,
            OpportunityEvaluator evaluator,
            OpportunityReevaluationPolicy reevaluationPolicy
    ) {
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.reevaluationPolicy = Objects.requireNonNull(reevaluationPolicy, "reevaluationPolicy");
    }

    public synchronized OpportunityDetectionResult evaluate(
            OpportunityEvaluationRequest request,
            OpportunityEvaluationConfig config,
            Instant now
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");
        String id = idFactory.create(
                request.listing().listingKey(), config.evaluationVersion(), request.item().fingerprint().sha256()
        );
        TrackedOpportunity previous = tracked.get(id);
        OpportunityRevision revision = OpportunityRevision.from(request, config);
        ReevaluationDecision decision = reevaluationPolicy.decide(
                request, revision, Optional.ofNullable(previous).map(TrackedOpportunity::revision),
                Optional.ofNullable(previous).map(value -> value.evaluation().evaluatedAt()),
                config.staleReevaluationInterval(), now
        );
        if (!decision.shouldEvaluate()) {
            return new OpportunityDetectionResult(decision, Optional.empty(), true);
        }

        Optional<OpportunityState> previousState = Optional.ofNullable(previous)
                .map(value -> value.evaluation().state());
        OpportunityEvaluation evaluation = evaluator.evaluate(request, config, now, previousState);
        Optional<Instant> lastAlertedAt = Optional.ofNullable(previous)
                .flatMap(TrackedOpportunity::lastAlertedAt);
        if (evaluation.highPriorityAlertEligible() && cooldownActive(lastAlertedAt, now, config.alertCooldown())) {
            List<AlertSuppressionReason> suppressions = new ArrayList<>(evaluation.alertSuppressions());
            suppressions.add(AlertSuppressionReason.ALERT_COOLDOWN_ACTIVE);
            evaluation = evaluation.withAlertDecision(false, List.copyOf(suppressions));
        }
        remember(id, new TrackedOpportunity(evaluation, revision, lastAlertedAt));
        return new OpportunityDetectionResult(decision, Optional.of(evaluation), false);
    }

    /** Records that the caller actually delivered an alert; evaluation alone does not consume cooldown. */
    public synchronized boolean recordAlerted(String opportunityId, Instant alertedAt) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(alertedAt, "alertedAt");
        TrackedOpportunity current = tracked.get(opportunityId);
        if (current == null || !current.evaluation().highPriorityAlertEligible()) {
            return false;
        }
        remember(opportunityId, new TrackedOpportunity(
                current.evaluation(), current.revision(), Optional.of(alertedAt)
        ));
        return true;
    }

    public synchronized boolean updateState(String opportunityId, OpportunityState state) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(state, "state");
        TrackedOpportunity current = tracked.get(opportunityId);
        if (current == null) {
            return false;
        }
        remember(opportunityId, new TrackedOpportunity(
                current.evaluation().withState(state), current.revision(), current.lastAlertedAt()
        ));
        return true;
    }

    public synchronized Optional<OpportunityEvaluation> find(String opportunityId) {
        return Optional.ofNullable(tracked.get(Objects.requireNonNull(opportunityId, "opportunityId")))
                .map(TrackedOpportunity::evaluation);
    }

    public synchronized List<OpportunityTrackingSnapshot> snapshots() {
        return tracked.values().stream()
                .map(value -> new OpportunityTrackingSnapshot(
                        value.evaluation(), value.revision(), value.lastAlertedAt()
                ))
                .toList();
    }

    public synchronized void restore(OpportunityTrackingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        remember(snapshot.evaluation().opportunityId(), new TrackedOpportunity(
                snapshot.evaluation(), snapshot.revision(), snapshot.lastAlertedAt()
        ));
    }

    public synchronized int trackedCount() {
        return tracked.size();
    }

    public synchronized long estimatedCacheBytes() {
        return tracked.size() * 1_024L;
    }

    private void remember(String id, TrackedOpportunity value) {
        if (!tracked.containsKey(id) && tracked.size() >= MAXIMUM_TRACKED_OPPORTUNITIES) {
            tracked.remove(tracked.keySet().iterator().next());
        }
        tracked.put(id, value);
    }

    private static boolean cooldownActive(Optional<Instant> lastAlertedAt, Instant now, Duration cooldown) {
        if (lastAlertedAt.isEmpty()) {
            return false;
        }
        Duration elapsed = Duration.between(lastAlertedAt.orElseThrow(), now);
        return elapsed.isNegative() || elapsed.compareTo(cooldown) < 0;
    }

    private record TrackedOpportunity(
            OpportunityEvaluation evaluation,
            OpportunityRevision revision,
            Optional<Instant> lastAlertedAt
    ) {
        private TrackedOpportunity {
            Objects.requireNonNull(evaluation, "evaluation");
            Objects.requireNonNull(revision, "revision");
            lastAlertedAt = Objects.requireNonNullElse(lastAlertedAt, Optional.empty());
        }
    }
}
