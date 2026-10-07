package com.autism.seedcracker.motion;

import java.util.concurrent.ThreadLocalRandom;

import com.autism.seedcracker.motion.pure.AimArbiter;
import com.autism.seedcracker.motion.pure.RotationMath;
import com.autism.seedcracker.util.Aim;
import com.autism.seedcracker.util.tunnel.SilentRotation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Single owner of the player's view. Every module that turns the camera goes through here, so two
 * modules can never fight over it (the alternating-aim jitter anti-cheats look for).
 *
 * {@link #request} eases toward a target with {@link RotationMath}; {@link #write} is for modules
 * that already smooth their own aim and just need ownership + the GCD-safe write. Both return
 * false when another module holds the view, and the caller should then skip its click/attack.
 */
public final class RotationEngine {
    private RotationEngine() {}

    public static final int PRIORITY_IDLE = 0;
    public static final int PRIORITY_MOVE = 10;
    public static final int PRIORITY_INTERACT = 20;
    public static final int PRIORITY_COMBAT = 30;
    public static final int PRIORITY_SAFETY = 40;

    private static final AimArbiter ARBITER = new AimArbiter(3);
    private static final RotationMath.State STATE = new RotationMath.State();

    private static String easedOwner;
    private static long stepTick = Long.MIN_VALUE;
    private static float tickStartYaw, tickStartPitch;
    private static boolean silent;
    private static boolean converged;

    /**
     * Ease toward a view this tick. Returns true once on target (safe to click). Call every tick
     * while aiming; ownership lapses after a few ticks without a request.
     */
    public static boolean request(String who, int priority, float yaw, float pitch, RotationMath.Profile p, boolean silentAim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        long now = mc.level.getGameTime();
        if (!ARBITER.request(who, priority, now)) return false;
        if (!who.equals(easedOwner)) {
            STATE.reset();
            easedOwner = who;
        }
        if (silent && !silentAim) SilentRotation.clear();
        silent = silentAim;
        // Step once per tick from the view the tick started with: a later winner replaces, never stacks.
        if (now != stepTick) {
            stepTick = now;
            tickStartYaw = silent ? SilentRotation.getYaw() : mc.player.getYRot();
            tickStartPitch = silent ? SilentRotation.getPitch() : mc.player.getXRot();
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        RotationMath.Step st = RotationMath.step(tickStartYaw, tickStartPitch, yaw, pitch,
            p == null ? RotationMath.Profile.defaults() : p, STATE, rnd.nextDouble(), rnd.nextDouble());
        if (silent) SilentRotation.apply(st.yaw(), st.pitch());
        else Aim.set(mc, st.yaw(), st.pitch());
        converged = st.converged();
        return converged;
    }

    public static boolean request(String who, int priority, float yaw, float pitch) {
        return request(who, priority, yaw, pitch, null, false);
    }

    /** Ease toward a world point (eye-relative). */
    public static boolean lookAt(String who, int priority, Vec3 point) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        float[] r = RotationMath.lookAt(point.x - eye.x, point.y - eye.y, point.z - eye.z);
        return request(who, priority, r[0], r[1]);
    }

    /**
     * Write an already-smoothed view if {@code who} owns the camera this tick. For modules with
     * their own human aim model (LegitMovement etc.); returns false when they don't own the view.
     */
    public static boolean write(String who, int priority, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (!ARBITER.request(who, priority, mc.level.getGameTime())) return false;
        if (silent) {
            SilentRotation.clear();
            silent = false;
        }
        easedOwner = null;
        Aim.set(mc, yaw, pitch);
        return true;
    }

    /** True if {@code who} would get the view this tick (no side effects on the camera). */
    public static boolean canTake(String who, int priority) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && ARBITER.canTake(who, priority, mc.level.getGameTime());
    }

    /** Drop ownership (module disable / done aiming); silent aim is released too. */
    public static void release(String who) {
        if (!who.equals(ARBITER.owner())) return;
        ARBITER.release(who);
        STATE.reset();
        easedOwner = null;
        if (silent) SilentRotation.clear();
        silent = false;
    }

    public static String owner() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : ARBITER.activeOwner(mc.level.getGameTime());
    }
}
