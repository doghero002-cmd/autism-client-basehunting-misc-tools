package com.autism.seedcracker.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Obfuscation-proof replacements for small client utils that were renamed in later (obfuscated)
 * builds. Each is self-contained (pure Minecraft/Fabric) so nothing here depends on a renamed
 * client class.
 */
public final class ClientCompatUtil {
    private ClientCompatUtil() {}

    // ---- ServerTickTracker.getEstimatedTps ----
    // Rolling estimate from incoming world-time packets, fed by tickWorldTime(). Defaults to 20
    // (healthy) until enough samples arrive. No client dependency.
    private static long lastWorldTimePacketMs = -1;
    private static long lastGameTime = -1;
    private static double estimatedTps = 20.0;

    /** Call from a world-time (time update) packet handler if one is wired; otherwise the
     * estimate stays at the 20 TPS default, which is the safe assumption. */
    public static synchronized void tickWorldTime(long gameTime) {
        long now = System.currentTimeMillis();
        if (lastWorldTimePacketMs > 0 && lastGameTime >= 0 && gameTime > lastGameTime) {
            long wallDelta = now - lastWorldTimePacketMs;
            long gameDelta = gameTime - lastGameTime;
            if (wallDelta > 0 && gameDelta > 0) {
                // TPS = game ticks advanced per wall-clock second.
                double sample = gameDelta * 1000.0 / wallDelta;
                estimatedTps = estimatedTps * 0.8 + Math.min(20.0, sample) * 0.2; // smooth
            }
        }
        lastWorldTimePacketMs = now;
        lastGameTime = gameTime;
    }

    /** Estimated server TPS (20 = healthy; 20 when unknown). Was ServerTickTracker.getEstimatedTps. */
    public static double getEstimatedTps() {
        return estimatedTps;
    }

    // ---- TeamsModule.isFriendOrTeam ----
    /** True if the other player is on your scoreboard team (was TeamsModule.isFriendOrTeam).
     * Friends/teammates are excluded from enemy checks by the caller. */
    public static boolean isFriendOrTeam(Player other) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || other == null) return false;
        var own = mc.player.getTeam();
        var theirs = other.getTeam();
        return own != null && theirs != null && own.isAlliedTo(theirs);
    }

    // ---- PackHideState.isActive ----
    /** The pack-hide feature isn't present in the addon context; report inactive (was
     * PackHideState.isActive). Callers treat "active" as "suppress this behaviour", so false is
     * the safe, feature-preserving default. */
    public static boolean isPackHideActive() {
        return false;
    }

    // ---- ModuleRenderUtil.color ----
    /** Resolve an ESP colour (was ModuleRenderUtil.color). The client helper read a named colour
     * setting off a module from ANOTHER addon (e.g. the storage-esp module). That module isn't
     * present here, so there's no per-name setting to read - return the caller's default, which is
     * what these were configured to anyway. */
    public static int moduleColor(Object module, String optionName, int defaultColor) {
        return defaultColor;
    }
}
