package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.BaseConfidence;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.util.pure.SusScore;
import com.autism.seedcracker.util.pure.SusScore.Signal;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder (Beta): runs the strongest detectors from every Sus Chunk Finder mode on each
 * chunk and fuses them with {@link SusScore} instead of trusting any single one. A chunk flags
 * only when two or more independent signals agree, which is what kills the classic false flags
 * (lone dungeon chest, jungle vine shaft, ancient-city deepslate, natural geode).
 *
 * Budgeted round-robin: a couple of chunks per tick, each re-scored every rescan interval.
 */
public final class SusChunkBetaModule extends Module {

    private static final String RENDER_ID = SeedcrackerAddon.ID + ":sus-chunk-beta";

    private final IntSetting threshold = add(new IntSetting("threshold", "Score to flag", 45, 10, 100, 5)
        .description("0-100 fused score needed. Storage 45, running redstone 35, deepslate/vines/kelp 25, room/built 20, glow/skulls 15.")
        .group("General"));
    private final IntSetting minSignals = add(new IntSetting("min-signals", "Agreeing signals", 2, 1, 5, 1)
        .description("Independent signals that must agree. 2 = recommended; 1 behaves like the old single-mode finders (noisy).")
        .group("General"));
    private final IntSetting radius = add(new IntSetting("radius", "Scan radius (chunks)", 6, 1, 16, 1).group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 16, 1)
        .description("Higher = faster sweep, lower = smoother FPS.").group("General"));
    private final IntSetting rescanSeconds = add(new IntSetting("rescan", "Rescan (s)", 20, 2, 300, 1)
        .description("How often a chunk is re-scored (redstone and flats change, storage rarely does).").group("General"));
    private final BoolSetting spread = add(new BoolSetting("spread", "Neighbour heat", true)
        .description("Chunks next to a flagged chunk get up to +15% (bases sprawl across borders). Never flags a chunk with no evidence.")
        .group("General"));
    private final IntSetting maxY = add(new IntSetting("max-y", "Underground below Y", 45, -40, 120, 5)
        .description("Vines/kelp/rooms only count below this (surface jungles and oceans are natural).").group("Signals"));
    private final BoolSetting glow = add(new BoolSetting("glow", "Amethyst glow", true)
        .description("Amethyst growth in the chunk (geodes = caves = traffic). Weak on its own by design.").group("Signals"));
    private final BoolSetting redstone = add(new BoolSetting("redstone", "Running redstone", true)
        .description("Powered repeaters/comparators: a clock is cycling right now.").group("Signals"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x50FF40C0).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("General"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private final Map<ChunkPos, SusScore.Result> results = new HashMap<>();
    private final Map<ChunkPos, Long> scannedAt = new HashMap<>();
    private final FinderReport reporter = new FinderReport("SusBeta", 60);
    private List<LevelChunk> queue = List.of();
    private int queueIndex;
    private int pruneTicks;

    public SusChunkBetaModule() {
        super(SeedcrackerAddon.ID + ":sus-chunk-beta", "Sus Chunk Finder (Beta)",
            "Fuses every sus-chunk detector; flags only when 2+ independent signals agree. Fewer false flags.");
    }

    // Hidden from the menu: this engine is driven by Sus Chunk Finder's BETA mode, so users meet
    // it there as one entry instead of a confusing second "(Beta)" module.
    @Override
    public boolean showInModuleMenu() {
        return false;
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.init();
        reset();
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.clear(RENDER_ID);
        reset();
    }

    @Override
    public void onGameLeft() {
        reset();
    }

    private void reset() {
        flagged.clear();
        notified.clear();
        results.clear();
        scannedAt.clear();
        reporter.clear();
        queue = List.of();
        queueIndex = 0;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        if (queueIndex >= queue.size()) {
            queue = ChunkScanHelper.loadedChunksAround(mc, radius.get());
            queueIndex = 0;
        }
        long rescanMs = rescanSeconds.get() * 1000L;
        int budget = chunksPerTick.get();
        while (budget > 0 && queueIndex < queue.size()) {
            LevelChunk chunk = queue.get(queueIndex++);
            ChunkPos pos = chunk.getPos();
            Long last = scannedAt.get(pos);
            if (last != null && now - last < rescanMs) continue;
            scannedAt.put(pos, now);
            budget--;
            evaluate(chunk);
        }
        if (++pruneTicks >= 40) {
            pruneTicks = 0;
            ChunkPos c = mc.player.chunkPosition();
            int r = radius.get() + 2;
            java.util.function.Predicate<ChunkPos> far = p -> Math.abs(p.x() - c.x()) > r || Math.abs(p.z() - c.z()) > r;
            flagged.removeIf(far);
            notified.removeIf(far);
            results.keySet().removeIf(far);
            scannedAt.keySet().removeIf(far);
        }
        ChunkFlagRenderer.feed(RENDER_ID, flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private void evaluate(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int yCut = maxY.get();
        Map<Signal, Double> s = new EnumMap<>(Signal.class);

        int storage = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be.getBlockPos().getY() <= 50 && isPlayerStorage(be.getBlockState().getBlock())) storage++;
        }
        s.put(Signal.STORAGE, SusScore.ramp(storage, 1));
        s.put(Signal.ROTATED_DEEPSLATE, SusScore.ramp(
            ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isRotatedDeepslate, 6), 3));
        s.put(Signal.DEEP_VINES, SusChunkFinderModule.vineRunHit(chunk, 8, yCut, chunk.getMinY()) ? 1.0 : 0);
        s.put(Signal.DEEP_KELP, SusChunkFinderModule.kelpBelowY(chunk, Math.min(yCut, 24)) ? 1.0 : 0);
        s.put(Signal.FLAT_ROOM, SusChunkFinderModule.flatRoomHit(chunk, 30) ? 1.0 : 0);
        s.put(Signal.SKULL_CANDLE, SusScore.ramp(
            ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isSkullOrCandle, 6), 3));
        if (redstone.get()) {
            s.put(Signal.POWERED_REDSTONE, SusScore.ramp(ChunkScanHelper.countBlocksInChunk(chunk, SusChunkBetaModule::isPoweredClock, 6), 3));
        }
        if (glow.get()) {
            s.put(Signal.GLOW, SusScore.ramp(ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isAmethystGrowth, 24), 12));
        }
        // BaseConfidence walks every block; only pay for it when something else already fired.
        if (s.values().stream().anyMatch(v -> v > 0)) {
            BaseConfidence.Result bc = BaseConfidence.score(chunk);
            s.put(Signal.BUILT, bc.score() >= 60 ? 1.0 : bc.score() >= 35 ? 0.5 : 0);
        }

        SusScore.Result r = SusScore.score(s, spread.get() ? neighbourHeat(pos) : 0, threshold.get(), minSignals.get());
        results.put(pos, r);
        if (r.flagged()) {
            flagged.add(pos);
            if (notified.add(pos) && notify.get()) {
                FinderNotify.flag("§d[SusBeta]", "Sus chunk X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ()
                    + " (" + r.score() + ": " + r.why() + ")", true);
            }
        } else {
            flagged.remove(pos);
        }
    }

    private int neighbourHeat(ChunkPos pos) {
        int best = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                SusScore.Result r = results.get(new ChunkPos(pos.x() + dx, pos.z() + dz));
                if (r != null && r.flagged()) best = Math.max(best, r.score());
            }
        }
        return best;
    }

    /** Storage that natural structures never generate (plain chests/spawners are excluded on purpose). */
    private static boolean isPlayerStorage(Block b) {
        return b == Blocks.BARREL || b == Blocks.HOPPER || b == Blocks.ENDER_CHEST || b == Blocks.FURNACE
            || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER || b == Blocks.ENCHANTING_TABLE || b == Blocks.BEACON
            || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    private static boolean isPoweredClock(BlockState s) {
        return (s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR))
            && s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED);
    }

    /** Flagged chunks with their score and reasons, strongest first (for the guide/HUD). */
    public List<Map.Entry<ChunkPos, SusScore.Result>> ranked() {
        List<Map.Entry<ChunkPos, SusScore.Result>> out = new ArrayList<>();
        for (ChunkPos p : flagged) if (results.containsKey(p)) out.add(Map.entry(p, results.get(p)));
        out.sort((a, b) -> Integer.compare(b.getValue().score(), a.getValue().score()));
        return out;
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }
}
