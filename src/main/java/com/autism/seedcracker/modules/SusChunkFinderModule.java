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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder.
 *
 * Five modes (mostly ports of the strongest sus-chunk detectors from other clients):
 *  - XENON: fast below-Y15 player-placement detection (non-natural block deep down = placed).
 *  - TYPES: per-block-type detector (kelp, vines, amethyst, bamboo, bee nests, rotated deepslate).
 *  - NEW_CHUNKS: Boze NewChunks - a block-update packet carrying FLOWING (non-source) fluid marks
 *    a freshly GENERATED chunk (fluid ticks only run on generation). New chunks in a straight
 *    line = someone's active highway/tunnel frontier.
 *  - OLD_CHUNKS: inverse of NEW_CHUNKS - a full chunk arriving WITH flowing fluid already in it
 *    was loaded before (someone has been here; the flow was mid-tick when they left).
 *  - TUNNEL: Boze TunnelESP corridor classifier - flags chunks containing 2-high walkable air
 *    corridors with solid walls on the perpendicular axis (the signature of player tunnels).
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class SusChunkFinderModule extends Module {

    public enum Mode { WATER, XENON, TYPES, NEW_CHUNKS, OLD_CHUNKS, TUNNEL, ACTIVITY, SIGNAL }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.WATER, Mode.values())
        .description("WATER (default) = Water-client detector: 49+ vine columns, max-age kelp, rotated deepslate, big deep caves. XENON = below-Y15 placement. TYPES = block types. NEW_CHUNKS = freshly generated (packet fluid-tick). OLD_CHUNKS = visited before. TUNNEL = 2x1 corridors. ACTIVITY = load/unload cycling (another player's render bubble). SIGNAL = loaded chunks beyond the server's render radius (someone else streams them).")
        .group("General"));
    private final EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("XENON: player-only blocks (shulker/hopper/furnace/crafting...) flag at 1 (LOW: 2). Structure-prone blocks (torch/chest/rail/spawner/obsidian) need HIGH 2 / MEDIUM 4 / LOW 6. TYPES: scales the per-type min counts.")
        .group("General"));

    // WATER-mode channels (Water client SusChunkFinder port: each detector its own toggle+threshold).
    private final BoolSetting waterVines = add(new BoolSetting("water-vines", "Long vines", true)
        .description("Flag a vine column this long: natural vines rarely exceed ~25 - 49+ means grown against a player build.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterVineLength = add(new IntSetting("water-vine-length", "Vine length", 49, 10, 120, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterVines.get()));
    private final BoolSetting waterKelp = add(new BoolSetting("water-kelp", "Max-age kelp", true)
        .description("Flag kelp whose AGE blockstate is near max: naturally generated kelp almost never reaches high ages - a fully-aged plant grew in real time near a player.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterKelpAge = add(new IntSetting("water-kelp-age", "Kelp min age", 13, 1, 25, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterKelp.get()));
    private final BoolSetting waterDeepslate = add(new BoolSetting("water-deepslate", "Rotated deepslate", true)
        .description("Flag sideways-axis deepslate (players place it rotated; worldgen is always upright).")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterDeepslateCount = add(new IntSetting("water-deepslate-count", "Rotated count", 3, 1, 50, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterDeepslate.get()));
    private final BoolSetting waterCaves = add(new BoolSetting("water-caves", "Big deep caves", true)
        .description("Flood-fill air pockets between Y-60..20: an unusually big connected cavity is often a hollowed-out base.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterCavePoints = add(new IntSetting("water-cave-points", "Cave points", 27, 5, 100, 1)
        .description("1 point = 25 connected air blocks.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterCaves.get()));

    // ACTIVITY-mode settings (Water ActivityDebug port: load/unload cycling = someone's render bubble).
    private final IntSetting activityCycles = add(new IntSetting("activity-cycles", "Min cycles", 4, 1, 30, 1)
        .description("Complete load+unload cycles inside the window needed to flag.")
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityUnloads = add(new IntSetting("activity-unloads", "Min unloads", 2, 0, 10, 1)
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityWindow = add(new IntSetting("activity-window", "Window (s)", 120, 10, 600, 10)
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityFade = add(new IntSetting("activity-fade", "Fade after (min)", 10, 1, 60, 1)
        .description("Flagged activity chunks un-flag after this long without new cycles.")
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));

    // SIGNAL-mode settings (Water SignalScanner port: chunks loaded beyond the server's own radius).
    private final IntSetting signalServerRadius = add(new IntSetting("signal-server-radius", "Server render radius", 8, 1, 32, 1)
        .description("The server's chunk-send radius around YOU. Loaded chunks farther than this are being streamed for someone else.")
        .group("Signal").visibleWhen(() -> mode.get() == Mode.SIGNAL));
    private final BoolSetting signalNeedBlocks = add(new BoolSetting("signal-need-blocks", "Require signal blocks", true)
        .description("Only flag out-of-radius chunks that ALSO contain storage/utility blocks (chest/furnace/hopper/spawner/anvil...).")
        .group("Signal").visibleWhen(() -> mode.get() == Mode.SIGNAL));

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
        newChunks.clear();
        oldChunks.clear();
        scanCursorAge.reset();
        scanCursorTunnel.reset();
        if (persistFlags.get()) loadFlags();
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        // Mode/sensitivity swap: drop old-mode flags immediately so results don't mix. The
        // NEW/OLD chunk-age intel survives (it's packet history - a rescan can't rebuild it).
        if ("mode".equals(settingId) || "sensitivity".equals(settingId)) {
            flagged.clear();
            notified.clear();
            lastScan.clear();
            activityFlaggedAt.clear();
            scanCursorAge.reset();
            scanCursorTunnel.reset();
        }
    }

    @Override
    public void onDisable() {
        if (persistFlags.get()) saveFlags();
        flagged.clear();
        notified.clear();
        lastScan.clear();
        activity.clear();
        activityFlaggedAt.clear();
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

        switch (mode.get()) {
            case WATER -> tickWater(mc);
            case XENON -> tickXenon(mc);
            case TYPES -> tickTypes(mc);
            case NEW_CHUNKS, OLD_CHUNKS -> tickChunkAge(mc);
            case TUNNEL -> tickTunnelScan(mc);
            case ACTIVITY -> tickActivity(mc);
            case SIGNAL -> tickSignal(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-sus-chunk-finder", flagged, color.get(), tracer.get());
    }

    // ---- NEW_CHUNKS / OLD_CHUNKS mode (Boze NewChunks port) ----

    /** Chunks seen with flowing fluid in a BLOCK UPDATE (fluid ticking = freshly generated). */
    private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
    /** Chunks whose FULL DATA arrived already containing flowing fluid (loaded before us). */
    private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        Mode m = mode.get();
        // ACTIVITY: count chunk load/unload events (another player's render bubble cycling ours).
        if (m == Mode.ACTIVITY) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket load) {
                onChunkActivity(new ChunkPos(load.getX(), load.getZ()), true);
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forget) {
                onChunkActivity(forget.pos(), false);
            }
            return false;
        }
        if (m != Mode.NEW_CHUNKS && m != Mode.OLD_CHUNKS) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        // A block UPDATE carrying flowing (non-source) fluid means the server is actively fluid-
        // ticking that chunk - which only happens during generation: a NEW chunk (Boze).
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket bup) {
            var fs = bup.getBlockState().getFluidState();
            if (!fs.isEmpty() && !fs.isSource()) {
                ChunkPos cp = new ChunkPos(bup.getPos().getX() >> 4, bup.getPos().getZ() >> 4);
                if (!oldChunks.contains(cp)) newChunks.add(cp);
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket sup) {
            sup.runUpdates((pos, state) -> {
                var fs = state.getFluidState();
                if (!fs.isEmpty() && !fs.isSource()) {
                    ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
                    if (!oldChunks.contains(cp)) newChunks.add(cp);
                }
            });
        }
        return false;
    }

    /** Full-chunk arrival scan: flowing fluid already IN the chunk data = old chunk (visited). */
    private void tickChunkAge(Minecraft mc) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = scanRadius.get();
        // Classify a few chunks per tick: full data containing flowing fluid = OLD.
        for (LevelChunk chunk : scanCursorAge.nextBatch(mc, radius, 5000, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (newChunks.contains(pos) || oldChunks.contains(pos)) continue;
            if (chunkHasFlowingFluid(chunk)) oldChunks.add(pos);
        }
        // Render whichever age class the mode wants.
        flagged.clear();
        flagged.addAll(mode.get() == Mode.NEW_CHUNKS ? newChunks : oldChunks);
        int pr = radius * 4; // age intel is worth keeping further out than live scans
        flagged.removeIf(p -> tooFar(p, center, pr));
    }

    private final com.autism.seedcracker.finder.ScanCursor scanCursorAge = new com.autism.seedcracker.finder.ScanCursor();

    private static boolean chunkHasFlowingFluid(LevelChunk chunk) {
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < chunk.getSectionsCount(); s++) {
            var sec = chunk.getSection(s);
            if (sec.hasOnlyAir()) continue;
            int baseY = minY + (s << 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var fs = sec.getBlockState(x, y, z).getFluidState();
                        if (!fs.isEmpty() && !fs.isSource()) return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- TUNNEL mode (Boze TunnelESP corridor classifier) ----

    private final com.autism.seedcracker.finder.ScanCursor scanCursorTunnel = new com.autism.seedcracker.finder.ScanCursor();

    private void tickTunnelScan(Minecraft mc) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = scanRadius.get();
        int need = sensitivity.get().scale(4); // corridor cells needed to flag a chunk
        for (LevelChunk chunk : scanCursorTunnel.nextBatch(mc, radius, 5000, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (countTunnelCells(mc, chunk, need) >= need) {
                if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
            } else {
                flagged.remove(pos);
            }
        }
        int pr = radius + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    /**
     * Boze method2074 corridor test: a tunnel cell is 2-high walkable air standing on solid
     * ground where exactly one horizontal axis is open (both directions air) and the other is
     * walled (both directions solid) - natural caves almost never form that pattern repeatedly.
     * Only scans below Y50 (surface trenches are farms/creeper holes, not tunnels).
     */
    private int countTunnelCells(Minecraft mc, LevelChunk chunk, int enough) {
        int count = 0;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = Math.max(chunk.getMinY() + 1, -60);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= 50; y++) {
                    m.set(startX + x, y, startZ + z);
                    if (!mc.level.getBlockState(m).isAir()) continue;
                    if (!mc.level.getBlockState(m.above()).isAir()) continue;          // 2-high air
                    if (mc.level.getBlockState(m.below()).isAir()) continue;           // solid floor
                    boolean eastOpen = mc.level.getBlockState(m.east()).isAir();
                    boolean westOpen = mc.level.getBlockState(m.west()).isAir();
                    boolean northOpen = mc.level.getBlockState(m.north()).isAir();
                    boolean southOpen = mc.level.getBlockState(m.south()).isAir();
                    boolean xCorridor = eastOpen && westOpen && !northOpen && !southOpen;
                    boolean zCorridor = northOpen && southOpen && !eastOpen && !westOpen;
                    if ((xCorridor || zCorridor) && ++count >= enough) return count;
                }
            }
        }
        return count;
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
                int code = susCodeBelowY15(mc, chunk, minY);
                boolean sus = code > 0;
                // Structure veto only applies to weak (structure-prone) evidence: a shulker or
                // furnace inside a mineshaft is still a player stash.
                if (code == 1 && skipStructures.get() && isNaturalStructure(mc, pos)) sus = false;
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

    // ---- WATER mode: Water-client SusChunkFinder port (vines/kelp-age/deepslate/caves) ----

    private void tickWater(Minecraft mc) {
        long now = System.currentTimeMillis();
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();

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
                if (scanWaterChunk(mc, chunk)) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (--budget <= 0) break outer;
            }
        }

        int pr = radius + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    private boolean scanWaterChunk(Minecraft mc, LevelChunk chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY() - 1;
        var sens = sensitivity.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // Long vine columns (top-down run count).
        if (waterVines.get()) {
            int req = sens.scale(waterVineLength.get());
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int len = 0;
                    for (int y = maxY; y >= Math.max(minY, 30); y--) {
                        m.set(startX + x, y, startZ + z);
                        if (chunk.getBlockState(m).is(Blocks.VINE)) {
                            if (++len >= req) return true;
                        } else {
                            len = 0;
                        }
                    }
                }
            }
        }

        // Max-age kelp (AGE blockstate property; natural kelp almost never near max).
        if (waterKelp.get()) {
            int req = Math.min(25, waterKelpAge.get());
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= Math.min(maxY, 62); y++) {
                        m.set(startX + x, y, startZ + z);
                        BlockState s = chunk.getBlockState(m);
                        if (!s.is(Blocks.KELP)) continue;
                        if (s.hasProperty(net.minecraft.world.level.block.KelpBlock.AGE)
                            && s.getValue(net.minecraft.world.level.block.KelpBlock.AGE) >= req) {
                            return true;
                        }
                    }
                }
            }
        }

        // Rotated (sideways-axis) deepslate: players place it rotated, worldgen never does.
        if (waterDeepslate.get()) {
            int req = sens.scale(waterDeepslateCount.get());
            int count = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= Math.min(maxY, 60); y++) {
                        m.set(startX + x, y, startZ + z);
                        BlockState s = chunk.getBlockState(m);
                        if (s.is(Blocks.DEEPSLATE)
                            && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                            && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.Y
                            && ++count >= req) {
                            return true;
                        }
                    }
                }
            }
        }

        // Big deep cave: largest connected air pocket between Y-60..20 (flood fill inside the chunk).
        if (waterCaves.get()) {
            int reqPts = sens.scale(waterCavePoints.get());
            int caveMinY = Math.max(minY, -60);
            int caveMaxY = Math.min(20, maxY);
            int cap = reqPts * 25 * 4;
            java.util.Set<Long> visited = new java.util.HashSet<>();
            int bestSize = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = caveMinY + 1; y < caveMaxY; y++) {
                        long pk = ((long) x << 16) | ((long) z << 8) | (long) (y - caveMinY);
                        if (visited.contains(pk)) continue;
                        m.set(startX + x, y, startZ + z);
                        if (!chunk.getBlockState(m).isAir()) continue;

                        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                        q.add(new int[]{x, y, z});
                        int size = 0;
                        while (!q.isEmpty() && size < cap) {
                            int[] cur = q.poll();
                            int lx = cur[0], ly = cur[1], lz = cur[2];
                            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) continue;
                            if (ly < caveMinY + 1 || ly >= caveMaxY) continue;
                            long p2 = ((long) lx << 16) | ((long) lz << 8) | (long) (ly - caveMinY);
                            if (!visited.add(p2)) continue;
                            m.set(startX + lx, ly, startZ + lz);
                            if (!chunk.getBlockState(m).isAir()) continue;
                            size++;
                            q.add(new int[]{lx + 1, ly, lz}); q.add(new int[]{lx - 1, ly, lz});
                            q.add(new int[]{lx, ly + 1, lz}); q.add(new int[]{lx, ly - 1, lz});
                            q.add(new int[]{lx, ly, lz + 1}); q.add(new int[]{lx, ly, lz - 1});
                        }
                        if (size > bestSize) bestSize = size;
                    }
                }
            }
            if (bestSize / 25 >= reqPts) return true;
        }

        return false;
    }

    // ---- ACTIVITY mode: chunk load/unload cycle counting (Water ActivityDebug port) ----

    private static final class ChunkActivity {
        final java.util.ArrayDeque<Long> loads = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Long> unloads = new java.util.ArrayDeque<>();

        synchronized void add(java.util.ArrayDeque<Long> list) {
            long now = System.currentTimeMillis();
            list.addLast(now);
            while (!list.isEmpty() && now - list.peekFirst() > 600_000L) list.pollFirst();
        }

        synchronized int recent(java.util.ArrayDeque<Long> list, long windowMs) {
            long now = System.currentTimeMillis();
            int n = 0;
            for (long t : list) if (now - t < windowMs) n++;
            return n;
        }
    }

    private final Map<ChunkPos, ChunkActivity> activity = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> activityFlaggedAt = new ConcurrentHashMap<>();

    private void onChunkActivity(ChunkPos pos, boolean load) {
        ChunkActivity act = activity.computeIfAbsent(pos, k -> new ChunkActivity());
        act.add(load ? act.loads : act.unloads);
        long windowMs = activityWindow.get() * 1000L;
        int loads = act.recent(act.loads, windowMs);
        int unloads = act.recent(act.unloads, windowMs);
        if (Math.min(loads, unloads) >= activityCycles.get() && unloads >= activityUnloads.get()) {
            activityFlaggedAt.put(pos, System.currentTimeMillis());
        }
    }

    private void tickActivity(Minecraft mc) {
        long fadeMs = activityFade.get() * 60_000L;
        long now = System.currentTimeMillis();
        activityFlaggedAt.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
        flagged.clear();
        flagged.addAll(activityFlaggedAt.keySet());
        for (ChunkPos p : activityFlaggedAt.keySet()) {
            if (notified.add(p)) onNewFlag(p);
        }
        // Bound the tracking map (chunks far outside interest fade naturally).
        if (activity.size() > 4096) activity.clear();
    }

    // ---- SIGNAL mode: loaded chunks beyond the server's render radius (Water SignalScanner port) ----

    private void tickSignal(Minecraft mc) {
        long now = System.currentTimeMillis();
        ChunkPos center = mc.player.chunkPosition();
        int serverR = signalServerRadius.get();
        int scanR = Math.max(scanRadius.get() * 4, serverR + 2); // look well past the bubble

        int budget = chunksPerTick.get() * 4; // cheap per-chunk check, allow more
        outer:
        for (int dx = -scanR; dx <= scanR && budget > 0; dx++) {
            for (int dz = -scanR; dz <= scanR; dz++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist <= serverR) continue; // inside our own bubble - expected to be loaded
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get()) continue;
                lastScan.put(pos, now);
                budget--;

                boolean sus = true;
                if (signalNeedBlocks.get()) {
                    sus = chunkHasSignalBlocks(mc.level.getChunk(cx, cz));
                }
                if (sus) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (budget <= 0) break outer;
            }
        }

        int pr = scanR + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    /** Storage/utility block entities = player worked here (SignalScanner's signal-block list). */
    private static boolean chunkHasSignalBlocks(LevelChunk chunk) {
        for (var e : chunk.getBlockEntities().entrySet()) {
            Block b = e.getValue().getBlockState().getBlock();
            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.ENDER_CHEST
                || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
                || b == Blocks.HOPPER || b == Blocks.DROPPER || b == Blocks.DISPENSER
                || b == Blocks.SPAWNER || b == Blocks.BARREL || b == Blocks.ENCHANTING_TABLE
                || b == Blocks.BEACON || b == Blocks.CONDUIT
                || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sus code for the chunk below Y15: 0 = clean, 1 = weak evidence (structure-prone blocks -
     * the natural-structure veto applies), 2 = strong evidence (player-only blocks - no veto).
     * Splitting the evidence kills the old false flags: one mineshaft torch or natural obsidian
     * used to flag instantly on HIGH/MEDIUM.
     */
    private int susCodeBelowY15(Minecraft mc, LevelChunk chunk, int minY) {
        var sens = sensitivity.get();
        int weakNeed = switch (sens) {
            case HIGH -> 2;
            case MEDIUM -> 4;
            default -> 6;
        };
        int strongNeed = sens == com.autism.seedcracker.finder.FinderSensitivity.LOW ? 2 : 1;
        int weak = 0, strong = 0;
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
                        Block b = chunk.getBlockState(m).getBlock();
                        int w = placementWeight(b);
                        if (w == 0) continue;
                        // Obsidian touching lava/water formed naturally at a fluid contact.
                        if (b == Blocks.OBSIDIAN && touchesFluid(mc, m)) continue;
                        if (w >= 2) {
                            if (++strong >= strongNeed) return 2;
                        } else if (++weak >= weakNeed) {
                            return 1;
                        }
                    }
                }
            }
        }
        // Near-threshold mix (e.g. 1 strong on LOW + several weak) still deserves a vetoable flag.
        if (strong > 0 && weak >= 2) return 1;
        return 0;
    }

    /** 2 = never generates below Y15 (player-only), 1 = also generates inside natural structures. */
    private static int placementWeight(Block b) {
        if (b == Blocks.ENDER_CHEST || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
            || b == Blocks.HOPPER || b == Blocks.BARREL || b == Blocks.TRAPPED_CHEST
            || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE
            || b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL
            || b == Blocks.BREWING_STAND || b == Blocks.BEE_NEST || b == Blocks.BEEHIVE
            || b == Blocks.CAULDRON || b == Blocks.WATER_CAULDRON || b == Blocks.LAVA_CAULDRON
            || b == Blocks.NETHER_PORTAL) {
            return 2;
        }
        if (b == Blocks.CHEST || b == Blocks.SPAWNER || b == Blocks.OBSIDIAN
            || b == Blocks.END_PORTAL_FRAME
            || b == Blocks.TORCH || b == Blocks.WALL_TORCH
            || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.RAIL || b == Blocks.POWERED_RAIL
            || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL) {
            return 1;
        }
        return 0;
    }

    /** Any of the 6 neighbours is lava/water (flowing or source). */
    private static boolean touchesFluid(Minecraft mc, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * True if the chunk is part of a natural structure, not a base. Recognizes dungeons + trial
     * chambers (as before) plus the generators of most false flags: mineshafts (planks/fences/
     * cobwebs alongside their torches, rails and chests), ancient cities (deepslate tiles, sculk,
     * soul lanterns - all below Y15 by definition) and strongholds (stone bricks + iron bars,
     * around END_PORTAL_FRAME rooms). Plain TUFF was removed from the dungeon signature: it
     * generates as natural ore-like blobs everywhere below Y0, so it both vetoed real bases and
     * said nothing about structures.
     */
    private boolean isNaturalStructure(Minecraft mc, ChunkPos pos) {
        int dungeonTrial = 0, mineshaft = 0, ancientCity = 0, stronghold = 0;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = 15; y >= mc.level.getMinY(); y -= 2) {
                    m.set(baseX + x, y, baseZ + z);
                    Block b = mc.level.getBlockState(m).getBlock();
                    if (b == Blocks.MOSSY_COBBLESTONE
                        || b == Blocks.POLISHED_TUFF || b == Blocks.TUFF_BRICKS
                        || b == Blocks.CHISELED_TUFF || b == Blocks.CHISELED_TUFF_BRICKS
                        || b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) {
                        dungeonTrial++;
                    } else if (b == Blocks.OAK_PLANKS || b == Blocks.DARK_OAK_PLANKS
                        || b == Blocks.OAK_FENCE || b == Blocks.DARK_OAK_FENCE
                        || b == Blocks.COBWEB) {
                        mineshaft++;
                    } else if (b == Blocks.DEEPSLATE_TILES || b == Blocks.CRACKED_DEEPSLATE_TILES
                        || b == Blocks.DEEPSLATE_BRICKS || b == Blocks.CRACKED_DEEPSLATE_BRICKS
                        || b == Blocks.SCULK || b == Blocks.SCULK_SENSOR || b == Blocks.SCULK_SHRIEKER
                        || b == Blocks.SCULK_CATALYST || b == Blocks.SOUL_FIRE) {
                        ancientCity++;
                    } else if (b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS
                        || b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.IRON_BARS) {
                        stronghold++;
                    }
                }
            }
        }
        return dungeonTrial >= 3 || mineshaft >= 4 || ancientCity >= 4 || stronghold >= 4;
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
