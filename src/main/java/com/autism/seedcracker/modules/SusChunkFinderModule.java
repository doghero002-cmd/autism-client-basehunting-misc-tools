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
        .description("XENON = fast below-Y15 player-placement detection. TYPES = per-block-type detector.")
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
    private final BoolSetting skipStructures = add(new BoolSetting(
            "skip-structures", "Skip natural structures", true)
        .description("XENON: don't flag spawners/blocks inside dungeons or trial chambers (they aren't player bases).")
        .group("General"));

    // TYPES-mode per-type toggles.
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true).group("Types"));
    private final IntSetting kelpCount = add(new IntSetting("kelp-count", "Kelp min count", 3, 1, 200, 1)
        .description("Kelp blocks in a chunk needed to count toward the flag (dense kelp = a farm).").group("Types"));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true).group("Types"));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true).group("Types"));
    private final BoolSetting amethystShards = add(new BoolSetting("amethyst-shards", "Amethyst Shards", true)
        .description("Amethyst clusters/buds - harvested by players, so a strong base indicator.")
        .group("Types"));
    private final BoolSetting amethystBlocks = add(new BoolSetting("amethyst-blocks", "Amethyst Blocks", false)
        .description("Full amethyst/budding blocks (geode structure - not player-placed).")
        .group("Types"));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true).group("Types"));
    private final IntSetting bambooCount = add(new IntSetting("bamboo-count", "Bamboo min count", 3, 1, 200, 1)
        .description("Bamboo blocks in a chunk needed to count toward the flag (dense bamboo = a farm).").group("Types"));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true).group("Types"));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true).group("Types"));

    private final Set<ChunkPos> flagged = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScan = new ConcurrentHashMap<>();
    private long typesLastScanMs = 0;

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

    // ---- XENON mode: fast below-Y15 player-placement detection ----

    private void tickXenon(Minecraft mc) {
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
                if (sus && skipStructures.get() && isNaturalStructure(mc, pos)) sus = false;
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

    /**
     * True if the chunk has a player-placed block below Y15. Natural deep terrain is deepslate,
     * bedrock, air, ore, lava, water, tuff, gravel and the usual cave blocks - so we only flag
     * blocks that do NOT naturally generate down there (chests, hoppers, spawners, placed stone
     * variants, torches, etc.). This avoids flagging every cave/ore vein.
     */
    private boolean isSusBelowY15(LevelChunk chunk, int minY) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 15; y >= minY; y--) {
                    m.set(startX + x, y, startZ + z);
                    if (isPlayerPlaced(chunk.getBlockState(m).getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True for blocks that essentially only exist below Y15 because a player put them there. */
    private static boolean isPlayerPlaced(Block b) {
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
            || b == Blocks.HOPPER || b == Blocks.ENDER_CHEST || b == Blocks.SHULKER_BOX
            || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.SPAWNER || b == Blocks.BEE_NEST || b == Blocks.BEEHIVE
            || b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE || b == Blocks.ANVIL
            || b == Blocks.BREWING_STAND || b == Blocks.CAULDRON || b == Blocks.WATER_CAULDRON || b == Blocks.LAVA_CAULDRON
            || b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL
            || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL_FRAME || b == Blocks.OBSIDIAN;
    }

    /** True if the chunk is part of a natural structure (dungeon / trial chamber), not a base. */
    private boolean isNaturalStructure(Minecraft mc, ChunkPos pos) {
        int structureBlocks = 0;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = 15; y >= mc.level.getMinY(); y -= 4) {
                    Block b = mc.level.getBlockState(new BlockPos(baseX + x, y, baseZ + z)).getBlock();
                    if (b == Blocks.MOSSY_COBBLESTONE
                        || b == Blocks.TUFF || b == Blocks.POLISHED_TUFF || b == Blocks.TUFF_BRICKS
                        || b == Blocks.CHISELED_TUFF || b == Blocks.CHISELED_TUFF_BRICKS
                        || b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) {
                        structureBlocks++;
                    }
                }
            }
        }
        return structureBlocks >= 3;
    }

    // ---- TYPES mode: per-block-type counter (throttled to avoid the 1 FPS full-volume scan) ----

    private void tickTypes(Minecraft mc) {
        // Use the shared rescan delay (ms) between full scans, with the early-exit count cap so
        // we never do a full-volume scan every frame.
        long now = System.currentTimeMillis();
        if (now - typesLastScanMs < rescanMs.get()) return;
        typesLastScanMs = now;

        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        int threshold = sensitivity.get();
        java.util.List<LevelChunk> chunks = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, radius);

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            // Kelp and bamboo have their own count thresholds (dense patches = a farm), the rest
            // use the shared sensitivity.
            boolean sus = false;
            if (kelp.get() && com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(
                    chunk, s -> s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT), kelpCount.get()) >= kelpCount.get()) {
                sus = true;
            }
            if (!sus && bamboo.get() && com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(
                    chunk, s -> s.is(Blocks.BAMBOO) || s.is(Blocks.BAMBOO_SAPLING), bambooCount.get()) >= bambooCount.get()) {
                sus = true;
            }
            if (!sus) {
                int count = com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, this::isSuspiciousType, threshold);
                if (count >= threshold) sus = true;
            }
            if (sus) {
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
        // (kelp and bamboo use their own count sliders in tickTypes, not this shared predicate)
        if (caveVines.get() && (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT))) return true;
        if (vines.get() && state.is(Blocks.VINE)) return true;
        // Amethyst shards (the bits players harvest - a base indicator), separate from the
        // full amethyst geode blocks below.
        if (amethystShards.get() && (state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.LARGE_AMETHYST_BUD)
            || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.SMALL_AMETHYST_BUD))) return true;
        if (amethystBlocks.get() && (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST))) return true;
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
