package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder.
 *
 * Two modes:
 *  - XENON: the fast base-detection heuristic. Only active while you're above the activation Y
 *    (slider). It flags any chunk that has a block below Y15 that is NOT deepslate or bedrock -
 *    i.e. something a player placed there, since natural deep terrain is just deepslate + bedrock.
 *    Very cheap: one top-down pass per chunk, cached on a rescan interval.
 *  - TYPES: the original per-block-type detector (kelp, vines, amethyst, bamboo, full bee nests,
 *    rotated deepslate), flagging chunks whose count reaches the sensitivity.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class SusChunkFinderModule extends Module {

    public enum Mode { XENON, TYPES }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.XENON, Mode.values())
        .description("XENON = fast below-Y15 base detection (above activation Y). TYPES = per-block-type detector.")
        .group("General"));
    private final IntSetting activateAboveY = add(new IntSetting(
            "activate-above-y", "Activate above Y", 16, -60, 100, 1)
        .description("XENON mode only scans while you're above this Y (the below-Y15 base sweet spot).")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 16, 1)
        .description("Chunk bubble around the player scanned.")
        .group("General"));
    private final IntSetting rescanMs = add(new IntSetting(
            "rescan-ms", "Rescan (ms)", 1000, 250, 10000, 250)
        .description("How often each chunk is re-scanned (XENON mode).")
        .group("General"));
    private final IntSetting sensitivity = add(new IntSetting(
            "sensitivity", "Sensitivity (TYPES)", 3, 1, 20, 1)
        .description("Suspicious blocks needed in a chunk to flag it (TYPES mode).")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0x50FF5050)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a suspicious chunk is found.")
        .group("General"));

    // TYPES-mode per-type toggles.
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true).group("Types"));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true).group("Types"));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true).group("Types"));
    private final BoolSetting amethyst = add(new BoolSetting("amethyst", "Amethyst", true).group("Types"));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true).group("Types"));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true).group("Types"));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true).group("Types"));

    private final Set<ChunkPos> flagged = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScan = new ConcurrentHashMap<>();

    public SusChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder", category,
            "Flags suspicious chunks (XENON below-Y15 base detection, or per-block-type).");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        lastScan.clear();
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        lastScan.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-sus-chunk-finder");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (mode.get() == Mode.XENON) {
            tickXenon(mc);
        } else {
            tickTypes(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-sus-chunk-finder", flagged, color.get(), tracer.get());
    }

    // ---- XENON mode: fast below-Y15 base detection, gated on being above the activation Y ----

    private void tickXenon(Minecraft mc) {
        // Only scan while above the activation Y (so we can see below-Y15 player placements).
        if (mc.player.getY() <= activateAboveY.get()) {
            flagged.clear();
            lastScan.clear();
            return;
        }

        long now = System.currentTimeMillis();
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        int minY = mc.level.getMinY();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get()) continue;
                lastScan.put(pos, now);

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                boolean sus = isSusBelowY15(chunk, minY);
                if (sus) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
            }
        }

        // Prune out-of-range.
        int pr = radius + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    /** True if the chunk has any block below Y15 that isn't deepslate or bedrock (player-placed). */
    private boolean isSusBelowY15(LevelChunk chunk, int minY) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 15; y >= minY; y--) {
                    m.set(startX + x, y, startZ + z);
                    Block b = chunk.getBlockState(m).getBlock();
                    if (b != Blocks.DEEPSLATE && b != Blocks.BEDROCK
                        && b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- TYPES mode: per-block-type counter ----

    private void tickTypes(Minecraft mc) {
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        int threshold = sensitivity.get();
        java.util.List<LevelChunk> chunks = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, radius);

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            int count = com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, this::isSuspiciousType, threshold);
            if (count >= threshold) {
                if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
            } else {
                flagged.remove(pos);
            }
        }
        int pr = radius + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    private boolean isSuspiciousType(net.minecraft.world.level.block.state.BlockState state) {
        if (state.isAir()) return false;
        if (kelp.get() && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))) return true;
        if (caveVines.get() && (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT))) return true;
        if (vines.get() && state.is(Blocks.VINE)) return true;
        if (amethyst.get() && state.is(Blocks.AMETHYST_CLUSTER)) return true;
        if (bamboo.get() && (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING))) return true;
        if (beeNest.get() && (state.is(Blocks.BEE_NEST) || state.is(Blocks.BEEHIVE))) return true;
        if (rotatedDeepslate.get() && state.is(Blocks.DEEPSLATE)
            && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
            && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.Y) return true;
        return false;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Sus chunk at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§d[SusChunkFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }
}
