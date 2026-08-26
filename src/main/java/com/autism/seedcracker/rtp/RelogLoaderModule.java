package com.autism.seedcracker.rtp;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.item.Items;

/**
 * Relog Loader.
 *
 * Forces the server to resend chunks (the "relog loading" trick): digs down to a configurable
 * Y below the bedrock floor, disconnects and rejoins so chunks are resent, then boosts up and
 * flies around so ESP/base-finder modules can read the loaded region. This sees the bedrock /
 * deepslate chunk signatures that normal chunk loading hides, though not everywhere.
 *
 * Requires an elytra or a riptide trident for the fly phase, and Baritone for the dig phase.
 * One-shot: enable it to run a single load cycle; it disables itself when finished.
 */
public final class RelogLoaderModule extends Module {

    private enum Phase { IDLE, DIG, DISCONNECT_WAIT, RECONNECT_WAIT, FLY, DONE }

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private ServerData server;
    private final Set<Long> logged = new HashSet<>();
    private Path logFile;
    private int loggedThisRun = 0;
    private boolean elytraStarted = false;

    private final IntSetting digY = add(new IntSetting("dig-y", "Dig down to Y", -1, -60, 320, 1)
        .description("Y level to dig down to before relogging.")
        .group("Relog"));
    private final IntSetting disconnectWait = add(new IntSetting("disconnect-wait", "Disconnect wait (s)", 2, 0, 60, 1)
        .description("Seconds to stay disconnected before rejoining.")
        .group("Relog"));
    private final IntSetting reconnectWait = add(new IntSetting("reconnect-wait", "Rejoin wait (s)", 5, 1, 120, 1)
        .description("Seconds to wait after rejoining for chunks to load.")
        .group("Relog"));
    private final IntSetting flyDuration = add(new IntSetting("fly-duration", "Fly duration (s)", 20, 1, 600, 1)
        .description("How long to fly around loading chunks after rejoining.")
        .group("Relog"));
    private final IntSetting flyRadius = add(new IntSetting("fly-radius", "Fly radius", 96, 16, 512, 16)
        .description("How far out the elytra flies to load chunks.")
        .group("Relog"));
    private final BoolSetting requireTool = add(new BoolSetting("require-tool", "Require elytra/trident", true)
        .description("Abort the fly phase if you have no elytra or riptide trident.")
        .group("Relog"));
    private final BoolSetting logBases = add(new BoolSetting("log-bases", "Log detected bases", true)
        .description("Log stash/base blocks ESP detects while flying to bases.txt.")
        .group("Logging"));
    private final StringListSetting targetBlocks = add(new StringListSetting("target-blocks", "ESP blocks",
            "minecraft:chest|minecraft:barrel|minecraft:shulker_box|minecraft:hopper|minecraft:trapped_chest|minecraft:ender_chest")
        .description("Block ids (| separated) logged as bases while flying.")
        .group("Logging"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius", 64, 8, 256, 8)
        .description("Block radius scanned for base blocks while flying.")
        .group("Logging"));

    public RelogLoaderModule() {
        super(SeedcrackerAddon.ID + ":relog-loader", "Relog Loader",
            "Digs down, relogs to resend chunks, then flies (elytra) so ESP can read the region. One-shot. WARNING: automated movement may flag anti-cheats.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        server = mc.getCurrentServer();
        if (server == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cRelog Loader: you must be on a server.");
            setEnabledSilently(false);
            return;
        }
        if (requireTool.get() && !hasFlightTool(mc)) {
            AutismClientMessaging.sendPrefixed("§eRelog Loader: no elytra or riptide trident - the fly phase will be skipped.");
        }
        logFile = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
        logged.clear();
        loggedThisRun = 0;
        elytraStarted = false;
        phase = Phase.DIG;
        phaseTicks = 0;
        ACTIVE = true;
        AutismClientMessaging.sendPrefixed("§c§l[Warning] §cRelog Loader uses automated dig/fly movement that anti-cheats may flag. Use at your own risk.");
        autismclient.util.AutismNotifications.warning("Relog Loader: may flag anti-cheat");
        AutismClientMessaging.sendPrefixed("§aRelog Loader: digging to Y=" + digY.get() + "...");
    }

    /** True while the module is enabled (drives the on-screen warning HUD). */
    public static volatile boolean ACTIVE = false;

    @Override
    public void onDisable() {
        stopBaritone();
        phase = Phase.IDLE;
        server = null;
        ACTIVE = false;
    }

    private void stopBaritone() {
        try {
            if (AutismCompatManager.isBaritoneAvailable()) AutismCompatManager.stopBaritone(Minecraft.getInstance());
        } catch (Throwable ignored) {}
    }

    private static boolean hasFlightTool(Minecraft mc) {
        if (mc.player == null) return false;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.is(Items.ELYTRA) || stack.is(Items.TRIDENT)) return true;
        }
        return false;
    }

    private static final String BARITONE_API_CLASS = "baritone.api.BaritoneAPI";
    private static final String GOAL_XZ_CLASS = "baritone.api.pathing.goals.GoalXZ";
    private static final String GOAL_CLASS = "baritone.api.pathing.goals.Goal";

