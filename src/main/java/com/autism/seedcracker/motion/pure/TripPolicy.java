package com.autism.seedcracker.motion.pure;

/**
 * When a long trip should give up (pure, unit tested). Finishing a segment is normal progress and
 * never counts; only failures (stuck, knocked off path, unbreakable block) spend the budget, and a
 * segment that gets clearly closer to the goal refunds it. That lets a 10k-block walk run across
 * dozens of segments while a bot wedged in a hole still stops quickly.
 */
public final class TripPolicy {

    public enum Verdict { CONTINUE, GIVE_UP }

    private final int maxFailures;
    private final double minProgress;
    private int failures;
    private double bestDistance = Double.POSITIVE_INFINITY;

    public TripPolicy(int maxFailures, double minProgress) {
        this.maxFailures = maxFailures;
        this.minProgress = minProgress;
    }

    /** A segment finished normally at {@code distanceToGoal}. */
    public Verdict segmentDone(double distanceToGoal) {
        if (distanceToGoal < bestDistance - minProgress) {
            bestDistance = distanceToGoal;
            failures = 0;
            return Verdict.CONTINUE;
        }
        // Finished a segment but ended no closer: a planner loop, treat as a failure.
        return failure(distanceToGoal);
    }

    /** Stuck / off path / blocked. */
    public Verdict failure(double distanceToGoal) {
        if (distanceToGoal < bestDistance - minProgress) {
            bestDistance = distanceToGoal;
            failures = 0;
        }
        return ++failures > maxFailures ? Verdict.GIVE_UP : Verdict.CONTINUE;
    }

    public int failures() {
        return failures;
    }

    public double bestDistance() {
        return bestDistance;
    }
}
