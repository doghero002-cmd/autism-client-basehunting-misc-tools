package com.autism.seedcracker.finder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.compat.ClientNotify;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Combined base alert: instead of five finders each pinging chat for the same base, this fires
 * ONE announcement when a fused cluster first crosses the confidence threshold. Per-finder
 * notifications stay available via each module's own notify toggle; this is the "multiple
 * signals agree - go look" ping.
 */
public final class BaseAlerts {
    private BaseAlerts() {}

    private static final int ANNOUNCE_CONFIDENCE = 80;
    private static final long MIN_GAP_MS = 10_000;
    /** Cell size for the once-per-cluster dedupe (coarser than the fusion radius on purpose). */
    private static final int CELL_BLOCKS = 96;

    private static final Map<Long, Boolean> announcedCells = new ConcurrentHashMap<>();
    private static volatile long lastAnnounceMs = 0;
    private static volatile long lastCheckMs = 0;

    /** Called by BaseTracker on every report; cheap rate-gate then fused re-check. */
    static void onReport(int blockX, int blockZ) {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < 1000) return; // fused scan at most 1/s
        lastCheckMs = now;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        double px = mc.player.getX(), pz = mc.player.getZ();

        for (BaseTracker.Fused f : BaseTracker.fusedNearest(px, pz, 8)) {
            if (f.confidence() < ANNOUNCE_CONFIDENCE) continue;
            if (!f.sources().contains("+")) continue; // single-finder clusters stay per-module
            long cell = (((long) Math.floorDiv(f.blockX(), CELL_BLOCKS)) << 32)
                ^ (Math.floorDiv(f.blockZ(), CELL_BLOCKS) & 0xffffffffL);
            if (announcedCells.putIfAbsent(cell, Boolean.TRUE) != null) continue;
            if (now - lastAnnounceMs < MIN_GAP_MS) return; // keep the cell marked, skip the ping
            lastAnnounceMs = now;
            int dist = (int) Math.sqrt(f.distSq());
            String msg = "BASE (" + f.confidence() + "%): " + f.sources()
                + " agree at X:" + f.blockX() + " Z:" + f.blockZ() + " (" + dist + "m)";
            mc.execute(() -> {
                AutismClientMessaging.sendPrefixed("§6§l[Base] §f" + msg);
                ClientNotify.warning(msg);
            });
            return; // one ping per pass
        }
    }

    /** Clear per-world state (server change). */
    public static void reset() {
        announcedCells.clear();
        lastAnnounceMs = 0;
    }
}
