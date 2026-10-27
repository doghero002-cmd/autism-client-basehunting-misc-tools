package com.autism.seedcracker.spectre.mechanics;

import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

/**
 * Adapted from Spectre RenderDistanceToggleModule (GPL-3.0-only), revision
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, by cody-raves and contributors.
 * Keeps the threshold/rearm cycle; adds compare-before-restore ownership and pacing.
 * The addon's existing hold-ticks setting replaces the upstream fixed ten-tick hold.
 */
public final class RenderDistanceCycle {
    private boolean armed = true;
    private int savedDistance = -1;
    private int appliedDistance = -1;
    private int remainingTicks;

    /** Returns an option change, not a simulation-distance or chunk mutation. */
    public OptionalInt tick(int currentDistance, int blockY, int resetY, int lowDistance,
                            int holdTicks, BooleanSupplier permitAction) {
        if (ownsDistance()) {
            if (currentDistance != appliedDistance) {
                // A user or another module changed it. That owner now wins, including on disable.
                forgetOwnership();
                return OptionalInt.empty();
            }
            if (--remainingTicks <= 0) return release(currentDistance);
            return OptionalInt.empty();
        }

        if (blockY > resetY + 2) armed = true;
        if (!armed || blockY > resetY) return OptionalInt.empty();
        int low = Math.clamp(lowDistance, 2, 32);
        if (currentDistance == low) {
            armed = false;
            return OptionalInt.empty();
        }
        if (!permitAction.getAsBoolean()) return OptionalInt.empty();

        armed = false;
        savedDistance = currentDistance;
        appliedDistance = low;
        remainingTicks = Math.clamp(holdTicks, 5, 200);
        return OptionalInt.of(low);
    }

    public boolean ownsDistance() {
        return savedDistance >= 0;
    }

    /** All cleanup paths use the same ownership check; never restore over a manual change. */
    public OptionalInt release(int currentDistance) {
        OptionalInt restore = ownsDistance() && currentDistance == appliedDistance
            ? OptionalInt.of(savedDistance) : OptionalInt.empty();
        forgetOwnership();
        return restore;
    }

    /** Call only after releasing the old world's option ownership. */
    public void reset() {
        forgetOwnership();
        armed = true;
    }

    private void forgetOwnership() {
        savedDistance = -1;
        appliedDistance = -1;
        remainingTicks = 0;
    }
}
