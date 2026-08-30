package com.example.donutflipscanner.gui.hud;

import com.example.donutflipscanner.data.FlipOpportunity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded, duplicate-suppressing alert queue containing immutable provider records. */
public final class OpportunityAlertQueue {
    public static final int MAXIMUM_QUEUED_ALERTS = 8;
    private static final int MAXIMUM_DUPLICATE_KEYS = 128;

    private final Duration displayDuration;
    private final Duration duplicateCooldown;
    private final ArrayDeque<QueuedAlert> queue = new ArrayDeque<>();
    private final Map<String, Instant> duplicateUntil = new LinkedHashMap<>();

    public OpportunityAlertQueue(Duration displayDuration, Duration duplicateCooldown) {
        this.displayDuration = positive(displayDuration, "displayDuration");
        this.duplicateCooldown = nonNegative(duplicateCooldown, "duplicateCooldown");
    }

    public synchronized boolean offer(FlipOpportunity opportunity, Instant now) {
        Objects.requireNonNull(opportunity, "opportunity");
        Objects.requireNonNull(now, "now");
        removeExpired(now);
        Instant suppressedUntil = duplicateUntil.get(opportunity.opportunityId());
        if (suppressedUntil != null && suppressedUntil.isAfter(now)) {
            return false;
        }
        duplicateUntil.put(opportunity.opportunityId(), now.plus(duplicateCooldown));
        trimDuplicateKeys();
        if (queue.size() >= MAXIMUM_QUEUED_ALERTS) {
            queue.removeFirst();
        }
        queue.addLast(new QueuedAlert(opportunity, now, now.plus(displayDuration)));
        return true;
    }

    public synchronized Optional<QueuedAlert> current(Instant now) {
        Objects.requireNonNull(now, "now");
        removeExpired(now);
        return Optional.ofNullable(queue.peekFirst());
    }

    public synchronized boolean dismissCurrent() {
        return queue.pollFirst() != null;
    }

    public synchronized void clear() {
        queue.clear();
        duplicateUntil.clear();
    }

    public synchronized int size(Instant now) {
        removeExpired(Objects.requireNonNull(now, "now"));
        return queue.size();
    }

    private void removeExpired(Instant now) {
        while (!queue.isEmpty() && !queue.peekFirst().expiresAt().isAfter(now)) {
            queue.removeFirst();
        }
        duplicateUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private void trimDuplicateKeys() {
        Iterator<String> keys = duplicateUntil.keySet().iterator();
        while (duplicateUntil.size() > MAXIMUM_DUPLICATE_KEYS && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private static Duration positive(Duration value, String name) {
        nonNegative(value, name);
        if (value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public record QueuedAlert(FlipOpportunity opportunity, Instant queuedAt, Instant expiresAt) {
        public QueuedAlert {
            Objects.requireNonNull(opportunity, "opportunity");
            Objects.requireNonNull(queuedAt, "queuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
