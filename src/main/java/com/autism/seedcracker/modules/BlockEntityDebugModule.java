// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.palette.BlockEntityCapture;
import com.autism.seedcracker.spectre.palette.BlockEntityLeaks;
import com.autism.seedcracker.spectre.palette.PaletteOverlay;
import com.autism.seedcracker.spectre.palette.SpawnerScan;
import com.autism.seedcracker.util.RelogPersistence;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.RegistryListSetting;
import autismclient.api.module.StringListSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Spectre BlockEntityDebug (d5a7dd3de0be); retains raw evidence instead of cancelling vanilla updates. */
public final class BlockEntityDebugModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":block-entity-debug";
    private enum Detection { ALL, BELOW_ZERO, BELOW_20, BELOW_30 }
    private enum Shape { OUTLINE, BOXES }
    private final EnumSetting<Detection> detection = add(new EnumSetting<>("detection", "Detection", Detection.ALL, Detection.values(),
        value -> switch (value) { case ALL -> "All"; case BELOW_ZERO -> "Below zero"; case BELOW_20 -> "Below -20"; case BELOW_30 -> "Below -30"; }).group("Detection"));
    private final BoolSetting blockFilter = add(new BoolSetting("block-filter", "Block filter", false).group("Detection"));
    private final RegistryListSetting blocks = add(RegistryListSetting.blocks("blocks", "Blocks", defaultBlocks()).group("Detection").visibleWhen(blockFilter::get));
    private final BoolSetting tracers = add(new BoolSetting("tracers", "Tracers", true).group("Render"));
    private final BoolSetting customTracer = add(new BoolSetting("custom-tracer-color", "Custom tracer color", false).group("Render").visibleWhen(tracers::get));
    private final ColorSetting tracerColor = add(new ColorSetting("tracer-color", "Tracer color", 0xffffffff).group("Render").visibleWhen(() -> tracers.get() && customTracer.get()));
    private final StringListSetting colors = add(new StringListSetting("block-colors", "Block color overrides", List.of())
        .description("One block-id=#AARRGGBB per entry; otherwise uses Spectre's block-specific colors.").group("Render"));
    private final EnumSetting<Shape> shape = add(new EnumSetting<>("shape", "Shape", Shape.BOXES, Shape.values()).group("Render"));
    private final DoubleSetting boxSize = add(new DoubleSetting("box-size", "Box size", 1, 0.1, 2, 0.1).group("Render"));
    private final IntSetting fillAlpha = add(new IntSetting("fill-alpha", "Fill alpha (%)", 80, 0, 100, 5).group("Render"));
    private final IntSetting range = add(new IntSetting("range", "Range", 256, 16, 2048, 16).group("Render"));
    private final BoolSetting fade = add(new BoolSetting("distance-fade", "Distance fade", true).group("Render"));
    private final BoolSetting reveal = add(new BoolSetting("reveal", "Reveal animation", true).group("Render"));
    private final BoolSetting beams = add(new BoolSetting("spawner-beams", "Spawner beams", true).group("Render"));
    private final IntSetting maxRendered = add(new IntSetting("max-rendered", "Maximum rendered entities", 1024, 64, 4096, 64).group("Performance"));
    private final FinderReport reporter = new FinderReport("Block Entity", 0);
    private final Map<BlockPos, Long> firstSeen = new HashMap<>();
    private Set<String> renderIds = Set.of();
    private List<String> lastColors = List.of();
    private Map<String, Integer> overrides = Map.of();
    private ClientLevel level;
    private boolean unloadHook;
    private int count;

    public BlockEntityDebugModule() {
        super(ID, "Block Entity Debug", "Spectre raw block-entity ESP. Packet evidence can reveal hidden entities, not prove a player base.");
    }

    private static String defaultBlocks() {
        List<String> ids = new ArrayList<>();
        for (var block : BuiltInRegistries.BLOCK) if (block instanceof EntityBlock) ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        return String.join(",", ids);
    }

    @Override public void onEnable() {
        reset();
        BlockEspRenderer.init();
        BlockEntityCapture.init();
        PaletteOverlay.init();
        if (!unloadHook) {
            unloadHook = true;
            ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (world != level) return;
                firstSeen.keySet().removeIf(pos -> (pos.getX() >> 4) == chunk.getPos().x() && (pos.getZ() >> 4) == chunk.getPos().z());
                clearRender();
                reporter.clear();
            });
        }
    }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    private void reset() {
        firstSeen.clear();
        reporter.clear();
        level = null;
        count = 0;
        clearRender();
    }
    private void clearRender() {
        for (String id : renderIds) BlockEspRenderer.clear(id);
        renderIds = Set.of();
        PaletteOverlay.clear(ID);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) { reset(); level = mc.level; }
        if (level == null || mc.player == null) { clearRender(); return; }
        updateColors();
        Set<String> selected = new HashSet<>(blocks.get());
        var entries = BlockEntityCapture.snapshot(level);
        Set<BlockPos> live = new HashSet<>();
        for (var entry : entries) live.add(BlockEntityCapture.blockPos(entry));
        firstSeen.keySet().retainAll(live);
        long now = System.nanoTime();
        for (BlockPos pos : live) firstSeen.putIfAbsent(pos, now);
        List<BlockEntityLeaks.Entry> shown = entries.stream().filter(entry -> passes(entry, selected, mc))
            .sorted(Comparator.comparingDouble(entry -> BlockEntityCapture.blockPos(entry).distToCenterSqr(mc.player.position())))
            .limit(maxRendered.get()).toList();
        count = shown.size();
        Map<Integer, List<AABB>> groups = new HashMap<>();
        Map<Integer, List<AABB>> beamGroups = new HashMap<>();
        List<PaletteOverlay.Line> lines = new ArrayList<>();
        Set<ChunkPos> flagged = new HashSet<>();
        for (var entry : shown) {
            BlockPos pos = BlockEntityCapture.blockPos(entry);
            flagged.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
            double distance = Math.sqrt(pos.distToCenterSqr(mc.player.position()));
            float alpha = fade.get() ? (float) Math.clamp((range.get() - distance) / (range.get() * 0.125), 0, 1) : 1;
            if (reveal.get()) alpha *= (float) Math.min(1, (now - firstSeen.get(pos)) / 600_000_000.0);
            alpha = Math.round(alpha * 7) / 7f;
            if (alpha <= 0) continue;
            int own = overrides.getOrDefault(entry.blockId(), BlockEntityLeaks.color(entry.blockId()));
            int tint = PaletteOverlay.withAlpha(own, alpha);
            Vec3 center = Vec3.atCenterOf(pos);
            double half = boxSize.get() / 2;
            groups.computeIfAbsent(tint, ignored -> new ArrayList<>()).add(new AABB(center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half));
            if (tracers.get()) lines.add(new PaletteOverlay.Line(null, center,
                PaletteOverlay.withAlpha(customTracer.get() ? tracerColor.get() : own, alpha), 1.5f));
            if (beams.get() && entry.spawner() != null) beamGroups.computeIfAbsent(tint, ignored -> new ArrayList<>())
                .add(new AABB(pos.getX() + 0.3, pos.getY() + 1, pos.getZ() + 0.3, pos.getX() + 0.7,
                    pos.getY() + 257, pos.getZ() + 0.7));
        }
        Set<String> next = new HashSet<>();
        feed(next, groups, ":blocks:", shape.get() == Shape.BOXES ? fillAlpha.get() : 0);
        feed(next, beamGroups, ":beams:", 0);
        for (String id : renderIds) if (!next.contains(id)) BlockEspRenderer.clear(id);
        renderIds = next;
        PaletteOverlay.feed(ID, level, lines, List.of());
        reporter.tick(mc, flagged);
    }

    private boolean passes(BlockEntityLeaks.Entry entry, Set<String> selected, Minecraft mc) {
        int maxY = switch (detection.get()) { case ALL -> Integer.MAX_VALUE; case BELOW_ZERO -> 0; case BELOW_20 -> -20; case BELOW_30 -> -30; };
        BlockPos pos = BlockEntityCapture.blockPos(entry);
        if (pos.getY() >= maxY || !SpawnerScan.loaded(level, pos) || pos.distToCenterSqr(mc.player.position()) > (double) range.get() * range.get()) return false;
        Identifier block = Identifier.tryParse(entry.blockId());
        boolean unknown = block == null || BuiltInRegistries.BLOCK.getOptional(block).isEmpty();
        return !blockFilter.get() || unknown || selected.contains(entry.blockId());
    }

    private static void feed(Set<String> ids, Map<Integer, List<AABB>> groups, String suffix, int fillPercent) {
        for (var group : groups.entrySet()) {
            String id = ID + suffix + Integer.toUnsignedString(group.getKey());
            ids.add(id);
            BlockEspRenderer.feedBoxes(id, group.getValue(), group.getKey(), Math.round(fillPercent / 100f * (group.getKey() >>> 24)));
        }
    }

    private void updateColors() {
        if (lastColors.equals(colors.get())) return;
        lastColors = List.copyOf(colors.get());
        Map<String, Integer> parsed = new HashMap<>();
        for (String value : lastColors.stream().limit(128).toList()) {
            if (value.length() > 280) continue;
            String[] parts = value.split("=", 2);
            if (parts.length != 2) continue;
            Identifier id = Identifier.tryParse(parts[0].trim());
            String hex = parts[1].trim().replaceFirst("^#", "");
            if (id == null || !hex.matches("[0-9a-fA-F]{8}")) continue;
            parsed.put(id.toString(), (int) Long.parseLong(hex, 16));
        }
        overrides = Map.copyOf(parsed);
    }

    @Override public String info() { return count == 0 ? "" : count + " entities"; }
}
