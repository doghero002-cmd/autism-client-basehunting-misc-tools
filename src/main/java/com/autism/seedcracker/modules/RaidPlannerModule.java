package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.BaseTracker;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Raid Planner (nyx RaidPlannerModule port).
 *
 * Aggregates every base find from the shared {@link BaseTracker} into persistent-for-the-session
 * candidates, scores them (peak confidence + how many different finders agreed + sightings) and
 * ranks them GOLD / SILVER / BRONZE. {@code info()} shows the count; the chat report (1/min or
 * on demand via toggling) lists the top targets so you can pick the richest base to raid first.
 */
public final class RaidPlannerModule extends Module {

    private final IntSetting goldScore = add(new IntSetting("gold-score", "Gold score", 150, 50, 500, 10)
        .description("Total score needed for a GOLD (raid first) rating.").group("Scoring"));
    private final IntSetting silverScore = add(new IntSetting("silver-score", "Silver score", 80, 20, 400, 10)
        .description("Total score needed for SILVER.").group("Scoring"));
    private final IntSetting reportMinutes = add(new IntSetting("report-minutes", "Report every (min)", 5, 1, 60, 1)
        .description("How often the top-targets chat report prints.").group("General"));
    private final BoolSetting reportChat = add(new BoolSetting("report-chat", "Chat reports", true)
        .description("Print the ranked target list periodically.").group("General"));

    /** Aggregated candidate per dedupe cell. */
    private static final class Candidate {
        int blockX, blockZ;
        int peakConfidence;
        final java.util.Set<String> sources = ConcurrentHashMap.newKeySet();
        int sightings;
        long lastSeenMs;

        int score() {
            return peakConfidence + sources.size() * 25 + Math.min(sightings / 20, 30);
        }

        String rank(int gold, int silver) {
            int s = score();
            if (s >= gold) return "§6GOLD";
            if (s >= silver) return "§7SILVER";
            return "§cBRONZE";
        }
    }

    private final Map<Long, Candidate> candidates = new ConcurrentHashMap<>();
    private int pollTicks = 0;
    private long lastReportMs = 0;

    public RaidPlannerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":raid-planner", "Raid Planner", category,
            "Ranks every base find GOLD/SILVER/BRONZE so you raid the richest target first.");
    }

    @Override
    public void onEnable() {
        lastReportMs = 0; // report soon after enabling
    }

    @Override
    public void onGameLeft() {
        candidates.clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (++pollTicks < 20) return;
        pollTicks = 0;

        for (BaseTracker.Entry e : BaseTracker.nearest(mc.player.getX(), mc.player.getZ(), 64)) {
            long key = cellKey(e.blockX(), e.blockZ());
            Candidate c = candidates.computeIfAbsent(key, k -> new Candidate());
            c.blockX = e.blockX();
            c.blockZ = e.blockZ();
            c.peakConfidence = Math.max(c.peakConfidence, e.confidence());
            c.sources.add(e.source());
            c.sightings++;
            c.lastSeenMs = System.currentTimeMillis();
        }

        if (reportChat.get()
            && System.currentTimeMillis() - lastReportMs > reportMinutes.get() * 60_000L
            && !candidates.isEmpty()) {
            lastReportMs = System.currentTimeMillis();
            report();
        }
    }

    private void report() {
        List<Candidate> ranked = new ArrayList<>(candidates.values());
        ranked.sort((a, b) -> Integer.compare(b.score(), a.score()));
        AutismClientMessaging.sendPrefixed("§b[RaidPlanner] §fTop targets (" + ranked.size() + " candidates):");
        int shown = 0;
        for (Candidate c : ranked) {
            if (++shown > 5) break;
            AutismClientMessaging.sendPrefixed("  " + c.rank(goldScore.get(), silverScore.get())
                + " §fX:" + c.blockX + " Z:" + c.blockZ
                + " §7score=" + c.score() + " finders=" + String.join(",", c.sources));
        }
    }

    @Override
    public String info() {
        return candidates.size() + " targets";
    }

    private static long cellKey(int blockX, int blockZ) {
        long cx = Math.floorDiv(blockX, 128); // 8-chunk cells
        long cz = Math.floorDiv(blockZ, 128);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }
}
