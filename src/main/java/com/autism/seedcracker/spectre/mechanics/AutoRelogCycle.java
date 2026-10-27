package com.autism.seedcracker.spectre.mechanics;

import java.util.function.BooleanSupplier;

/**
 * Height arming adapted from Spectre AutoRelogModule (GPL-3.0-only), revision
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, cody-raves and contributors.
 * Preserves forty high ticks and the four-block rearm margin; adds bounded teardown,
 * paced reconnect and connect deadlines. Cancellation consumes the attempt; there is no retry.
 */
public final class AutoRelogCycle {
    public enum Phase { WATCHING, DISCONNECTING, WAITING, CONNECTING }

    private Phase phase = Phase.WATCHING;
    private int aboveTicks;
    private long delayMs;
    private long reconnectAtMs;
    private long deadlineMs;
    private String stopReason = "";

    public boolean observe(boolean eligible, double y, int line, BooleanSupplier permitAction) {
        if (phase != Phase.WATCHING) return false;
        if (!eligible || !Double.isFinite(y)) {
            aboveTicks = 0;
            return false;
        }
        int threshold = Math.clamp(line, -60, 16);
        if (y >= threshold + 4) {
            aboveTicks = Math.min(40, aboveTicks + 1);
        } else if (y < threshold) {
            if (aboveTicks >= 40 && permitAction.getAsBoolean()) {
                aboveTicks = 0;
                return true;
            }
            if (aboveTicks < 40) aboveTicks = 0;
        }
        // Like upstream, the four-block band retains (but does not increase) the arming count.
        return false;
    }

    public void begin(long now, double delaySeconds) {
        if (phase != Phase.WATCHING) throw new IllegalStateException("Relog already pending");
        double safeDelay = Double.isFinite(delaySeconds) ? Math.clamp(delaySeconds, 1.0, 30.0) : 3.0;
        delayMs = Math.round(safeDelay * 1000);
        phase = Phase.DISCONNECTING;
        deadlineMs = now + 10_000;
        aboveTicks = 0;
        stopReason = "";
    }

    /** Delay starts after the old connection/world is actually gone, not at the disconnect call. */
    public void observeTeardown(long now, boolean sameOrigin, boolean fullyDisconnected, boolean ownsWaitScreen) {
        if (phase != Phase.DISCONNECTING) return;
        if (now >= deadlineMs) {
            stop("disconnect timed out");
        } else if (!sameOrigin) {
            stop("connection changed");
        } else if (fullyDisconnected) {
            if (!ownsWaitScreen) {
                stop("reconnect cancelled by navigation");
            } else {
                phase = Phase.WAITING;
                reconnectAtMs = now + delayMs;
                deadlineMs = reconnectAtMs + 10_000;
            }
        }
    }

    public boolean reconnectDue(long now, boolean ownsEmptyContext, BooleanSupplier permitAction) {
        if (phase != Phase.WAITING) return false;
        if (!ownsEmptyContext) {
            stop("reconnect cancelled by navigation or another connection");
        } else if (now >= deadlineMs) {
            stop("reconnect deadline expired");
        } else if (now >= reconnectAtMs && permitAction.getAsBoolean()) {
            phase = Phase.CONNECTING;
            deadlineMs = now + 60_000;
            return true;
        }
        return false;
    }

    public void observeConnecting(long now, boolean stillOwnsAttempt) {
        if (phase != Phase.CONNECTING) return;
        if (!stillOwnsAttempt) stop("connection attempt cancelled or replaced");
        else if (now >= deadlineMs) stop("connection attempt timed out");
    }

    public void resetArming() { aboveTicks = 0; }

    public void cancel() {
        phase = Phase.WATCHING;
        aboveTicks = 0;
        reconnectAtMs = deadlineMs = delayMs = 0;
        stopReason = "";
    }

    private void stop(String reason) {
        cancel();
        stopReason = reason;
    }

    public Phase phase() { return phase; }
    public String stopReason() { return stopReason; }
    public boolean pending() { return phase != Phase.WATCHING; }
    public int aboveTicks() { return aboveTicks; }
}
