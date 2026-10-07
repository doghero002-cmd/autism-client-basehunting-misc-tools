package com.autism.seedcracker.motion.pure;

/**
 * Who owns the view this tick (pure, unit tested). The highest priority request in a tick wins;
 * on a tie the current owner keeps it, so two equal modules can't ping-pong the camera every
 * other tick. A different owner may only take over after {@code handoffTicks} of the old owner
 * not asking, which stops a briefly-silent module losing the view mid-swing.
 */
public final class AimArbiter {

    private final int handoffTicks;
    private String owner;
    private int ownerPriority;
    private long ownerLastTick = Long.MIN_VALUE / 2;
    private long tick = Long.MIN_VALUE;
    private int bestThisTick = Integer.MIN_VALUE;

    public AimArbiter(int handoffTicks) {
        this.handoffTicks = Math.max(0, handoffTicks);
    }

    /** True if {@code who} gets to write the view at {@code now}. */
    public boolean request(String who, int priority, long now) {
        if (now != tick) {
            tick = now;
            bestThisTick = Integer.MIN_VALUE;
        }
        boolean isOwner = who.equals(owner);
        boolean ownerActive = owner != null && now - ownerLastTick <= handoffTicks;
        if (!isOwner && ownerActive) {
            // A strictly higher priority (safety, combat) always preempts; equals wait their turn.
            if (priority <= ownerPriority) return false;
        }
        if (priority < bestThisTick) return false;
        bestThisTick = priority;
        owner = who;
        ownerPriority = priority;
        ownerLastTick = now;
        return true;
    }

    /** Same rules as {@link #request} but changes nothing. */
    public boolean canTake(String who, int priority, long now) {
        if (who.equals(owner) || owner == null || now - ownerLastTick > handoffTicks) {
            return now != tick || priority >= bestThisTick;
        }
        return priority > ownerPriority && (now != tick || priority >= bestThisTick);
    }

    public void release(String who) {
        if (who.equals(owner)) {
            owner = null;
            ownerLastTick = Long.MIN_VALUE / 2;
        }
    }

    public String owner() {
        return owner;
    }

    /** Owner as of {@code now}, or null once it has gone quiet for longer than the hand-off window. */
    public String activeOwner(long now) {
        return owner != null && now - ownerLastTick <= handoffTicks ? owner : null;
    }
}