    private static Object primaryBaritone() throws Exception {
        Class<?> api = Class.forName(BARITONE_API_CLASS);
        Object provider = api.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    /** True if Baritone's elytra process is present and its native lib loaded (elytra usable). */
    private static boolean elytraAvailable() {
        try {
            Object elytra = primaryBaritone().getClass().getMethod("getElytraProcess").invoke(primaryBaritone());
            Object loaded = elytra.getClass().getMethod("isLoaded").invoke(elytra);
            return loaded instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Starts elytra flight toward (x, z) using Baritone's ElytraProcess + GoalXZ. */
    private static boolean elytraFlyTo(int x, int z) {
        try {
            Object baritone = primaryBaritone();
            Object elytra = baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
            Class<?> goalClass = Class.forName(GOAL_CLASS);
            Object goal = Class.forName(GOAL_XZ_CLASS).getConstructor(int.class, int.class).newInstance(x, z);
            elytra.getClass().getMethod("pathTo", goalClass).invoke(elytra, goal);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the elytra process currently has a destination. */
    private static boolean elytraActive() {
        try {
            Object elytra = primaryBaritone().getClass().getMethod("getElytraProcess").invoke(primaryBaritone());
            Object active = elytra.getClass().getMethod("isActive").invoke(elytra);
            return active instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private List<String> targetBlockIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : list("target-blocks")) {
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Scans the freshly-loaded chunks around the player for base blocks and logs new ones. */
    private void scanAndLogBases(Minecraft mc) {
        if (!logBases.get() || mc.level == null || mc.player == null || logFile == null) return;
        List<String> targets = targetBlockIds();
        if (targets.isEmpty()) return;
        int r = scanRadius.get();
        int px = (int) mc.player.getX();
        int pz = (int) mc.player.getZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir()) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    if (!targets.contains(id)) continue;
                    long key = pos.asLong();
                    if (logged.add(key)) {
                        writeBase(mc, pos.immutable(), id);
                    }
                }
            }
        }
    }

    private void writeBase(Minecraft mc, BlockPos pos, String blockId) {
        String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
        String line = String.format(Locale.ROOT, "%d %d %d  %s  %s  %s%n",
            pos.getX(), pos.getY(), pos.getZ(), dim, blockId,
            new java.sql.Timestamp(System.currentTimeMillis()));
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            loggedThisRun++;
        } catch (IOException e) {
            AutismClientMessaging.sendPrefixed("§cRelog Loader: failed to write bases.txt: " + e.getMessage());
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();

        switch (phase) {
            case IDLE, DONE -> {
            }
            case DIG -> {
                if (mc.player == null) return;
                if ((int) mc.player.getY() <= digY.get()) {
                    stopBaritone();
                    AutismClientMessaging.sendPrefixed("§7Relog Loader: at depth, relogging...");
                    if (RelogHelper.disconnect()) {
                        phase = Phase.DISCONNECT_WAIT;
                        phaseTicks = disconnectWait.get() * 20;
                    } else {
                        setEnabled(false);
                    }
                    return;
                }
                if (!AutismCompatManager.isBaritoneBusy()) {
                    if (!AutismCompatManager.isBaritoneAvailable()) {
                        AutismClientMessaging.sendPrefixed("§cRelog Loader: Baritone required to dig. Teleport falling instead is unsafe; aborting.");
                        setEnabled(false);
                        return;
                    }
                    AutismCompatManager.startBaritoneGoTo(mc, (int) mc.player.getX(), digY.get(), (int) mc.player.getZ());
                }
            }
            case DISCONNECT_WAIT -> {
                if (phaseTicks > 0) { phaseTicks--; return; }
                RelogHelper.reconnect(server);
                phase = Phase.RECONNECT_WAIT;
                phaseTicks = reconnectWait.get() * 20;
            }
            case RECONNECT_WAIT -> {
                // Wait while rejoining + chunks resend. Only count down once back in a world.
                if (mc.player == null || mc.level == null) return;
                if (phaseTicks > 0) { phaseTicks--; return; }
                if (requireTool.get() && !hasFlightTool(mc)) {
                    AutismClientMessaging.sendPrefixed("§eRelog Loader: chunks loaded. No flight tool, skipping fly phase.");
                    phase = Phase.DONE;
                    setEnabled(false);
                    return;
                }
                phase = Phase.FLY;
                phaseTicks = flyDuration.get() * 20;
                AutismClientMessaging.sendPrefixed("§7Relog Loader: flying to load chunks for ESP...");
            }
            case FLY -> {
                if (mc.player == null) return;
                // Scan the freshly-loaded chunks for base blocks (what ESP shows) and log them.
                scanAndLogBases(mc);

                // Fly out with the elytra so new chunks stream in.
                int px = (int) mc.player.getX();
                int pz = (int) mc.player.getZ();
                int r = flyRadius.get();
                if (!elytraActive()) {
                    if (!elytraStarted && elytraAvailable()) {
                        elytraStarted = elytraFlyTo(px + r, pz + r);
                    }
                    if (!elytraStarted) {
                        // Elytra unavailable: fall back to Baritone walking out so we still load some chunks.
                        if (!AutismCompatManager.isBaritoneBusy()) {
                            int targetY = Math.min(mc.level.getMaxY() - 2, (int) mc.player.getY() + 40);
                            AutismCompatManager.startBaritoneGoTo(mc, px + 32, targetY, pz + 32);
                        }
                    }
                }
                if (phaseTicks > 0) { phaseTicks--; return; }
                phase = Phase.DONE;
                AutismClientMessaging.sendPrefixed("§aRelog Loader: done. Logged " + loggedThisRun + " base(s) to bases.txt.");
                setEnabled(false);
            }
        }
    }

    @Override
    public String info() {
        return switch (phase) {
            case IDLE -> "idle";
            case DIG -> "digging";
            case DISCONNECT_WAIT -> "leaving";
            case RECONNECT_WAIT -> "rejoining";
            case FLY -> "flying";
            case DONE -> "done";
        };
    }
}
