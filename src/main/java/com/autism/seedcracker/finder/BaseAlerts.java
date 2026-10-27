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

        BaseTracker.Fused candidate = claimNext(px, pz, now);
        if (candidate == null) return;
        int dist = (int) Math.sqrt(candidate.distSq());
        String msg = "Base candidate (score " + candidate.confidence() + "/100): " + candidate.sources()
            + " agree at X:" + candidate.blockX() + " Z:" + candidate.blockZ() + " (" + dist + "m)";
        mc.execute(() -> {
            AutismClientMessaging.sendPrefixed("§6§l[Base] §f" + msg);
            ClientNotify.warning(msg);
        });
    }

    static BaseTracker.Fused claimNext(double playerX, double playerZ, long now) {
        for (BaseTracker.Fused candidate : BaseTracker.fusedNearestAt(playerX, playerZ, Integer.MAX_VALUE, now)) {
            if (candidate.confidence() < ANNOUNCE_CONFIDENCE || !candidate.sources().contains("+")) continue;
            long cell = (((long) Math.floorDiv(candidate.blockX(), CELL_BLOCKS)) << 32)
                ^ (Math.floorDiv(candidate.blockZ(), CELL_BLOCKS) & 0xffffffffL);
            if (claim(cell, now)) return candidate;
        }
        return null;
    }

    static boolean claim(long cell, long now) {
        if (lastAnnounceMs != 0 && now >= lastAnnounceMs && now - lastAnnounceMs < MIN_GAP_MS) return false;
        if (announcedCells.putIfAbsent(cell, Boolean.TRUE) != null) return false;
        lastAnnounceMs = now;
        return true;
    }

    /** Clear per-world state (server change). */
    public static void reset() {
        announcedCells.clear();
        lastAnnounceMs = 0;
        lastCheckMs = 0;
    }
}
