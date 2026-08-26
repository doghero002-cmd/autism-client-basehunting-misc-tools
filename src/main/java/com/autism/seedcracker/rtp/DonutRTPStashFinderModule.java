package com.autism.seedcracker.rtp;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Donut RTP Stash Finder.
 *
 * Repeatedly RTPs around DonutSMP. When you land closer to 0,0 than the configured threshold,
 * it (optionally) runs a Baritone base-search that digs down and looks for stash blocks
 * (chests, hoppers, ...) for a while. In "Save & RTP" mode it logs any base it finds to
 * bases.txt and immediately RTPs away instead of searching.
 *
 * Requires Baritone (baritone-meteor or upstream) for the base-search part. The optional
 * relog chunk-loading is provided by the separate "Relog Loader" module.
 */
public final class DonutRTPStashFinderModule extends Module {

    public enum Mode { SEARCH, SAVE_AND_RTP }
    public enum ScanMode { DETECT_ONLY, BARITONE_MINE }

    private enum State { IDLE, RTP_WAIT, DIGGING, SEARCHING }

    private State state = State.IDLE;
    private int stateTicks = 0;
    private long searchEndMs = 0L;
    private Path logFile;

    // Stuck-RTP tracking: if our coords don't change after an RTP, relog and try a new region.
    private double rtpFromX = 0.0;
    private double rtpFromZ = 0.0;
    private long rtpDeadlineMs = 0L;
    private boolean stuckRelogPending = false;
    private long stuckRelogDeadlineMs = 0L;
    private net.minecraft.client.multiplayer.ServerData server;
    private static final double MOVED_EPSILON = 4.0;

