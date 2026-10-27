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
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.palette.BlockEntityCapture;
import com.autism.seedcracker.spectre.palette.PaletteOverlay;
import com.autism.seedcracker.spectre.palette.SpawnerScan;
import com.autism.seedcracker.util.RelogPersistence;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Spectre SpawnerNotifier (d5a7dd3de0be), replacing the old finder without changing its id. */
public final class SpawnerFinderModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":z-spawner-finder";
    private enum Shape { OUTLINE, BOXES }
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 16, 1, 32, 1).group("Detection"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 16, 1).group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notifications", true).group("General"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Discovery sound", true).group("General"));
    private final BoolSetting render = add(new BoolSetting("render", "Render spawners", true).group("Render"));
    private final ColorSetting color = add(new ColorSetting("spawner-color", "Spawner color", -587246251).group("Render"));
    private final EnumSetting<Shape> shape = add(new EnumSetting<>("shape", "Shape", Shape.OUTLINE, Shape.values()).group("Render"));
    private final DoubleSetting boxSize = add(new DoubleSetting("box-size", "Box size", 1, 0.1, 2, 0.1).group("Render"));
    private final IntSetting fillAlpha = add(new IntSetting("fill-alpha", "Fill alpha (%)", 10, 0, 100, 5).group("Render"));
    private final IntSetting range = add(new IntSetting("range", "Render range", 256, 16, 2048, 16).group("Render"));
    private final BoolSetting fade = add(new BoolSetting("distance-fade", "Distance fade", true).group("Render"));
    private final BoolSetting reveal = add(new BoolSetting("reveal", "Reveal animation", true).group("Render"));
    private final BoolSetting chunks = add(new BoolSetting("chunk-markers", "Finder chunk markers", true).group("Finder overlay"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Finder tracers", false).group("Finder overlay"));
    private final SpawnerScan scan = new SpawnerScan();
    private final FinderReport reporter = new FinderReport("Spawner", 0);
    private final Map<BlockPos, Long> firstSeen = new HashMap<>();
    private Set<String> renderIds = Set.of();
    private ClientLevel level;
    private boolean unloadHook;
    private int spawnerCount;

    public SpawnerFinderModule() {
        super(ID, "Spawner Finder", "Spectre spawner notifications and block ESP. Natural spawners are not proof of a player base.");
    }

    @Override public void onEnable() {
        reset();
        BlockEspRenderer.init();
        ChunkFlagRenderer.init();
        BlockEntityCapture.init();
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
        scan.clear();
        firstSeen.clear();
        reporter.clear();
        level = null;
        spawnerCount = 0;
        clearRender();
    }
    private void clearRender() {
        for (String id : renderIds) BlockEspRenderer.clear(id);
        renderIds = Set.of();
        ChunkFlagRenderer.clear(ID);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) { reset(); level = mc.level; }
        if (level == null || mc.player == null) { clearRender(); return; }
        Set<BlockPos> detected = scan.tick(mc, scanRadius.get(), chunksPerTick.get());
        firstSeen.keySet().retainAll(detected);
        long now = System.nanoTime();
        List<BlockPos> fresh = new ArrayList<>();
        for (BlockPos pos : detected) if (firstSeen.putIfAbsent(pos, now) == null) fresh.add(pos);
        if (!fresh.isEmpty()) notify(mc, fresh);
        spawnerCount = detected.size();
        Set<ChunkPos> flagged = new HashSet<>();
        for (BlockPos pos : detected) flagged.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunks.get()) ChunkFlagRenderer.feed(ID, Set.copyOf(flagged), color.get(), tracer.get());
        else ChunkFlagRenderer.clear(ID);
        // No spawner/activation confidence floor: only independent chunk context enters Base Scout.
        reporter.tick(mc, flagged);
        render(mc, detected, now);
    }

    private void notify(Minecraft mc, List<BlockPos> fresh) {
        fresh.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())));
        if (notify.get()) {
            for (int i = 0; i < Math.min(3, fresh.size()); i++) {
                BlockPos pos = fresh.get(i);
                String mob = BlockEntityCapture.mobName(BlockEntityCapture.spawnerData(level, pos));
                String text = (mob == null ? "Unknown" : mob) + " spawner at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " (" + Math.round(Math.sqrt(pos.distToCenterSqr(mc.player.position()))) + "m)";
                ClientNotify.success(text);
                AutismClientMessaging.sendPrefixed("§c[SpawnerFinder] §f" + text);
            }
            if (fresh.size() > 3) AutismClientMessaging.sendPrefixed("§c[SpawnerFinder] §f" + (fresh.size() - 3) + " additional spawners found.");
        }
        if (sound.get()) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1, 1);
    }

    private void render(Minecraft mc, Set<BlockPos> detected, long now) {
        Set<String> next = new HashSet<>();
        if (render.get()) {
            Map<Integer, List<AABB>> groups = new HashMap<>();
            for (BlockPos pos : detected.stream().sorted(Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.position()))).limit(1024).toList()) {
                double distance = Math.sqrt(pos.distToCenterSqr(mc.player.position()));
                if (distance > range.get()) continue;
                float alpha = fade.get() ? (float) Math.clamp((range.get() - distance) / (range.get() * 0.125), 0, 1) : 1;
                if (reveal.get()) alpha *= (float) Math.min(1, (now - firstSeen.get(pos)) / 600_000_000.0);
                int bucket = Math.round(alpha * 15);
                if (bucket == 0) continue;
                Vec3 center = Vec3.atCenterOf(pos);
                double half = boxSize.get() / 2;
                groups.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(new AABB(center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half));
            }
            for (var group : groups.entrySet()) {
                String id = ID + ":blocks:" + group.getKey();
                next.add(id);
                float alpha = group.getKey() / 15f;
                BlockEspRenderer.feedBoxes(id, group.getValue(), PaletteOverlay.withAlpha(color.get(), alpha),
                    shape.get() == Shape.BOXES ? Math.round(fillAlpha.get() * 2.55f * alpha) : 0);
            }
        }
        for (String id : renderIds) if (!next.contains(id)) BlockEspRenderer.clear(id);
        renderIds = next;
    }

    @Override public String info() { return spawnerCount == 0 ? "" : spawnerCount + " spawners"; }
}
