package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

/**
 * Silent rotation.
 *
 * Applies a yaw/pitch to the *outgoing movement packets* without ever moving the client camera,
 * so the server sees the player looking at the dig/place target but the screen never snaps
 * (which is what anticheats flag). Mirrors the Water Client "RotationUtil.setSilentRotation"
 * approach: while active, {@link #processPacket} rewrites each {@link ServerboundMovePlayerPacket}
 * to the silent yaw/pitch via the AUTISM Client's move-packet accessor.
 *
 * Use {@link #apply(float, float)} to set the silent rotation and {@link #clear()} to stop.
 */
public final class SilentRotation {
    private static final Minecraft mc = Minecraft.getInstance();

    private static volatile boolean active = false;
    private static float yaw = 0.0f;
    private static float pitch = 0.0f;
    // Render-lerp state (Wocky RotationClientHandler.tickCamera): the on-screen view eases toward
    // the silent target so the camera doesn't freeze/jerk while packets aim at the block.
    private static float renderYaw = 0.0f;
    private static float renderPitch = 0.0f;
    private static boolean renderInit = false;
    // Last real camera rotation seen, so we accumulate the player's own mouse deltas (keeps the
    // stored camera yaw continuous across a silent engage/disengage).
    private static float lastCamYaw = 0.0f;
    private static float lastCamPitch = 0.0f;

    private SilentRotation() {}

    /** Set the silent rotation (server-side view) without moving the client camera. */
    public static void apply(float y, float p) {
        yaw = normalizeYaw(y);
        pitch = Mth.clamp(p, -90.0f, 90.0f);
        if (!renderInit && mc.player != null) {
            renderYaw = mc.player.getYRot();
            renderPitch = mc.player.getXRot();
            lastCamYaw = renderYaw;
            lastCamPitch = renderPitch;
            renderInit = true;
        }
        active = true;
    }

    /** Stop driving silent rotation (packets go back to the camera's real rotation). */
    public static void clear() {
        active = false;
        renderInit = false;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        Gcd.resetRemainders();
    }

    public static boolean isActive() {
        return active;
    }

    public static float getYaw() {
        return active ? yaw : (mc.player != null ? mc.player.getYRot() : 0.0f);
    }

    public static float getPitch() {
        return active ? pitch : (mc.player != null ? mc.player.getXRot() : 0.0f);
    }

    /**
     * The render-space yaw: eases the on-screen view toward the silent target by 0.5/tick while
     * active (Wocky tickCamera), so the visible camera moves smoothly instead of freezing. Returns
     * the real camera yaw when inactive.
     */
    public static float renderYaw() {
        if (!active || mc.player == null) return mc.player != null ? mc.player.getYRot() : 0.0f;
        renderYaw += (normalizeYaw(yaw) - renderYaw) * 0.5f;
        return renderYaw;
    }

    /** The render-space pitch (eases toward the silent target by 0.5/tick while active). */
    public static float renderPitch() {
        if (!active || mc.player == null) return mc.player != null ? mc.player.getXRot() : 0.0f;
        renderPitch += (pitch - renderPitch) * 0.5f;
        return Mth.clamp(renderPitch, -90.0f, 90.0f);
    }

    /**
     * Call each tick with the player's real camera rotation. Accumulates the player's own mouse
     * deltas so the stored camera yaw stays continuous across silent engage/disengage.
     */
    public static void trackMouse(float camYaw, float camPitch) {
        if (active && renderInit) {
            float dYaw = camYaw - lastCamYaw;
            float dPitch = camPitch - lastCamPitch;
            renderYaw += dYaw;
            renderPitch += dPitch;
        }
        lastCamYaw = camYaw;
        lastCamPitch = camPitch;
    }

