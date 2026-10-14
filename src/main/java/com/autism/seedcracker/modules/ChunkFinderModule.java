package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.StoneStackScanner;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.Level;

public final class ChunkFinderModule extends Module {
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius", 12, 1, 32, 1).group("Performance"));
    private final IntSetting budget = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 128, 1).group("Performance"));
    private final IntSetting height = add(new IntSetting("stack-height", "Stack height", 2, 2, 5, 1).group("Detection"));
    private final IntSetting spread = add(new IntSetting("spread-radius", "Spread radius", 1, 0, 16, 1).group("Detection"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("General"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true).group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x6100FFFF).group("Render"));
    private final Map<ChunkPos, Integer> scanned = new HashMap<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private final FinderReport reporter = new FinderReport("Chunk", 55);
    private ClientLevel world;
    private List<LevelChunk> queue = List.of();
    private int cursor, appliedHeight;
    private long refreshAt;
    private StoneStackScanner active;
    private ChunkPos activePos;
    private Set<ChunkPos> flagged = Set.of();

    public ChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-chunk-finder", "Chunk Finder", "Anubis-style stone stacks inside shallow natural block blobs.");
    }

    private void reset() { scanned.clear(); notified.clear(); queue = List.of(); cursor = 0; refreshAt = 0; active = null; flagged = Set.of(); ChunkFlagRenderer.clear(id()); }
    @Override public void onEnable() { reset(); world = null; }
    @Override public void onDisable() { reset(); world = null; }
    @Override public void onGameLeft() { reset(); world = null; if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false); }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level || appliedHeight != height.get()) { reset(); world = mc.level; appliedHeight = height.get(); }
        if (world == null || mc.player == null) return;
        if (world.dimension().equals(Level.NETHER) || world.dimension().equals(Level.END)) { ChunkFlagRenderer.clear(id()); return; }
        long now = System.currentTimeMillis();
        if (cursor >= queue.size() && now >= refreshAt && active == null) { queue = ChunkScanHelper.loadedChunksAround(mc, radius.get()); cursor = 0; refreshAt = now + 1000; }
        long deadline = System.nanoTime() + 3_000_000L;
        int completed = 0;
        while (completed < budget.get() && System.nanoTime() < deadline) {
            if (active == null) {
                if (cursor >= queue.size()) break;
                LevelChunk chunk = queue.get(cursor++); activePos = chunk.getPos();
                if (scanned.containsKey(activePos) || !world.hasChunk(activePos.x(), activePos.z())) continue;
                if (!java.util.Arrays.stream(chunk.getSections()).anyMatch(section -> section != null && section.maybeHas(state -> state.is(net.minecraft.world.level.block.Blocks.STONE))
                    && section.maybeHas(state -> state.is(net.minecraft.world.level.block.Blocks.DIORITE) || state.is(net.minecraft.world.level.block.Blocks.GRANITE) || state.is(net.minecraft.world.level.block.Blocks.ANDESITE) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)))) { scanned.put(activePos, 0); continue; }
                active = new StoneStackScanner(activePos, height.get(), pos -> world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? world.getBlockState(pos) : null);
            }
            if (!active.step(deadline)) break;
            if (active.complete()) scanned.put(activePos, active.stacks().size());
            active = null; completed++;
        }
        ChunkPos center = mc.player.chunkPosition();
        int area = spread.get();
        Map<ChunkPos, Integer> heat = StoneStackScanner.spreadHeat(scanned, center, radius.get(), area);
        heat.keySet().removeIf(pos -> !world.hasChunk(pos.x(), pos.z()));
        flagged = area == 0 ? Set.copyOf(heat.keySet()) : StoneStackScanner.hottest(heat);
        for (ChunkPos pos : flagged) if (notified.add(pos) && notify.get()) com.autism.seedcracker.finder.FinderNotify.flag("[ChunkFinder]", "Stone-stack candidate at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), false);
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get()); reporter.tick(mc, flagged);
    }

    @Override public String info() { return flagged.size() + " stack candidates"; }
}
