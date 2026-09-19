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
    private final EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("XENON: HIGH/MEDIUM = 1 player-placed block flags, LOW = need 2. TYPES: scales the per-type min counts.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 16, 1)
        .description("Chunk bubble around the player scanned.")
        .group("General"));
    private final IntSetting rescanMs = add(new IntSetting(
            "rescan-ms", "Rescan (ms)", 1000, 250, 10000, 250)
        .description("How often each chunk is re-scanned (XENON mode).")
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
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("How many chunks to scan per tick (TYPES mode). Lower = less FPS impact (spread over more seconds); higher = faster full scan.")
        .group("Performance"));

    // TYPES-mode per-type toggles + per-type min-count sliders.
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true).group("Types"));
    private final IntSetting kelpCount = add(new IntSetting("kelp-count", "Kelp min count", 3, 1, 200, 1)
        .description("Kelp blocks in a chunk needed to flag (dense kelp = a farm).").group("Types"));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true).group("Types"));
    private final IntSetting caveVinesCount = add(new IntSetting("cave-vines-count", "Cave vines min count", 3, 1, 200, 1)
        .description("Cave-vine blocks in a chunk needed to flag.").group("Types"));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true).group("Types"));
    private final IntSetting vinesCount = add(new IntSetting("vines-count", "Vines min count", 3, 1, 200, 1)
        .description("Vine blocks in a chunk needed to flag.").group("Types"));
    private final BoolSetting amethystShards = add(new BoolSetting("amethyst-shards", "Amethyst Shards", true)
        .description("Amethyst clusters/buds - harvested by players, so a strong base indicator.")
        .group("Types"));
    private final IntSetting amethystShardsCount = add(new IntSetting("amethyst-shards-count", "Amethyst shards min count", 1, 1, 200, 1)
        .description("Amethyst clusters/buds in a chunk needed to flag.").group("Types"));
    private final BoolSetting amethystBlocks = add(new BoolSetting("amethyst-blocks", "Amethyst Blocks", false)
        .description("Full amethyst/budding blocks (geode structure - not player-placed).")
        .group("Types"));
    private final IntSetting amethystBlocksCount = add(new IntSetting("amethyst-blocks-count", "Amethyst blocks min count", 4, 1, 200, 1)
        .description("Full amethyst/budding blocks in a chunk needed to flag.").group("Types"));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true).group("Types"));
    private final IntSetting bambooCount = add(new IntSetting("bamboo-count", "Bamboo min count", 3, 1, 200, 1)
        .description("Bamboo blocks in a chunk needed to flag (dense bamboo = a farm).").group("Types"));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true).group("Types"));
    private final IntSetting beeNestCount = add(new IntSetting("bee-nest-count", "Bee nest min count", 1, 1, 200, 1)
        .description("Bee nests/hives in a chunk needed to flag.").group("Types"));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true).group("Types"));
    private final IntSetting rotatedDeepslateCount = add(new IntSetting("rotated-deepslate-count", "Rotated deepslate min count", 3, 1, 200, 1)
        .description("Rotated (non-Y-axis) deepslate blocks in a chunk needed to flag.").group("Types"));
    private final BoolSetting skullCandle = add(new BoolSetting("skull-candle", "Skulls / Candles", true)
        .description("Mob skulls + candles (strong player-build / decoration markers, nyx signal).").group("Types"));
    private final IntSetting skullCandleCount = add(new IntSetting("skull-candle-count", "Skull/candle min count", 1, 1, 200, 1)
        .description("Skull or candle blocks in a chunk needed to flag.").group("Types"));
    private final BoolSetting cocoa = add(new BoolSetting("cocoa", "Cocoa", false)
        .description("Cocoa pods (a farm indicator, nyx signal).").group("Types"));
    private final IntSetting cocoaCount = add(new IntSetting("cocoa-count", "Cocoa min count", 3, 1, 200, 1)
        .description("Cocoa pods in a chunk needed to flag.").group("Types"));
    private final BoolSetting villagerHall = add(new BoolSetting("villager-hall", "Villager hall (entities)", true)
        .description("Villager/zombie-villager/allay/vindicator/warden entities in a chunk - an active base or villager hall (nyx signal).")
        .group("Types"));
    private final BoolSetting persistFlags = add(new BoolSetting("persist-flags", "Persist flags to disk", true)
        .description("Save flagged chunks to disk keyed by dimension and reload them on join (survives relog, nyx behaviour).")
        .group("General"));

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
        if (persistFlags.get()) loadFlags();
    }

    @Override
    public void onDisable() {
        if (persistFlags.get()) saveFlags();
        flagged.clear();
        notified.clear();
        lastScan.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-sus-chunk-finder");
    }

    // ---- disk persistence (nyx ChunkActivityScanner behaviour) ----
    private java.nio.file.Path flagsFile() {
        Minecraft mc = Minecraft.getInstance();
        String dim = mc.level != null ? mc.level.dimension().toString().replace(':', '_') : "unknown";
        return autismclient.AutismClientAddon.FOLDER.toPath()
            .resolve("sus-chunk-flags-" + dim + ".txt");
    }

    private void loadFlags() {
        try {
            java.nio.file.Path f = flagsFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] p = line.trim().split(",");
                if (p.length != 2) continue;
                flagged.add(new ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
            }
        } catch (Throwable ignored) {}
    }

    private void saveFlags() {
        try {
            java.nio.file.Path f = flagsFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (ChunkPos p : flagged) lines.add(p.x() + "," + p.z());
            java.nio.file.Files.write(f, lines);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
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

        // Budgeted: only chunksPerTick chunks per tick (a full 16x16x79-block scan per chunk is
        // ~20k block reads; the old loop did the whole radius in one tick whenever the rescan
        // timers expired together = FPS hitch).
        int budget = chunksPerTick.get();
        outer:
        for (int dx = -radius; dx <= radius && budget > 0; dx++) {
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
                if (--budget <= 0) break outer;
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
        // LOW sensitivity: one lone torch/chest can be a dungeon remnant - need 2+ placed blocks.
        int need = sensitivity.get() == com.autism.seedcracker.finder.FinderSensitivity.LOW ? 2 : 1;
        int found = 0;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Scan section-by-section, skipping all-air sections (hasOnlyAir is a cached palette
        // check) - cuts most of the 20k block reads in cavey chunks.
        for (int sy = minY; sy <= 15; sy += 16) {
            int sectionIdx = chunk.getSectionIndex(sy);
            if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) continue;
            if (chunk.getSection(sectionIdx).hasOnlyAir()) continue;
            int yLo = Math.max(sy, minY);
            int yHi = Math.min(sy + 15, 15);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yHi; y >= yLo; y--) {
                        m.set(startX + x, y, startZ + z);
                        if (isPlayerPlaced(chunk.getBlockState(m).getBlock()) && ++found >= need) {
                            return true;
                        }
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

    // ---- TYPES mode: incremental per-block-type scanner (spread across ticks to avoid lag) ----

    private java.util.List<LevelChunk> scanQueue = java.util.Collections.emptyList();
    private int scanIndex = 0;
    private long lastQueueRefreshMs = 0;

    private void tickTypes(Minecraft mc) {
        // Refresh the chunk queue periodically (or when exhausted), then scan only chunksPerTick
        // chunks this tick. A full pass therefore spreads over several seconds, so there's no
        // single-tick FPS spike.
        long now = System.currentTimeMillis();
        if (scanIndex >= scanQueue.size() && now - lastQueueRefreshMs >= rescanMs.get()) {
            scanQueue = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            scanIndex = 0;
            lastQueueRefreshMs = now;
        }

        int budget = chunksPerTick.get();
        ChunkPos center = mc.player.chunkPosition();
        while (budget-- > 0 && scanIndex < scanQueue.size()) {
            LevelChunk chunk = scanQueue.get(scanIndex++);
            scanChunkTypes(mc, chunk);
        }

        int pr = scanRadius.get() + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    /** Count-vs-threshold with the sensitivity scale applied (TYPES mode). */
    private boolean typeHit(LevelChunk chunk, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> pred, int baseCount) {
        int need = sensitivity.get().scale(baseCount);
        return com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, pred, need) >= need;
    }

    /** Run every enabled block-type check on one chunk and flag/unflag it. */
    private void scanChunkTypes(Minecraft mc, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        boolean sus = false;
        if (kelp.get() && typeHit(chunk, s -> s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT), kelpCount.get())) sus = true;
        if (!sus && bamboo.get() && typeHit(chunk, s -> s.is(Blocks.BAMBOO) || s.is(Blocks.BAMBOO_SAPLING), bambooCount.get())) sus = true;
        if (!sus && caveVines.get() && typeHit(chunk, s -> s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT), caveVinesCount.get())) sus = true;
        if (!sus && vines.get() && typeHit(chunk, s -> s.is(Blocks.VINE), vinesCount.get())) sus = true;
        if (!sus && amethystShards.get() && typeHit(chunk,
                s -> s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD)
                    || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.SMALL_AMETHYST_BUD),
                amethystShardsCount.get())) sus = true;
        if (!sus && amethystBlocks.get() && typeHit(chunk,
                s -> s.is(Blocks.AMETHYST_BLOCK) || s.is(Blocks.BUDDING_AMETHYST),
                amethystBlocksCount.get())) sus = true;
        if (!sus && beeNest.get() && typeHit(chunk, s -> s.is(Blocks.BEE_NEST) || s.is(Blocks.BEEHIVE), beeNestCount.get())) sus = true;
        if (!sus && rotatedDeepslate.get() && typeHit(chunk,
                s -> s.is(Blocks.DEEPSLATE)
                    && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                    && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.Y,
                rotatedDeepslateCount.get())) sus = true;
        if (!sus && skullCandle.get() && typeHit(chunk, SusChunkFinderModule::isSkullOrCandle, skullCandleCount.get())) sus = true;
        if (!sus && cocoa.get() && typeHit(chunk, s -> s.is(Blocks.COCOA), cocoaCount.get())) sus = true;
        if (!sus && villagerHall.get() && chunkHasVillagerHall(mc, pos)) sus = true;
        if (sus) {
            if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
        } else {
            flagged.remove(pos);
        }
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    /** Mob skulls + candles (nyx player-build / decoration signal). Dyed candles are typed
     *  collections in 26.2 (no plain Blocks constant), so candles are matched by registry id. */
    private static boolean isSkullOrCandle(net.minecraft.world.level.block.state.BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.SKELETON_SKULL || b == Blocks.WITHER_SKELETON_SKULL
            || b == Blocks.ZOMBIE_HEAD || b == Blocks.CREEPER_HEAD || b == Blocks.PLAYER_HEAD
            || b == Blocks.PIGLIN_HEAD || b == Blocks.DRAGON_HEAD
            || b == Blocks.SKELETON_WALL_SKULL || b == Blocks.WITHER_SKELETON_WALL_SKULL
            || b == Blocks.ZOMBIE_WALL_HEAD || b == Blocks.CREEPER_WALL_HEAD || b == Blocks.PLAYER_WALL_HEAD
            || b == Blocks.PIGLIN_WALL_HEAD || b == Blocks.DRAGON_WALL_HEAD) return true;
        if (b == Blocks.CANDLE) return true;
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return id != null && id.toString().endsWith("_candle");
    }

    /** True if the chunk has a villager/zombie-villager/allay/vindicator/warden (nyx base signal). */
    private static boolean chunkHasVillagerHall(Minecraft mc, ChunkPos pos) {
        if (mc.level == null) return false;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            pos.getMinBlockX(), mc.level.getMinY(), pos.getMinBlockZ(),
            pos.getMaxBlockX() + 1, mc.level.getMaxY() + 1, pos.getMaxBlockZ() + 1);
        for (net.minecraft.world.entity.Entity e : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, box,
                x -> x instanceof net.minecraft.world.entity.npc.villager.Villager
                    || x instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager
                    || x instanceof net.minecraft.world.entity.monster.illager.Vindicator
                    || x instanceof net.minecraft.world.entity.animal.allay.Allay
                    || x instanceof net.minecraft.world.entity.monster.warden.Warden)) {
            return true;
        }
        return false;
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
