package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderSensitivity;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Light Source Finder (nyx LightFinder / Zelith LightESP port).
 *
 * Bases are lit: players place torches, lanterns and glowstone everywhere they work. This scans
 * loaded chunks for LIGHT-EMITTING blocks below a Y gate and flags chunks with enough of them.
 * Underground light sources are near-exclusively player-placed (natural emitters - lava, glow
 * lichen, amethyst, sea pickles, magma - are filtered), so even a hidden base leaks its torches.
 *
 * Budgeted scan (chunks-per-tick) with per-section all-air skipping, like the other finders.
 */
public final class LightSourceFinderModule extends Module {

    private final EnumSetting<FinderSensitivity> sensitivity = add(new EnumSetting<>(
            "sensitivity", "Sensitivity", FinderSensitivity.HIGH, FinderSensitivity.values())
        .description("HIGH = half the light-count threshold (more finds). LOW = double (only heavily lit areas).")
        .group("General"));
    private final IntSetting yLevel = add(new IntSetting("y-level", "Max Y level", 40, -64, 320, 1)
        .description("Only count light sources at or below this Y (underground light = player light).")
        .group("General"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Light count", 2, 1, 64, 1)
        .description("Player-type light sources in a chunk needed to flag it.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 6, 1, 12, 1)
        .description("Chunk bubble around the player scanned.")
        .group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 16, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.")
        .group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Toast + chat ping on a new lit chunk.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x8CFFE080)
        .description("Colour of lit-chunk markers.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false)
        .description("Tracer line to each lit chunk.")
        .group("Render"));

    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();

    public LightSourceFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":light-finder", "Light Source Finder", category,
            "Flags chunks with player-placed light sources below a Y level - bases are lit.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        scanCursor.reset();
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        ChunkPos centre = mc.player.chunkPosition();
        int radius = scanRadius.get();
        int need = sensitivity.get().scale(threshold.get());

        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 1500, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (countPlayerLights(chunk, yLevel.get(), need) >= need) {
                flagged.add(pos);
                if (notified.add(pos)) onNewFlag(pos);
            } else {
                flagged.remove(pos);
            }
        }

        int pr = radius + 2;
        flagged.removeIf(p -> tooFar(p, centre, pr));
        notified.removeIf(p -> tooFar(p, centre, pr));
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
    }

    /** Count player-type light emitters at or below yGate, early-out at {@code enough}. */
    private int countPlayerLights(LevelChunk chunk, int yGate, int enough) {
        int count = 0;
        int minY = chunk.getMinY();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int sy = minY; sy <= yGate; sy += 16) {
            int sectionIdx = chunk.getSectionIndex(sy);
            if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) continue;
            LevelChunkSection sec = chunk.getSection(sectionIdx);
            if (sec.hasOnlyAir()) continue;
            int yLo = Math.max(sy, minY);
            int yHi = Math.min(sy + 15, yGate);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yLo; y <= yHi; y++) {
                        m.set(startX + x, y, startZ + z);
                        BlockState state = chunk.getBlockState(m);
                        if (state.getLightEmission() <= 0) continue;
                        if (isNaturalEmitter(state)) continue;
                        if (++count >= enough) return count;
                    }
                }
            }
        }
        return count;
    }

    /** Natural light emitters that generate underground - never evidence of a player. */
    private static boolean isNaturalEmitter(BlockState s) {
        return !s.getFluidState().isEmpty() // lava (fluid state catches flowing)
            || s.is(Blocks.LAVA) || s.is(Blocks.MAGMA_BLOCK)
            || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.SEA_PICKLE)
            || s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD)
            || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.SMALL_AMETHYST_BUD)
            || s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)
            || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.CRYING_OBSIDIAN)
            || s.is(Blocks.ENCHANTING_TABLE) // emits weak light but flagged by other finders anyway
            || s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE)
            || s.is(Blocks.GLOWSTONE) && dimensionIsNether(); // natural glowstone in the nether only
    }

    private static boolean dimensionIsNether() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Lit chunk at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ() + " (player lights below Y" + yLevel.get() + ")";
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§e[LightFinder] §f" + msg);
    }

    @Override
    public String info() {
        return flagged.size() + " lit";
    }
}
