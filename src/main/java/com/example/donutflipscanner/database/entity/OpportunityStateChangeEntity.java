package com.example.donutflipscanner.database.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record OpportunityStateChangeEntity(
        long changeId,
        String opportunityId,
        Optional<String> previousState,
        String newState,
        Instant changedAt,
        Optional<String> reason
) {
    public OpportunityStateChangeEntity {
        if (changeId < 0) {
            throw new IllegalArgumentException("changeId must not be negative");
        }
        opportunityId = EntityChecks.text(opportunityId, "opportunityId");
        previousState = EntityChecks.optional(previousState);
        newState = EntityChecks.text(newState, "newState");
        Objects.requireNonNull(changedAt, "changedAt");
        reason = EntityChecks.optional(reason);
    }
}