    // ---- RTP ----
    private final StringSetting rtpCommand = add(new StringSetting("rtp-command", "RTP command", "/rtp")
        .description("Base RTP command. With region rotation on, the region argument is appended.")
        .group("RTP"));
    private final IntSetting rtpCooldown = add(new IntSetting("rtp-cooldown", "RTP cooldown (s)", 5, 1, 600, 1)
        .description("Seconds to wait after an RTP before checking position / re-RTPing.")
        .group("RTP"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Distance threshold", 50000, 0, 30000000, 1000)
        .description("Run the base search when closer than this to 0,0 (uses max of |x|,|z|).")
        .group("RTP"));
    private final BoolSetting rotateRegions = add(new BoolSetting("rotate-regions", "Rotate RTP regions", true)
        .description("Pick a random DonutSMP region each RTP, never repeating the one just used, so TP spots don't cluster.")
        .group("RTP"));
    private final BoolSetting allowEast = add(new BoolSetting("allow-east", "Allow /rtp east", false)
        .description("WARNING: east is often full and can break RTPing. Enable to include it in the rotation.")
        .group("RTP"));
    private final IntSetting stuckTimeout = add(new IntSetting("stuck-timeout", "Stuck RTP timeout (s)", 10, 1, 60, 1)
        .description("If your coords don't change this long after an RTP, relog and try another region.")
        .group("RTP"));

    // ---- Mode / behaviour ----
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.SEARCH, Mode.values())
        .description("SEARCH digs/searches for stash blocks. SAVE_AND_RTP logs a found base and RTPs away.")
        .group("Behaviour"));
    private final EnumSetting<ScanMode> scanMode = add(new EnumSetting<>("scan-mode", "Search method", ScanMode.DETECT_ONLY, ScanMode.values())
        .description("DETECT_ONLY scans loaded chunks. BARITONE_MINE digs down and mines toward the target blocks.")
        .group("Behaviour"));
    private final StringListSetting targetBlocks = add(new StringListSetting("target-blocks", "Target blocks",
            "minecraft:chest|minecraft:barrel|minecraft:shulker_box|minecraft:hopper|minecraft:trapped_chest|minecraft:ender_chest")
        .description("Block ids (| separated) that count as a stash/base.")
        .group("Behaviour"));
    private final IntSetting searchRadius = add(new IntSetting("search-radius", "Search radius", 48, 8, 256, 8)
        .description("Block radius scanned for stash blocks when detecting.")
        .group("Behaviour"));
    private final IntSetting searchDuration = add(new IntSetting("search-duration", "Search duration (s)", 900, 10, 7200, 10)
        .description("How long to search before RTPing again (default 15 min).")
        .group("Behaviour"));
    private final IntSetting digDepth = add(new IntSetting("dig-depth", "Dig depth (Y)", -1, -60, 320, 1)
        .description("Y level Baritone digs down to before searching (Baritone mode).")
        .group("Behaviour"));
    private final BoolSetting autoDisableOnFind = add(new BoolSetting("auto-disable-on-find", "Stop after find", false)
        .description("Disable the module once a base is logged (Save & RTP mode).")
        .group("Behaviour"));

    public DonutRTPStashFinderModule() {
        super(SeedcrackerAddon.ID + ":donut-rtp", "Donut RTP Stash Finder",
            "RTPs around DonutSMP and searches for stashes near 0,0. WARNING: automated movement may flag anti-cheats.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        server = mc.getCurrentServer();
        logFile = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
        state = State.RTP_WAIT;
        stateTicks = 0;
        stuckRelogPending = false;
        stuckRelogDeadlineMs = 0L;
        rtpDeadlineMs = 0L;
        ACTIVE = true;
        AutismClientMessaging.sendPrefixed("§c§l[Warning] §cDonut RTP Stash Finder uses automated RTP/Baritone movement that anti-cheats may flag. Use at your own risk.");
        autismclient.util.AutismNotifications.warning("RTP Stash Finder: may flag anti-cheat");
        AutismClientMessaging.sendPrefixed("§aDonut RTP Stash Finder enabled. Mode: " + mode.get());
        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("§eBaritone not detected - base-search (dig/mine) disabled; detection still works.");
        }
    }

    /** True while the module is enabled (drives the on-screen warning HUD). */
    public static volatile boolean ACTIVE = false;

    @Override
    public void onDisable() {
        stopBaritone();
        state = State.IDLE;
        ACTIVE = false;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    private static int distFromSpawn(Minecraft mc) {
        return Math.max(Math.abs((int) mc.player.getX()), Math.abs((int) mc.player.getZ()));
    }

    private void sendRtp(Minecraft mc) {
        if (mc.getConnection() == null) return;
        String base = rtpCommand.get().trim();
        if (base.isEmpty()) base = "/rtp";
        if (rotateRegions.get()) {
            base = base + " " + pickRegion();
        }
        String cmd = base;
        // Record where we are and start the stuck-detection timer.
        if (mc.player != null) {
            rtpFromX = mc.player.getX();
            rtpFromZ = mc.player.getZ();
        }
        rtpDeadlineMs = System.currentTimeMillis() + stuckTimeout.get() * 1000L;
        if (cmd.startsWith("/")) mc.getConnection().sendCommand(cmd.substring(1));
        else mc.getConnection().sendChat(cmd);
        state = State.RTP_WAIT;
        stateTicks = rtpCooldown.get() * 20;
    }

    /** If the RTP didn't move us within the timeout, relog and try another region. */
    private void checkStuckRtp(Minecraft mc) {
        if (mc.player == null || rtpDeadlineMs == 0L) return;
        double dx = mc.player.getX() - rtpFromX;
        double dz = mc.player.getZ() - rtpFromZ;
        boolean moved = (dx * dx + dz * dz) > (MOVED_EPSILON * MOVED_EPSILON);
        if (moved) {
            rtpDeadlineMs = 0L;
            return;
        }
        if (System.currentTimeMillis() < rtpDeadlineMs) return;
        // Stuck: coords unchanged past the timeout. Relog and pick a fresh region.
        rtpDeadlineMs = 0L;
        AutismClientMessaging.sendPrefixed("§eDonut RTP: teleport didn't move us; relogging and trying another region.");
        stopBaritone();
        stuckRelogPending = true;
        if (RelogHelper.disconnect()) {
            stuckRelogDeadlineMs = System.currentTimeMillis() + 3000L;
        } else {
            stuckRelogPending = false;
        }
    }

    // DonutSMP RTP regions. "east" is excluded by default (often full / can break RTP).
    private static final String[] REGIONS = { "west", "eu central", "eu west", "asia", "oceania" };
    private static final String EAST = "east";
    private static final java.util.Random RNG = new java.util.Random();
    private int lastRegionIndex = -1;
    private boolean lastWasEast = false;

    /** Picks a random region, never repeating the one used last time. */
    private String pickRegion() {
        boolean includeEast = allowEast.get();
        int pool = REGIONS.length + (includeEast ? 1 : 0);
        if (pool <= 1) return includeEast ? EAST : REGIONS[0];

        int idx;
        do {
            idx = RNG.nextInt(pool);
        } while (isSameAsLast(idx, includeEast));
        lastRegionIndex = idx;
        lastWasEast = includeEast && idx == REGIONS.length;
        return lastWasEast ? EAST : REGIONS[idx];
    }

    private boolean isSameAsLast(int idx, boolean includeEast) {
        boolean isEast = includeEast && idx == REGIONS.length;
        if (isEast != lastWasEast) return false;
        return !isEast && idx == lastRegionIndex;
    }

    private void stopBaritone() {
        try {
            if (AutismCompatManager.isBaritoneAvailable()) AutismCompatManager.stopBaritone(Minecraft.getInstance());
        } catch (Throwable ignored) {}
    }

    private List<String> targetBlockIds() {
        List<String> out = new ArrayList<>();
        for (String s : list("target-blocks")) {
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Scans loaded chunks around the player for any target block; returns its position or null. */
    private BlockPos scanForBase(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        List<String> targets = targetBlockIds();
        if (targets.isEmpty()) return null;
        int r = searchRadius.get();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int px = (int) mc.player.getX();
        int pz = (int) mc.player.getZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir()) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    if (targets.contains(id)) return pos.immutable();
                }
            }
        }
        return null;
    }

    private void logBase(Minecraft mc, BlockPos found) {
        String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
        String line = String.format(Locale.ROOT, "%d %d %d  %s  %s%n",
            found.getX(), found.getY(), found.getZ(), dim,
            new java.sql.Timestamp(System.currentTimeMillis()));
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            AutismClientMessaging.sendPrefixed("§aLogged base to bases.txt: " + found.getX() + " " + found.getY() + " " + found.getZ());
        } catch (IOException e) {
            AutismClientMessaging.sendPrefixed("§cFailed to write bases.txt: " + e.getMessage());
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();

        // Handle a pending stuck-RTP relog: reconnect once the wait elapses, then RTP to a new region.
        if (stuckRelogPending) {
            if (System.currentTimeMillis() < stuckRelogDeadlineMs) return;
            if (mc.player == null) {
                // Still at the menu/disconnected: reconnect now.
                RelogHelper.reconnect(server);
                return;
            }
            // Back in the world: re-RTP to a different region.
            stuckRelogPending = false;
            sendRtp(mc);
            return;
        }

        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        switch (state) {
            case IDLE -> {
                sendRtp(mc);
            }
            case RTP_WAIT -> {
                // Watch for a failed teleport and relog if our coords never change.
                checkStuckRtp(mc);
                if (stuckRelogPending || state != State.RTP_WAIT) return;
                if (stateTicks > 0) { stateTicks--; return; }
                int dist = distFromSpawn(mc);
                if (dist >= threshold.get()) {
                    sendRtp(mc);
                    return;
                }
                // Within threshold.
                if (mode.get() == Mode.SAVE_AND_RTP) {
                    BlockPos found = scanForBase(mc);
                    if (found != null) {
                        logBase(mc, found);
                        if (autoDisableOnFind.get()) { setEnabled(false); return; }
                    }
                    sendRtp(mc);
                    return;
                }
                // SEARCH mode.
                if (scanMode.get() == ScanMode.BARITONE_MINE && AutismCompatManager.isBaritoneAvailable()) {
                    state = State.DIGGING;
                    stateTicks = 0;
                    AutismCompatManager.startBaritoneGoTo(mc, (int) mc.player.getX(), digDepth.get(), (int) mc.player.getZ());
                    AutismClientMessaging.sendPrefixed("§7Digging down to Y=" + digDepth.get() + "...");
                } else {
                    state = State.SEARCHING;
                    stateTicks = 0;
                    searchEndMs = System.currentTimeMillis() + searchDuration.get() * 1000L;
                }
            }
            case DIGGING -> {
                // Wait until Baritone stops digging (reached depth / gave up), then start searching.
                if (!AutismCompatManager.isBaritoneBusy()) {
                    state = State.SEARCHING;
                    searchEndMs = System.currentTimeMillis() + searchDuration.get() * 1000L;
                    List<String> ids = targetBlockIds();
                    List<String> bare = new ArrayList<>();
                    for (String id : ids) bare.add(id.startsWith("minecraft:") ? id.substring(10) : id);
                    if (scanMode.get() == ScanMode.BARITONE_MINE && !bare.isEmpty()) {
                        AutismCompatManager.startBaritoneMine(mc, bare);
                        AutismClientMessaging.sendPrefixed("§7Searching for: " + String.join(", ", bare));
                    }
                }
                // Fallthrough: also check detection while digging.
                BlockPos found = scanForBase(mc);
                if (found != null) {
                    logBase(mc, found);
                    if (autoDisableOnFind.get()) { setEnabled(false); return; }
                }
            }
            case SEARCHING -> {
                BlockPos found = scanForBase(mc);
                if (found != null) {
                    logBase(mc, found);
                    if (autoDisableOnFind.get()) { setEnabled(false); return; }
                }
                if (System.currentTimeMillis() >= searchEndMs) {
                    stopBaritone();
                    AutismClientMessaging.sendPrefixed("§7Search time up, RTPing again.");
                    sendRtp(mc);
                }
            }
        }
    }

    @Override
    public String info() {
        return switch (state) {
            case IDLE -> "idle";
            case RTP_WAIT -> "rtp";
            case DIGGING -> "digging";
            case SEARCHING -> "searching";
        };
    }
}
