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

    // ---- RTP ----
    private final StringSetting rtpCommand = add(new StringSetting("rtp-command", "RTP command", "/rtp")
        .description("Command sent to random-teleport.")
        .group("RTP"));
    private final IntSetting rtpCooldown = add(new IntSetting("rtp-cooldown", "RTP cooldown (s)", 5, 1, 600, 1)
        .description("Seconds to wait after an RTP before checking position / re-RTPing.")
        .group("RTP"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Distance threshold", 50000, 0, 30000000, 1000)
        .description("Run the base search when closer than this to 0,0 (uses max of |x|,|z|).")
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
            "RTPs around DonutSMP and searches for stashes near 0,0.");
    }

    @Override
    public void onEnable() {
        logFile = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
        state = State.RTP_WAIT;
        stateTicks = 0;
        AutismClientMessaging.sendPrefixed("§aDonut RTP Stash Finder enabled. Mode: " + mode.get());
        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("§eBaritone not detected - base-search (dig/mine) disabled; detection still works.");
        }
    }

    @Override
    public void onDisable() {
        stopBaritone();
        state = State.IDLE;
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
        String cmd = rtpCommand.get().trim();
        if (cmd.isEmpty()) return;
        if (cmd.startsWith("/")) mc.getConnection().sendCommand(cmd.substring(1));
        else mc.getConnection().sendChat(cmd);
        state = State.RTP_WAIT;
        stateTicks = rtpCooldown.get() * 20;
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
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        switch (state) {
            case IDLE -> {
                sendRtp(mc);
            }
            case RTP_WAIT -> {
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
