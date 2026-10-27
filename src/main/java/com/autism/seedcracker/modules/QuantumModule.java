// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre QuantumModule, d5a7dd3 (Cody / cody-raves); GoNuts portions retain their MIT notice.
package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.spectre.activity.ActivityRender;
import com.autism.seedcracker.spectre.activity.ChunkSnapshotStore;
import com.autism.seedcracker.spectre.activity.QuantumRules;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;

public final class QuantumModule extends Module {
    private final BoolSetting advancedDebug = add(new BoolSetting("advanced-debug", "Advanced debug", false).group("Detection"));
    private final BoolSetting render = add(new BoolSetting("render", "Render debug evidence", true).group("Render"));
    private final ColorSetting fillColor = add(new ColorSetting("side-color", "Fill colour", 0x28ff2828)
        .group("Render").visibleWhen(() -> render.get() && advancedDebug.get()));
    private final ColorSetting lineColor = add(new ColorSetting("line-color", "Line colour", 0xffff2828)
        .group("Render").visibleWhen(() -> render.get() && advancedDebug.get()));
    private final BoolSetting notifications = add(new BoolSetting("notification", "Notifications", false).group("Alerts"));
    private final ChunkSnapshotStore snapshots = new ChunkSnapshotStore(Blocks.AIR.defaultBlockState());
    private final LinkedHashSet<Long> justSent = new LinkedHashSet<>();
    private final LinkedHashSet<BlockPos> changedBlocks = new LinkedHashSet<>();
    private final LinkedHashSet<Long> flaggedChunks = new LinkedHashSet<>();
    private final LinkedHashSet<Long> sourceChunks = new LinkedHashSet<>();
    private final ArrayDeque<Long> baselines = new ArrayDeque<>();
    private final FinderReport reporter = new FinderReport("Quantum", 40);
    private Set<Long> range = Set.of();
    private ChunkPos lastPos;
    private ClientLevel world;
    private ClientPacketListener connection;

