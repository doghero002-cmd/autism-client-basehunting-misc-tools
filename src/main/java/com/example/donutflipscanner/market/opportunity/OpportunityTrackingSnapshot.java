package com.example.donutflipscanner.market.opportunity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Serializable state that scanner orchestration can persist and restore in a later chunk. */
public record OpportunityTrackingSnapshot(
        OpportunityEvaluation evaluation,
        OpportunityRevision revision,
        Optional<Instant> lastAlertedAt
) {
    public OpportunityTrackingSnapshot {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(revision, "revision");
        lastAlertedAt = Objects.requireNonNullElse(lastAlertedAt, Optional.empty());
    }
}
