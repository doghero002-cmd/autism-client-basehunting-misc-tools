// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ScanCursor;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.palette.AmethystScan;
import com.autism.seedcracker.spectre.palette.PaletteScan;
import com.autism.seedcracker.util.RelogPersistence;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Spectre AmethystBypass (d5a7dd3de0be), preserving the existing amethyst-esp registry id. */
public final class AmethystEspModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":amethyst-esp";
    private enum Method { BYPASS, LIGHT, ANBS_SCAN }
    private final EnumSetting<Method> method = add(new EnumSetting<>("method", "Method", Method.BYPASS, Method.values(),
        value -> switch (value) { case BYPASS -> "Bypass"; case LIGHT -> "Light"; case ANBS_SCAN -> "ANBS+ Scan"; }).group("Detection"));
    private final IntSetting simDistance = add(new IntSetting("sim-distance", "Scan radius (chunks)", 8, 1, 32, 1).group("Detection"));
    private final IntSetting minCells = add(new IntSetting("min-cells", "Minimum glow cells", 12, 1, 100, 1)
        .group("Detection").visibleWhen(() -> method.get() == Method.ANBS_SCAN));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 4, 1, 16, 1).group("Performance"));
    private final BoolSetting chatAlert = add(new BoolSetting("chat-alert", "Chat alert", false).group("General"));
    private final BoolSetting blockEsp = add(new BoolSetting("block-esp", "Render markers", true).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final BoolSetting fill = add(new BoolSetting("fill", "Fill markers", true).group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Marker color", -3636481).group("Render"));
    private final ScanCursor scan = new ScanCursor();
    private final AmethystScan detector = new AmethystScan();
    private final Map<ChunkPos, List<Integer>> rawPalettes = new LinkedHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> found = new LinkedHashMap<>();
    private final Set<ChunkPos> checked = new HashSet<>();
    private final Set<ChunkPos> dirty = new LinkedHashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private ClientLevel level;
    private Method lastMethod;
    private int lastRadius;
    private int lastMinimum;
    private boolean unloadHook;

    public AmethystEspModule() {
        super(ID, "Amethyst ESP", "Spectre geode palette leaks, hidden bud light peaks and ANBS+ glow edges.");
    }

    @Override public void onEnable() {
        reset();
        BlockEspRenderer.init();
        if (!unloadHook) {
            unloadHook = true;
            ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (world != level) return;
                ChunkPos pos = chunk.getPos();
                rawPalettes.remove(pos);
                found.remove(pos);
                checked.remove(pos);
                dirty.remove(pos);
                notified.remove(pos);
                BlockEspRenderer.clear(ID);
            });
        }
    }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    private void reset() {
        rawPalettes.clear();
        clearScan();
        level = null;
        lastMethod = null;
    }
    private void clearScan() {
        found.clear();
        checked.clear();
        dirty.clear();
        notified.clear();
        scan.reset();
        detector.clear();
        BlockEspRenderer.clear(ID);
    }
    private void sync(Minecraft mc) {
        if (level != mc.level) {
            reset();
            level = mc.level;
        }
        if (lastMethod != method.get() || lastRadius != simDistance.get() || lastMinimum != minCells.get()) {
            clearScan();
            lastMethod = method.get();
            lastRadius = simDistance.get();
            lastMinimum = minCells.get();
        }
    }

    /** Takes ownership of a fresh packet read-buffer wrapper; releases it on every path. */
    public void onChunkData(FriendlyByteBuf buffer, int chunkX, int chunkZ, int sectionCount, int minSectionY) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) throw new IllegalStateException("Palette packets must be applied on the client thread");
            if (!isEnabled()) return;
            sync(mc);
            if (level == null || !PaletteScan.validChunk(chunkX, chunkZ) || !level.getChunkSource().hasChunk(chunkX, chunkZ)) return;
            var result = PaletteScan.read(buffer.nioBuffer(buffer.readerIndex(), buffer.readableBytes()), sectionCount, minSectionY,
                id -> AmethystScan.bud(Block.stateById(id)));
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            if (result.valid() && !result.sections().isEmpty()) rawPalettes.put(pos, result.sections().stream().map(PaletteScan.Section::y).toList());
            else rawPalettes.remove(pos);
            trimRaw();
            invalidate(pos);
        } finally {
            buffer.release();
        }
    }

    public void onBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Block updates must be applied on the client thread");
        if (!isEnabled()) return;
        sync(mc);
        invalidate(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
    }
    private void invalidate(ChunkPos pos) {
        checked.remove(pos);
        found.remove(pos);
        if (dirty.size() < 1024) dirty.add(pos);
    }
    private void trimRaw() {
        while (rawPalettes.size() > 1024) rawPalettes.remove(rawPalettes.keySet().iterator().next());
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        sync(mc);
        if (level == null || mc.player == null) { BlockEspRenderer.clear(ID); return; }
        ChunkPos center = mc.player.chunkPosition();
        java.util.function.Predicate<ChunkPos> stale = pos -> !level.getChunkSource().hasChunk(pos.x(), pos.z())
            || Math.abs((long) pos.x() - center.x()) > simDistance.get() || Math.abs((long) pos.z() - center.z()) > simDistance.get();
        rawPalettes.keySet().removeIf(stale);
        found.keySet().removeIf(stale);
        checked.removeIf(stale);
        dirty.removeIf(stale);
        notified.removeIf(stale);
        long deadline = System.nanoTime() + 2_000_000;
        int remaining = chunksPerTick.get();
        while (!dirty.isEmpty() && remaining > 0 && System.nanoTime() < deadline) {
            ChunkPos pos = dirty.iterator().next();
            dirty.remove(pos);
            var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) scanChunk(chunk);
            remaining--;
        }
        // ponytail: the 2 ms budget is checked between chunks; split sections if one chunk becomes costly.
        while (remaining-- > 0 && System.nanoTime() < deadline) {
            var batch = scan.nextBatch(mc, simDistance.get(), 1000, 1);
            if (batch.isEmpty()) break;
            LevelChunk chunk = batch.getFirst();
            if (level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) == chunk) scanChunk(chunk);
        }
        if (blockEsp.get()) {
            Set<BlockPos> markers = new HashSet<>();
            for (var set : found.values()) markers.addAll(set);
            BlockEspRenderer.feed(ID, Set.copyOf(markers), color.get(), tracer.get(), fill.get());
        } else BlockEspRenderer.clear(ID);
    }

    private void scanChunk(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        Set<BlockPos> hits;
        if (method.get() == Method.BYPASS) {
            if (!checked.add(pos)) return;
            BlockPos marker = AmethystScan.paletteMarker(chunk, rawPalettes.getOrDefault(pos, List.of()));
            hits = marker == null ? Set.of() : Set.of(marker);
        } else if (method.get() == Method.LIGHT) hits = detector.lightPeaks(level, chunk);
        else hits = detector.glowEdges(level, chunk, minCells.get());
        if (hits.isEmpty()) {
            found.remove(pos);
            return;
        }
        found.put(pos, hits);
        int count = found.values().stream().mapToInt(Set::size).sum();
        while (found.size() > 1024 || count > 8192) {
            var oldest = found.keySet().iterator().next();
            count -= found.remove(oldest).size();
            notified.remove(oldest);
        }
        if (notified.add(pos) && chatAlert.get()) AutismClientMessaging.sendPrefixed("§d[AmethystESP] §fGeode signal at "
            + pos.getMinBlockX() + ", " + pos.getMinBlockZ() + " §7(" + method.displayString() + "; not proof of a base)");
    }

    @Override public String info() { return found.size() + " geode signals"; }
}
