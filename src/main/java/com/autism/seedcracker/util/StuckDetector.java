package com.autism.seedcracker.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stuck detector (per movement module).
 *
 * Watches each registered movement module's position every tick. If a module hasn't moved more
 * than {@code threshold} blocks for {@code maxTicks} ticks (and isn't in a known wait state), it
 * logs a STUCK event to the flag log with the module's current state/action (supplied by the
 * module via {@link #setAction}), so the exact looping behaviour can be diagnosed and fixed.
 *
 * Usage (one instance per module):
 *   private final StuckDetector stuck = new StuckDetector("TunnelBaseWaterModule");
 *   // in tick(): stuck.setAction("MINING dir=" + currentDirection); stuck.tick(mc);
 *   // the detector auto-logs when the module stops making progress.
 *
 * Logs go through {@link FlagLog} with category STUCK, throttled so a stuck module doesn't spam.
 */
public final class StuckDetector {
    private final String module;
    private Vec3 lastPos;
    private int stillTicks;
    private String action = "idle";
    private long lastLogMs = 0;
    private static final long LOG_THROTTLE_MS = 4000;

    /** Thresholds: ticks without progress before flagging, and movement epsilon (blocks). */
    private final int maxTicks;
    private final double threshold;

    public StuckDetector(String module) {
        this(module, Tuning.STUCK_TICKS_DEFAULT, Tuning.STUCK_EPSILON);
    }

    public StuckDetector(String module, int maxTicks, double threshold) {
        this.module = module;
        this.maxTicks = maxTicks;
        this.threshold = threshold;
    }

    /** The module reports what it's currently doing (state machine state / action / target). */
    public void setAction(String action) {
        this.action = action == null ? "?" : action;
    }

    /** Call every tick from the module. Logs a STUCK event if the module hasn't moved. */
    public void tick(Minecraft mc) {
        if (mc.player == null) { reset(); return; }
        Vec3 pos = mc.player.position();
        stillTicks = com.autism.seedcracker.util.pure.StuckLogic.update(
            stillTicks, lastPos != null, lastPos != null ? pos.distanceTo(lastPos) : 0.0, threshold);
        lastPos = pos;

        if (com.autism.seedcracker.util.pure.StuckLogic.isStuck(stillTicks, maxTicks)) {
            long now = System.currentTimeMillis();
            if (now - lastLogMs >= LOG_THROTTLE_MS) {
                lastLogMs = now;
                BlockPos bp = mc.player.blockPosition();
                FlagLog.flag("STUCK", module,
                    "no movement for " + stillTicks + "t at " + bp.getX() + "," + bp.getY() + "," + bp.getZ()
                    + " action=" + action);
            }
        }
    }

    /** Reset tracking (call on enable/disable or when the module intentionally pauses). */
    public void reset() {
        lastPos = null;
        stillTicks = 0;
    }
}
