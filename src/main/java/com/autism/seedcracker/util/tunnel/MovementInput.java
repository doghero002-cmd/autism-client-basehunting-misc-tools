package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Movement input relative to the *server-seen* (silent) yaw, not the client camera.
 *
 * Port of the wocky-client MovementUtil strafe + DirectionalInput octant-snapping to Mojang 26.2.
 * The key idea: when silent rotation is active the server thinks you're facing the dig/place
 * target, but your *movement* should still follow that same facing so the body and the look agree
 * on the server. This computes which of forward/back/left/right to hold so the player walks along
 * the desired world direction as seen from the SILENT yaw - letting you walk down a tunnel while
 * the crosshair stays on the dig face (instead of the classic "walk sideways into the wall" tell).
 *
 * {@link #apply(double, boolean, boolean)} drives the vanilla movement keys toward a world-space
 * direction (degrees), using the silent yaw when active, else the real camera yaw.
 */
public final class MovementInput {
    private static final Minecraft mc = Minecraft.getInstance();

    private MovementInput() {}

    /** Which movement keys to hold, resolved against the silent yaw. */
    public record Keys(boolean forward, boolean back, boolean left, boolean right) {
        public static final Keys NONE = new Keys(false, false, false, false);
    }

    /**
     * Compute the keys needed to walk along {@code worldDirDeg} (0=South,90=West,180=North,270=East,
     * Minecraft yaw convention) as seen from the current look yaw (silent if active, else camera).
     * The desired world direction is snapped to the nearest 45-degree octant, then decomposed into
     * forward/back/left/right relative to the look yaw (wocky DirectionalInput logic).
     */
    public static Keys keysFor(double worldDirDeg) {
        float lookYaw = SilentRotation.isActive() ? SilentRotation.getYaw()
            : (mc.player != null ? mc.player.getYRot() : 0.0f);
        // Relative angle between where we want to go and where we're looking.
        double rel = Mth.wrapDegrees(worldDirDeg - lookYaw);
        // Snap to the nearest 45-degree octant (8 movement sectors).
        double snapped = Math.round(rel / 45.0) * 45.0;
        // Normalize into [0,360).
        double sector = ((snapped % 360.0) + 360.0) % 360.0;
        return switch ((int) (sector / 45.0)) {
            case 0 -> new Keys(true, false, false, false);              // straight ahead
            case 1 -> new Keys(true, false, false, true);               // forward-right
            case 2 -> new Keys(false, false, false, true);              // right
            case 3 -> new Keys(false, true, false, true);               // back-right
            case 4 -> new Keys(false, true, false, false);              // back
            case 5 -> new Keys(false, true, true, false);               // back-left
            case 6 -> new Keys(false, false, true, false);              // left
            case 7 -> new Keys(true, false, true, false);               // forward-left
            default -> Keys.NONE;
        };
    }

    /** Compute keys to walk from the player's position toward a target XZ, silent-yaw-relative. */
    public static Keys keysToward(double targetX, double targetZ) {
        if (mc.player == null) return Keys.NONE;
        double dx = targetX - mc.player.getX();
        double dz = targetZ - mc.player.getZ();
        if (dx * dx + dz * dz < 1.0e-6) return Keys.NONE;
        double worldDir = Math.toDegrees(Math.atan2(-dx, dz));
        return keysFor(worldDir);
    }

    /**
     * Drive the vanilla movement keys toward a target XZ, silent-yaw-relative, with optional
     * sprint. Returns the keys held (so the caller can decide jump/sneak separately).
     */
    public static Keys apply(double targetX, double targetZ, boolean sprint, boolean allowBackward) {
        Keys k = keysToward(targetX, targetZ);
        // If backward movement isn't allowed, convert back sectors into a turn-then-forward walk.
        if (!allowBackward && (k.back && !k.forward)) {
            k = Keys.NONE; // caller should rotate first
        }
        setKeys(k, sprint);
        return k;
    }

    /** Press/release the movement keys for a resolved Keys, with optional sprint. */
    public static void setKeys(Keys k, boolean sprint) {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(k.forward);
        mc.options.keyDown.setDown(k.back);
        mc.options.keyLeft.setDown(k.left);
        mc.options.keyRight.setDown(k.right);
        if (sprint && k.forward && !k.back) mc.options.keySprint.setDown(true);
        else mc.options.keySprint.setDown(false);
    }

    /** Release all movement keys. */
    public static void release() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    /** True if the player has any horizontal movement input held (wocky isMoving). */
    public static boolean isMoving() {
        if (mc.player == null) return false;
        var input = mc.player.input;
        if (input == null) return false;
        net.minecraft.world.phys.Vec2 mv = input.getMoveVector();
        return mv != null && (mv.x != 0.0f || mv.y != 0.0f);
    }
}
