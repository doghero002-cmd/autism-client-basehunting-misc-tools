package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Simulated player physics (forward simulation).
 *
 * A focused port of the wocky-client SimulatedPlayer to Mojang 26.2. Instead of the full vanilla
 * travel() pipeline, this replays the parts our movement code actually needs - per-tick gravity,
 * air drag, step-height (0.5 + maxUpStep), and swept-AABB collision via
 * {@link Entity#collideBoundingBox} - to answer, before committing to a move:
 *
 *  - {@link #predictLandingY}: the Y the player lands on after walking/falling in a direction.
 *  - {@link #predictFallDamage}: the fall damage (hearts) from stepping off an edge.
 *  - {@link #willCollideHorizontally}: whether a step in a direction hits a wall.
 *  - {@link #canReach}: whether a block is genuinely reachable (not just straight-line close).
 *
 * Far more reliable than the naive "probe straight down" / straight-line-distance checks, which
 * can't see overhangs, ledges or liquid.
 */
public final class SimulatedPlayer {
    private static final Minecraft mc = Minecraft.getInstance();

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    private Vec3 pos;
    private Vec3 vel;
    private boolean onGround;
    private double fallDistance;
    private final CollisionContext ctx;

    private SimulatedPlayer(Vec3 pos, Vec3 vel, boolean onGround) {
        this.pos = pos;
        this.vel = vel;
        this.onGround = onGround;
        this.fallDistance = 0.0;
        this.ctx = mc.player != null ? CollisionContext.of(mc.player) : null;
    }

    /** Snapshot the real player's current physics state. */
    public static SimulatedPlayer fromPlayer() {
        if (mc.player == null) return new SimulatedPlayer(Vec3.ZERO, Vec3.ZERO, true);
        return new SimulatedPlayer(mc.player.position(), mc.player.getDeltaMovement(), mc.player.onGround());
    }

    private AABB box() {
        double w = PLAYER_WIDTH / 2.0;
        return new AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + PLAYER_HEIGHT, pos.z + w);
    }

    /** Advance one physics tick with the given horizontal input direction (radians) + sprint. */
    public void tick(double moveYawRad, boolean sprint, boolean jump) {
        if (mc.level == null) return;
        // Input acceleration (vanilla-ish).
        double accel = onGround ? 0.1 : (sprint ? 0.026 : 0.02);
        double ix = -Math.sin(moveYawRad) * accel;
        double iz = Math.cos(moveYawRad) * accel;
        vel = new Vec3(vel.x + ix, vel.y, vel.z + iz);

        // Gravity + drag.
        vel = new Vec3(vel.x * (onGround ? 0.91 : DRAG), (vel.y - GRAVITY) * DRAG, vel.z * (onGround ? 0.91 : DRAG));
        if (jump && onGround) vel = new Vec3(vel.x, 0.42, vel.z);

        // Swept collision.
        Vec3 before = vel;
        Vec3 after = ctx != null
            ? Entity.collideBoundingBox(ctx, vel, box(), mc.level, java.util.List.of())
            : vel;
        boolean xColl = Math.abs(before.x - after.x) > 1.0e-7;
        boolean yColl = Math.abs(before.y - after.y) > 1.0e-7;
        boolean zColl = Math.abs(before.z - after.z) > 1.0e-7;

        // Step up 0.5 (maxUpStep) if blocked horizontally and (on ground or falling onto it).
        float step = mc.player != null ? mc.player.maxUpStep() : 0.5f;
        if ((onGround || (yColl && before.y < 0)) && (xColl || zColl) && step > 0) {
            Vec3 stepTry = ctx != null
                ? Entity.collideBoundingBox(ctx, new Vec3(before.x, step, before.z), box(), mc.level, java.util.List.of())
                : before;
            if (stepTry.lengthSqr() > after.lengthSqr()) after = stepTry;
        }

        pos = pos.add(after);
        horizontalCollision = xColl || zColl;
        boolean wasOnGround = onGround;
        onGround = yColl && before.y < 0;
        if (onGround) {
            fallDistance = 0.0;
        } else if (before.y < 0) {
            fallDistance += -before.y;
        }
        vel = new Vec3(xColl ? 0 : after.x, onGround ? 0 : after.y, zColl ? 0 : after.z);
    }

    private boolean horizontalCollision = false;

    public Vec3 pos() { return pos; }
    public boolean onGround() { return onGround; }
    public boolean horizontalCollision() { return horizontalCollision; }
    public double fallDistance() { return fallDistance; }

    // ---- static convenience queries ----

    /**
     * Predict the Y level the player would land on after walking {@code blocks} forward in
     * {@code yawRad} (or off the resulting edge), simulating up to {@code maxTicks} ticks.
     * Returns the landing Y (player's feet), or NaN if it never lands (void).
     */
    public static double predictLandingY(double yawRad, int maxTicks) {
        SimulatedPlayer sim = fromPlayer();
        for (int i = 0; i < maxTicks; i++) {
            sim.tick(yawRad, false, false);
            if (sim.onGround() && sim.fallDistance() > 0.5) return sim.pos().y;
        }
        return sim.onGround() ? sim.pos().y : Double.NaN;
    }

    /**
     * Predict the fall damage (hearts) from continuing forward in {@code yawRad} until landing
     * (or {@code maxTicks} elapse). Vanilla: damage = max(0, ceil(fallDistance - 3)).
     */
    public static double predictFallDamage(double yawRad, int maxTicks) {
        SimulatedPlayer sim = fromPlayer();
        for (int i = 0; i < maxTicks; i++) {
            sim.tick(yawRad, false, false);
            if (sim.onGround()) return Math.max(0, Math.ceil(sim.fallDistance() - 3.0));
        }
        return 0.0;
    }

    /** True if walking forward in {@code yawRad} hits a horizontal wall within {@code ticks}. */
    public static boolean willCollideHorizontally(double yawRad, int ticks) {
        SimulatedPlayer sim = fromPlayer();
        for (int i = 0; i < ticks; i++) {
            sim.tick(yawRad, false, false);
            if (sim.horizontalCollision()) return true;
        }
        return false;
    }

    /**
     * True if a block is genuinely reachable: within reach distance AND there is a walkable /
     * landable position adjacent to it (predicted via forward-sim), not just straight-line close.
     */
    public static boolean canReach(BlockPos target, double maxReach) {
        if (mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        double dist = eye.distanceTo(Vec3.atCenterOf(target));
        if (dist > maxReach) return false;
        // Simulate a few ticks toward the target; reachable if we can land on or beside it.
        double yaw = Math.atan2(-(target.getX() + 0.5 - mc.player.getX()), target.getZ() + 0.5 - mc.player.getZ());
        SimulatedPlayer sim = fromPlayer();
        for (int i = 0; i < 12; i++) {
            sim.tick(yaw, false, false);
            double d = sim.pos().distanceTo(Vec3.atCenterOf(target));
            if (d <= maxReach && sim.onGround()) return true;
        }
        return dist <= maxReach;
    }
}
