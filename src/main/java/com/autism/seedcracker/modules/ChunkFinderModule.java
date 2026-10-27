// SPDX-License-Identifier: GPL-3.0-only
// Port of Spectre ChunkScannerModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.modules;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.StoneStackScanner;
import com.autism.seedcracker.spectre.chunks.seedscan.AdvancedScanner;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;

public final class ChunkFinderModule extends Module {
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius", 12, 1, 32, 1).group("Performance"));
    private final IntSetting budget = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 128, 1).group("Performance"));
    private final BoolSetting advancedMode = add(new BoolSetting("advanced-scanner", "Seed-aware scanner", false).group("Detection"));
    private final StringSetting seed = add(new StringSetting("seed", "World seed", "")
        .description("Vanilla Overworld prediction, within 8 chunks and +/-250,000 blocks. Seed mismatches stop predictions.")
        .group("Detection").visibleWhen(advancedMode::get));
    private final IntSetting height = add(new IntSetting("stack-height", "Stack height", 2, 2, 5, 1).group("Detection").visibleWhen(() -> !advancedMode.get()));
    private final IntSetting spread = add(new IntSetting("spread-radius", "Spread radius", 1, 0, 16, 1).group("Detection"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true).group("Alerts").visibleWhen(notify::get));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true).group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x6100FFFF).group("Render"));
    private final Map<ChunkPos, Integer> scanned = new HashMap<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private final LinkedHashSet<ChunkPos> queue = new LinkedHashSet<>();
    private final FinderReport reporter = new FinderReport("Chunk", 55);
    private ClientLevel world;
    private int ticks, seedStill;
    private String seedText;
    private boolean seedAsked;
    private StoneStackScanner active;
    private ChunkPos activePos;
    private AdvancedScanner advanced, closing;
    private Set<ChunkPos> flagged = Set.of();
    private final Set<ChunkPos> probed = new HashSet<>();
    private java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientPacketListener> probeServer = new java.lang.ref.WeakReference<>(null);
    private static final net.minecraft.core.Direction[] SIDES = net.minecraft.core.Direction.values();
    private boolean probing, sawBlob;

    public ChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-chunk-finder", "Chunk Finder",
            "Spectre stone-stack and seed-aware blob checks. Candidates, not confirmed bases.");
    }

    private void reset(ClientLevel level) {
        if (advanced != null) { advanced.close(); closing = advanced; advanced = null; }
        scanned.clear(); notified.clear(); queue.clear(); reporter.clear();
        active = null; activePos = null; flagged = Set.of(); ticks = 0;
        seedText = null; seedStill = 0; seedAsked = false; world = level;
        var connection = Minecraft.getInstance().getConnection();
        if (level == null) { probed.clear(); probing = false; probeServer.clear(); }
        if (level != null && connection != probeServer.get()) {
            probeServer = new java.lang.ref.WeakReference<>(connection); probing = true; probed.clear();
        }
        ChunkFlagRenderer.clear(id());
    }

    @Override public void onEnable() { reset(Minecraft.getInstance().level); ChunkFlagRenderer.init(); }
    @Override public void onDisable() { reset(null); }
    @Override public void onGameLeft() {
        reset(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onSettingsReset() { reset(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override protected void onOptionValueChanged(String settingId) {
        if ("advanced-scanner".equals(settingId) || "stack-height".equals(settingId)) reset(isEnabled() ? Minecraft.getInstance().level : null);
    }

    /** ClientPacketListener TAIL hook, after vanilla has applied chunk/block data. */
    public void onPacketApplied(ClientLevel level, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread() || level == null || level != mc.level) return;
        if (level != world) reset(level);
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            if (advanced != null) advanced.chunkArrived(chunk.getX(), chunk.getZ());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(chunk.getX() + dx, chunk.getZ() + dz));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            ChunkPos pos = forget.pos();
            scanned.remove(pos); notified.remove(pos); queue.remove(pos);
            if (pos.equals(activePos)) { active = null; activePos = null; }
            if (advanced != null) advanced.chunkArrived(pos.x(), pos.z());
        } else if (packet instanceof ClientboundBlockUpdatePacket block) blockChanged(block.getPos());
        else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) section.runUpdates((pos, state) -> blockChanged(pos));
    }

    private void blockChanged(BlockPos pos) {
        if (advanced != null) advanced.blockChanged(pos);
        if (pos.getY() < -1 || pos.getY() > 33) return;
        for (int cx = (pos.getX() - 2) >> 4; cx <= (pos.getX() + 2) >> 4; cx++)
            for (int cz = (pos.getZ() - 2) >> 4; cz <= (pos.getZ() + 2) >> 4; cz++) enqueue(new ChunkPos(cx, cz));
    }

    private boolean nearby(ChunkPos pos) {
        var player = Minecraft.getInstance().player;
        return player != null && distance(player.chunkPosition(), pos) <= radius.get()
            && world != null && world.getChunkSource().getChunkNow(pos.x(), pos.z()) != null;
    }

    private void enqueue(ChunkPos pos) {
        if (!advancedMode.get() && nearby(pos) && queue.size() < 4225) queue.add(pos);
    }

    private static int distance(ChunkPos a, ChunkPos b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())); }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) reset(mc.level);
        if (closing != null && closing.stopped()) closing = null;
        if (world == null || mc.player == null) return;
        if (!world.dimension().equals(Level.OVERWORLD)
            && !world.dimensionTypeRegistration().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)) {
            if (!scanned.isEmpty() || advanced != null) reset(world);
            ChunkFlagRenderer.clear(id()); return;
        }
        if (ticks++ % 20 == 0) {
            scanned.keySet().removeIf(pos -> !nearby(pos));
            notified.removeIf(pos -> !nearby(pos));
            queue.removeIf(pos -> !nearby(pos));
            if (!advancedMode.get()) {
                var loaded = ChunkScanHelper.loadedChunksAround(mc, radius.get());
                loaded.sort(Comparator.comparingInt(chunk -> distance(mc.player.chunkPosition(), chunk.getPos())));
                for (var chunk : loaded) if (!scanned.containsKey(chunk.getPos())) enqueue(chunk.getPos());
            }
        }
        long deadline = System.nanoTime() + 3_000_000L;
        if (advancedMode.get()) tickAdvanced(deadline);
        else {
            int completed = 0;
            while (completed < budget.get() && System.nanoTime() < deadline) {
                if (active == null) {
                    if (queue.isEmpty()) break;
                    activePos = queue.removeFirst();
                    if (!nearby(activePos)) continue;
                    sawBlob = false;
                    active = new StoneStackScanner(activePos, height.get(), this::sampleState);
                }
                if (!nearby(activePos)) { active = null; activePos = null; continue; }
                if (!active.step(deadline)) break;
                if (active.complete()) scanned.put(activePos, active.stacks().size());
                if (probing && sawBlob && probed.add(activePos) && probed.size() >= 48) {
                    probing = false; probed.clear();
                    FinderNotify.flag("[Chunk Finder]", "Enclosed blobs were not visible in 48 samples. Anti-xray may hide patched shafts here.", false);
                }
                active = null; activePos = null; completed++;
            }
        }
        var heat = StoneStackScanner.spreadHeat(scanned, mc.player.chunkPosition(), radius.get(), spread.get());
        heat.keySet().removeIf(pos -> !nearby(pos));
        flagged = spread.get() == 0 ? Set.copyOf(heat.keySet()) : StoneStackScanner.hottest(heat);
        for (ChunkPos pos : flagged) if (notified.add(pos) && notify.get())
            FinderNotify.flag("[Chunk Finder]", "Possible modified terrain at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), sound.get());
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private net.minecraft.world.level.block.state.BlockState sampleState(BlockPos pos) {
        var chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        var state = chunk.getBlockState(pos);
        if (probing && pos.getY() >= 0 && pos.getY() <= 32 && (state.is(net.minecraft.world.level.block.Blocks.DIORITE)
            || state.is(net.minecraft.world.level.block.Blocks.GRANITE) || state.is(net.minecraft.world.level.block.Blocks.ANDESITE)
            || state.is(net.minecraft.world.level.block.Blocks.GRAVEL))) {
            sawBlob = true;
            boolean enclosed = true;
            for (var side : SIDES) {
                var adjacent = pos.relative(side); var neighbor = world.getChunkSource().getChunkNow(adjacent.getX() >> 4, adjacent.getZ() >> 4);
                if (neighbor == null || !neighbor.getBlockState(adjacent).isSolidRender()) { enclosed = false; break; }
            }
            if (enclosed) { probing = false; probed.clear(); }
        }
        return state;
    }

    private void tickAdvanced(long deadline) {
        String text = seed.get().strip();
        if (!text.equals(seedText)) {
            seedText = text; seedStill = 0; seedAsked = false; scanned.clear(); notified.clear();
            if (advanced != null) { advanced.close(); closing = advanced; advanced = null; }
        }
        if (seedStill < 20) { seedStill++; return; }
        var wanted = WorldOptions.parseSeed(text);
        if (wanted.isEmpty()) {
            if (advanced != null) { advanced.close(); closing = advanced; advanced = null; }
            scanned.clear();
            if (!seedAsked) { FinderNotify.flag("[Chunk Finder]", "Seed-aware scanning needs the world seed; no prediction is being made.", false); seedAsked = true; }
            return;
        }
        if (advanced != null && advanced.seed() != wanted.getAsLong()) {
            advanced.close(); closing = advanced; advanced = null; scanned.clear();
        }
        if (advanced == null && closing == null) advanced = AdvancedScanner.start(wanted.getAsLong());
        if (advanced == null) return;
        for (var result : advanced.tick(world, deadline, Math.min(radius.get(), 8), budget.get())) {
            var pos = new ChunkPos(ChunkPos.getX(result.chunk()), ChunkPos.getZ(result.chunk()));
            if (nearby(pos)) scanned.put(pos, result.anchors().length);
        }
        if (!advanced.trusted()) scanned.clear();
        for (String notice : advanced.drainNotices()) FinderNotify.flag("[Chunk Finder]", notice, false);
    }

    // API v3 only has a module-wide share guard; the seed was a private value upstream.
    @Override public boolean settingsShareable() { return false; }
    @Override public String info() { return flagged.size() + (advancedMode.get() ? " seed candidates" : " stack candidates"); }
}