    /**
     * Rewrite an outgoing packet's rotation to the silent values while active. Call from a
     * module's {@code onPacketSend}. Returns the (possibly rewritten) packet.
     */
    // Last packet rotation actually sent, so GCD snapping applies to the DELTA (what Grim
    // checks), not the absolute angle.
    private static float lastSentYaw = Float.NaN;
    private static float lastSentPitch = Float.NaN;

    @SuppressWarnings("unchecked")
    public static Packet<?> processPacket(Packet<?> packet) {
        if (!active || !(packet instanceof ServerboundMovePlayerPacket)) return packet;
        try {
            ServerboundMovePlayerPacket move = (ServerboundMovePlayerPacket) packet;
            autismclient.mixin.accessor.AutismMovePlayerPacketAccessor acc =
                (autismclient.mixin.accessor.AutismMovePlayerPacketAccessor) packet;
            // Snap the rotation DELTA (not the absolute angle) to the mouse-sensitivity GCD grid:
            // Grim validates consecutive-rotation deltas as integer mouse-count multiples.
            if (Float.isNaN(lastSentYaw)) {
                lastSentYaw = move.getYRot(mc.player != null ? mc.player.getYRot() : 0f);
                lastSentPitch = move.getXRot(mc.player != null ? mc.player.getXRot() : 0f);
            }
            float qy = Gcd.quantizeDelta(lastSentYaw, yaw, true);
            float qp = Mth.clamp(Gcd.quantizeDelta(lastSentPitch, pitch, false), -90.0f, 90.0f);
            acc.autism$setYRot(qy);
            acc.autism$setXRot(qp);
            lastSentYaw = qy;
            lastSentPitch = qp;
        } catch (Throwable t) {
            // If the accessor breaks, silent rotation is silently OFF while modules think it's on
            // (raw camera rotation goes to the server = flag risk). Log once so it's visible.
            if (!rewriteFailureLogged) {
                rewriteFailureLogged = true;
                com.autism.seedcracker.util.FlagLog.flag("ERROR", "SilentRotation",
                    "move-packet rewrite failed - silent rotation inactive: " + t);
            }
        }
        return packet;
    }

    private static boolean rewriteFailureLogged = false;

    /**
     * Mouse-sensitivity GCD quantizer (Grim rotation check). Snaps rotation DELTAS to the grid a
     * real mouse at the current sensitivity would produce, carrying the sub-GCD remainder forward
     * (AlphaDLC HolyWorldRotation) so small errors accumulate into a real step later instead of
     * being silently dropped - exactly like integer mouse counts do.
     */
    public static final class Gcd {
        private Gcd() {}

        private static double yawRemainder = 0.0;
        private static double pitchRemainder = 0.0;

        static double step() {
            Minecraft mc = Minecraft.getInstance();
            double sens = 0.5;
            try { sens = mc.options.sensitivity().get(); } catch (Throwable ignored) {}
            return com.autism.seedcracker.util.pure.GcdMath.step(sens);
        }

        /** Legacy absolute-angle snap (kept for callers that only have one angle). */
        public static float quantize(float angleDeg) {
            return com.autism.seedcracker.util.pure.GcdMath.quantize(angleDeg, step());
        }

        /** Snap {@code from -> to} so the applied delta is an integer GCD multiple, with remainder carry. */
        public static float quantizeDelta(float from, float to, boolean isYaw) {
            var q = com.autism.seedcracker.util.pure.GcdMath.quantizeDelta(
                from, to, isYaw ? yawRemainder : pitchRemainder,
                step(), com.autism.seedcracker.util.Tuning.GCD_REMAINDER_CLAMP, isYaw);
            if (isYaw) yawRemainder = q.remainder(); else pitchRemainder = q.remainder();
            return q.angle();
        }

        static void resetRemainders() { yawRemainder = 0.0; pitchRemainder = 0.0; }
    }

    private static float normalizeYaw(float y) {
        y = y % 360.0f;
        if (y >= 180.0f) y -= 360.0f;
        if (y < -180.0f) y += 360.0f;
        return y;
    }
}
