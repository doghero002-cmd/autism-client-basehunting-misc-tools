package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder.
 *
 * Counts "suspicious" blocks in each chunk - things that rarely generate naturally in a way that
 * looks like player activity or an odd/modified chunk: kelp, cave vines, regular vines, amethyst
 * clusters, bamboo, full bee nests and rotated (non-vertical) deepslate. Each type is individually
 * toggleable; when a chunk's total count reaches the configured sensitivity it is flagged.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 *
 * Clean-room port of the Zelith "SusChunkFinder" module against the AUTISM module API. The
 * original used per-block structural heuristics (e.g. "kelp with more kelp above"); here each type
 * is matched by a straightforward block-state predicate, keeping the module readable while
 * preserving the per-type toggles and sensitivity threshold.
 */
public final class SusChunkFinderModule extends Module {

    private static final int FULL_HONEY = 5;

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "simulation-distance", "Simulation distance (chunks)", 4, 2, 16, 1)
        .description("Chunk bubble around the player scanned for suspicious blocks.")
        .group("General"));
    private final IntSetting sensitivity = add(new IntSetting(
            "sensitivity", "Sensitivity", 3, 1, 20, 1)
        .description("Suspicious blocks needed in a chunk to flag it.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "alpha", "Marker colour", 0x50FF5050)
        .description("Colour (with alpha) of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a suspicious chunk is found.")
        .group("General"));

    // ---- per-type toggles ----
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true)
        .description("Count kelp / kelp plants.").group("Types"));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true)
        .description("Count cave vines / glow-berry vines.").group("Types"));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true)
        .description("Count regular vines.").group("Types"));
    private final BoolSetting amethyst = add(new BoolSetting("amethyst", "Amethyst", true)
        .description("Count amethyst clusters.").group("Types"));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true)
        .description("Count bamboo.").group("Types"));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true)
        .description("Count full bee nests/hives (honey level 5).").group("Types"));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true)
        .description("Count deepslate rotated off the vertical axis (player-placed).").group("Types"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public SusChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder", category,
            "Flags chunks dense with suspicious blocks (kelp, vines, amethyst, bamboo, ...) - likely player activity.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
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

        tickCounter++;
        if (tickCounter % 8 == 0) {
            scan(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-sus-chunk-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        Predicate<BlockState> suspicious = this::isSuspicious;
        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            int count = ChunkScanHelper.countBlocksInChunk(chunk, suspicious);
            if (count >= sensitivity.get()) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos, count);
                }
            } else {
                flagged.remove(pos);
            }
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** Combined predicate honouring each per-type toggle. */
    private boolean isSuspicious(BlockState state) {
        if (state.isAir()) return false;

        if (kelp.get() && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))) return true;
        if (caveVines.get() && (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT))) return true;
        if (vines.get() && state.is(Blocks.VINE)) return true;
        if (amethyst.get() && state.is(Blocks.AMETHYST_CLUSTER)) return true;
        if (bamboo.get() && (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING))) return true;
        if (beeNest.get() && isFullHive(state)) return true;
        if (rotatedDeepslate.get() && isRotatedDeepslate(state)) return true;
        return false;
    }

    private static boolean isFullHive(BlockState state) {
        if (!state.is(Blocks.BEE_NEST) && !state.is(Blocks.BEEHIVE)) return false;
        return state.hasProperty(BeehiveBlock.HONEY_LEVEL)
            && state.getValue(BeehiveBlock.HONEY_LEVEL) == FULL_HONEY;
    }

    /** Deepslate whose pillar axis is horizontal (player-placed), as opposed to natural vertical. */
    private static boolean isRotatedDeepslate(BlockState state) {
        if (!state.is(Blocks.DEEPSLATE)) return false;
        if (!state.hasProperty(RotatedPillarBlock.AXIS)) return false;
        return state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        if (!notify.get()) return;
        String msg = "Sus chunk (" + count + " blocks) at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§d[SusChunkFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