    public QuantumModule() {
        super(SeedcrackerAddon.ID + ":quantum", "Quantum",
            "Finds unexplained changes in resent chunks. Activity evidence, not proof of a player or base.");
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private void reset() {
        snapshots.clear();
        justSent.clear();
        changedBlocks.clear();
        clearRenderFlags();
        baselines.clear();
        range = Set.of();
        lastPos = null;
        world = null;
        connection = null;
        reporter.clear();
        ActivityRender.clear(id() + ":changes");
        ActivityRender.clear(id() + ":resent");
    }

    private boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return false;
        if (world != mc.level || connection != mc.getConnection()) {
            reset();
            world = mc.level;
            connection = mc.getConnection();
        }
        return isEnabled() && world != null && mc.player != null
            && (world.dimension() == Level.OVERWORLD || world.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD));
    }

    @Override public void tick() {
        if (!ready()) return;
        Minecraft mc = Minecraft.getInstance();
        ChunkPos center = mc.player.chunkPosition();
        if (!center.equals(lastPos)) {
            range = QuantumRules.scanRange(center.x(), center.z());
            baselines.clear();
            for (long key : range) if (!snapshots.contains(key) && loaded(key)) baselines.addLast(key);
            lastPos = center;
        }
        snapshots.retain(key -> range.contains(key) && loaded(key));
        justSent.removeIf(key -> !range.contains(key) || !loaded(key));
        sourceChunks.removeIf(key -> !loaded(key));
        changedBlocks.removeIf(pos -> !world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4));
        // ponytail: seed at most eight existing chunks/tick; an unseeded resend establishes a baseline, never a detection.
        for (int count = 0; count < 8 && !baselines.isEmpty(); count++) {
            long key = baselines.removeFirst();
            if (!snapshots.contains(key) && loaded(key)) {
                snapshots.store(QuantumRules.x(key), QuantumRules.z(key), extractSections(world.getChunk(QuantumRules.x(key), QuantumRules.z(key))));
            }
        }
        if (render.get() && advancedDebug.get()) {
            ActivityRender.feed(id() + ":changes", changedBlocks.stream().map(AABB::new).toList(), lineColor.get(), fillColor.get());
            ActivityRender.feed(id() + ":resent", justSent.stream().map(key -> marker(new ChunkPos(QuantumRules.x(key), QuantumRules.z(key)))).toList(),
                0xff00ff00, 0x2800ff00);
        } else {
            ActivityRender.clear(id() + ":changes");
            ActivityRender.clear(id() + ":resent");
        }
        reporter.tick(mc, sourceChunks());
    }

    /** Dispatch at ClientPacketListener TAIL on the client thread, not from raw Netty receive. */
    public void onPacketApplied(Packet<?> packet) {
        if (!ready()) return;
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            onChunkUnload(forget.pos());
        } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            onBlockUpdate(update.getPos(), update.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates(this::onBlockUpdate);
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            Minecraft mc = Minecraft.getInstance();
            ChunkPos center = mc.player.chunkPosition();
            int x = chunk.getX(), z = chunk.getZ();
            if (!QuantumRules.inScanRange(x, z, center.x(), center.z()) || !world.getChunkSource().hasChunk(x, z)) return;
            PalettedContainer<BlockState>[] current = extractSections(world.getChunk(x, z));
            ChunkSnapshotStore.BlockChange change = snapshots.firstChange(x, z, current, world.getMinSectionY(),
                (oldState, newState) -> !QuantumRules.ignoredChange(
                    BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).toString(),
                    BuiltInRegistries.BLOCK.getKey(newState.getBlock()).toString()));
            if (change != null) onActivity(new ChunkPos(x, z), change);
            justSent.add(QuantumRules.key(x, z));
            while (justSent.size() > 289) justSent.removeFirst();
            snapshots.store(x, z, current);
        }
    }

    /** Also dispatch ClientLevel.sendBlockUpdated so ordinary local/packet changes update the baseline. */
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!ready() || pos == null || state == null) return;
        snapshots.update(pos.getX() >> 4, pos.getZ() >> 4, (pos.getY() >> 4) - world.getMinSectionY(),
            pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }

    public void onChunkUnload(ChunkPos pos) {
        if (!ready() || pos == null) return;
        long key = QuantumRules.key(pos.x(), pos.z());
        snapshots.remove(key);
        justSent.remove(key);
        sourceChunks.remove(key);
        flaggedChunks.remove(key);
        baselines.remove(key);
        changedBlocks.removeIf(block -> (block.getX() >> 4) == pos.x() && (block.getZ() >> 4) == pos.z());
        reporter.clear();
        ActivityRender.clear(id() + ":changes");
        ActivityRender.clear(id() + ":resent");
        clearDisplay();
    }

    private void onActivity(ChunkPos pos, ChunkSnapshotStore.BlockChange change) {
        changedBlocks.add(new BlockPos(change.worldX(), change.worldY(), change.worldZ()));
        while (changedBlocks.size() > 2048) changedBlocks.removeFirst();
        addSourceChunk(pos);
        Set<Long> narrowed = QuantumRules.narrow(flaggedChunks, pos.x(), pos.z());
        flaggedChunks.clear();
        flaggedChunks.addAll(narrowed);
        if (notifications.get()) {
            String detail = advancedDebug.get() ? ": " + change.oldState().getBlock().getName().getString()
                + " -> " + change.newState().getBlock().getName().getString() : "";
            FinderNotify.flag("[Quantum]", "Resent-chunk activity signal at chunk " + pos.x() + ", " + pos.z() + detail, false);
        }
    }

    private boolean loaded(long key) { return world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)); }

    public Set<ChunkPos> flaggedChunks() {
        return ready() ? positions(flaggedChunks) : Set.of();
    }
    public Set<ChunkPos> sourceChunks() {
        return ready() ? positions(sourceChunks) : Set.of();
    }
    public void addFlaggedChunks(Collection<ChunkPos> chunks) {
        if (!ready() || chunks == null) return;
        for (ChunkPos pos : chunks) if (pos != null) {
            flaggedChunks.add(QuantumRules.key(pos.x(), pos.z()));
            while (flaggedChunks.size() > 361) flaggedChunks.removeFirst();
        }
    }
    public void clearFlaggedChunks() { flaggedChunks.clear(); clearDisplay(); }
    public void addSourceChunk(ChunkPos pos) {
        if (!ready() || pos == null) return;
        sourceChunks.add(QuantumRules.key(pos.x(), pos.z()));
        while (sourceChunks.size() > 1024) sourceChunks.removeFirst();
    }
    public void clearRenderFlags() { flaggedChunks.clear(); sourceChunks.clear(); clearDisplay(); }

    private void clearDisplay() {
        if (ModuleLookup.get(SeedcrackerAddon.ID + ":render-util") instanceof RenderUtilModule display) display.clearRender();
    }
    private static Set<ChunkPos> positions(Set<Long> keys) {
        return keys.stream().map(key -> new ChunkPos(QuantumRules.x(key), QuantumRules.z(key))).collect(Collectors.toUnmodifiableSet());
    }
    static AABB marker(ChunkPos pos) {
        double x = pos.getMinBlockX(), z = pos.getMinBlockZ();
        return new AABB(x, 63, z, x + 16, 63, z + 16);
    }
    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] extractSections(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        PalettedContainer<BlockState>[] out = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[sections.length];
        for (int index = ChunkSnapshotStore.MIN_SECTION_INDEX; index <= ChunkSnapshotStore.MAX_SECTION_INDEX && index < sections.length; index++) {
            if (sections[index] != null && !sections[index].hasOnlyAir()) out[index] = sections[index].getStates();
        }
        return out;
    }
    @Override public String info() { return sourceChunks.size() + " signals / " + flaggedChunks.size() + " candidate chunks"; }
}
