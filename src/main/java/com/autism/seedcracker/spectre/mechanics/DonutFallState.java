package com.autism.seedcracker.spectre.mechanics;

/**
 * SPDX-License-Identifier: GPL-3.0-only
 * Spectre DonutNoFall (d5a7dd3): prior-sample fall trigger and four grounded-packet reset.
 * Planning does not advance state: a failed replacement send cannot acknowledge a landing.
 */
public final class DonutFallState {
    private double previousFallDistance;
    private boolean suppressGround;
    private int groundedPackets;
    private long revision;

    public record Adjustment(long revision, int groundedPackets, double yOffset) {}

    public void observeFall(double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            reset();
            return;
        }
        if (previousFallDistance > 3.0) suppressGround = true;
        previousFallDistance = distance;
    }

    public Adjustment prepare(boolean originallyGrounded) {
        if (!suppressGround) return null;
        int next = groundedPackets + (originallyGrounded ? 1 : 0);
        return new Adjustment(revision, next, (next & 1) == 1 ? 1.0E-8 : 0.0);
    }

    public void sent(Adjustment adjustment) {
        if (adjustment == null || adjustment.revision() != revision || !suppressGround) return;
        groundedPackets = adjustment.groundedPackets();
        revision++;
        if (groundedPackets >= 4) reset();
    }

    public boolean active() { return suppressGround; }

    public void reset() {
        previousFallDistance = 0.0;
        suppressGround = false;
        groundedPackets = 0;
        revision++;
    }
}
