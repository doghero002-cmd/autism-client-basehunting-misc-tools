package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tunnel Base Finder.
 *
 * Digs a long tunnel in the direction you're facing (Baritone does the pathing/digging) while
 * scanning the chunks you pass through for player-base signs - storage blocks (chests, barrels,
 * shulkers, hoppers) and monster spawners. When a chunk's storage count reaches the threshold,
 * or a spawner is found, it alerts you (toast + chat + sound) with the coordinates.
 *
 * This is a faithful-in-spirit port of the Krypton "TunnelBaseFinder" module to the AUTISM API
 * (Mojang 26.2). The original also auto-bought totems / repaired pickaxes by clicking DonutSMP
 * shop GUIs via hardcoded slot indexes and counted spawners via a mixin accessor - those depend
 * on the live server layout / a mixin we can't add from an addon, so this port focuses on the
 * tunnel-dig + base-detection core and drives digging through Baritone.
 *
 * WARNING: automated digging may flag anti-cheats.
 */
public final class TunnelBaseFinderModule extends Module {

    private final IntSetting segmentLength = add(new IntSetting(
            "segment-length", "Segment length (blocks)", 48, 8, 256, 8)
        .description("How far ahead each dig segment goes before extending.")
        .group("Tunnel"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 2, 1, 8, 1)
        .description("Chunk bubble scanned for bases while tunneling.")
        .group("Detect"));
    private final IntSetting storageThreshold = add(new IntSetting(
            "storage-threshold", "Storage threshold", 5, 1, 100, 1)
        .description("Storage blocks in a chunk needed to flag it as a base.")
        .group("Detect"));
    private final BoolSetting detectSpawners = add(new BoolSetting(
            "spawners", "Detect spawners", true)
        .description("Alert when a monster spawner is found while tunneling.")
        .group("Detect"));
    private final BoolSetting notify = add(new BoolSetting(
            "notify", "Notifications", true)
        .description("Toast + chat + sound when a base is found.")
        .group("Detect"));
    private final BoolSetting pauseOnFind = add(new BoolSetting(
            "pause-on-find", "Pause on find", false)
        .description("Stop tunneling when a base is found (else keep digging).")
        .group("Detect"));

    private static final Set<net.minecraft.world.level.block.Block> STORAGE = buildStorage();

    private boolean digging = false;
    private int dirX = 0;
    private int dirZ = 0;
    private double nextTargetX = 0;
    private double nextTargetZ = 0;
    private double tunnelY = 0;
    private final Set<ChunkPos> notified = new HashSet<>();

    public TunnelBaseFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-finder", "Tunnel Base Finder", category,
            "Digs a tunnel (Baritone) and alerts on storage/spawner bases it passes. WARNING: automated digging may flag anti-cheats.");
    }

    private static Set<net.minecraft.world.level.block.Block> buildStorage() {
        Set<net.minecraft.world.level.block.Block> s = new HashSet<>();
        s.add(Blocks.CHEST);
        s.add(Blocks.BARREL);
        s.add(Blocks.SHULKER_BOX);
        s.addAll(Blocks.DYED_SHULKER_BOX.asList());
        s.add(Blocks.HOPPER);
        s.add(Blocks.ENDER_CHEST);
        s.add(Blocks.TRAPPED_CHEST);
        return s;
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cTunnel Base Finder: you must be in a world.");
            setEnabledSilently(false);
            return;
        }
        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("§cTunnel Base Finder: Baritone not available.");
            setEnabledSilently(false);
            return;
        }
        com.autism.seedcracker.util.LegitBaritone.apply(mc);
        notified.clear();
        digging = false;

        // Lock the tunnel direction to the closest cardinal of where you're facing.
        float yaw = mc.player.getYRot();
        int cardinal = Math.round(yaw / 90.0f) & 3; // 0=S,1=W,2=N,3=E
        dirX = cardinal == 1 ? -1 : (cardinal == 3 ? 1 : 0);
        dirZ = cardinal == 0 ? 1 : (cardinal == 2 ? -1 : 0);
        tunnelY = mc.player.getY();
        nextTargetX = mc.player.getX();
        nextTargetZ = mc.player.getZ();

        String dirName = cardinal == 0 ? "South (+Z)" : cardinal == 1 ? "West (-X)"
            : cardinal == 2 ? "North (-Z)" : "East (+X)";
        AutismClientMessaging.sendPrefixed("§aTunnel Base Finder: tunneling " + dirName + ".");
    }

    @Override
    public void onDisable() {
        if (AutismCompatManager.isBaritoneAvailable()) {
            AutismCompatManager.stopBaritone(Minecraft.getInstance());
        }
        digging = false;
        notified.clear();
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        scanAround(mc);
        tickTunnel(mc);
    }

    /** Drive the Baritone dig: keep a segment goal ahead of the player in the locked direction. */
    private void tickTunnel(Minecraft mc) {
        if (!AutismCompatManager.isBaritoneAvailable()) return;
        if (AutismCompatManager.isBaritoneBusy()) {
            digging = true;
            return;
        }
        // Baritone finished (or hasn't started) - advance the goal one segment forward and go again.
        nextTargetX += dirX * segmentLength.get();
        nextTargetZ += dirZ * segmentLength.get();
        digging = AutismCompatManager.startBaritoneGoTo(mc,
            (int) Math.floor(nextTargetX), (int) Math.floor(tunnelY), (int) Math.floor(nextTargetZ));
        if (!digging) {
            AutismClientMessaging.sendPrefixed("§eTunnel Base Finder: couldn't start dig; retrying.");
        }
    }

    /** Scan the chunk bubble around the player for storage blocks / spawners. */
    private void scanAround(Minecraft mc) {
        java.util.List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        int threshold = storageThreshold.get();
        Predicate<BlockState> isStorage = st -> STORAGE.contains(st.getBlock());

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            if (notified.contains(pos)) continue;

            int storage = ChunkScanHelper.countBlocksInChunk(chunk, isStorage, threshold);
            boolean foundSpawner = detectSpawners.get() && hasSpawner(chunk);

            if (storage >= threshold || foundSpawner) {
                notified.add(pos);
                onBaseFound(mc, pos, storage, foundSpawner);
                if (pauseOnFind.get()) {
                    setEnabled(false);
                    return;
                }
            }
        }
    }

    private static boolean hasSpawner(LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof SpawnerBlockEntity) return true;
        }
        return false;
    }

    private void onBaseFound(Minecraft mc, ChunkPos pos, int storage, boolean spawner) {
        if (!notify.get()) return;
        int bx = pos.getMinBlockX();
        int bz = pos.getMinBlockZ();
        String what = spawner ? "§5spawner" : ("§6" + storage + " storage");
        String msg = "Base sign (" + what + "§f) at X:" + bx + " Z:" + bz;
        AutismNotifications.warning("Tunnel base found: " + what + " at " + bx + " " + bz);
        AutismClientMessaging.sendPrefixed("§d[TunnelBaseFinder] §f" + msg);

        // Log to the shared base log so it shows in the Base Log Browser.
        logToBaseFile(mc, bx, bz, spawner);
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private void logToBaseFile(Minecraft mc, int x, int z, boolean spawner) {
        try {
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
            String block = spawner ? "minecraft:spawner" : "minecraft:chest";
            int y = mc.player != null ? (int) mc.player.getY() : 0;
            String line = String.format(Locale.ROOT, "%d %d %d  %s  %s  %s%n",
                x, y, z, dim, block, new java.sql.Timestamp(System.currentTimeMillis()));
            java.nio.file.Files.writeString(f, line, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    @Override
    public String info() {
        return digging ? "digging" : "idle";
    }
}
