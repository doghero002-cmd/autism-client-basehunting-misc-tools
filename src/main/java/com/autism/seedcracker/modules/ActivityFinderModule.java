package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Activity Finder.
 *
 * A y-level-gated activity detector. The Zelith original listened to network packets, pulled block
 * positions out of them via reflection and flagged chunks below a configurable Y level. This
 * clean-room port keeps the essence - "flag chunks showing block-entity activity at or below a Y
 * cut-off" - but reads it from loaded chunk data instead of packet reflection: any chunk with one
 * or more block entities (chests, furnaces, hoppers, spawners, beehives, ... - i.e. signs of a
 * worked area) at or below the configured Y level is flagged.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class ActivityFinderModule extends Module {

    // ---- settings ----
    /** Which block entities count as activity. */
    public enum Mode {
        /** Any block entity (original). */
        ALL,
        /** Storage only: chests/barrels/shulkers/hoppers (loot rooms). */
        STORAGE,
        /** Work stations: furnaces/brewing/enchanting/anvil area blocks (active base). */
        UTILITY,
        /** Redstone machinery: hoppers/dispensers/droppers/comparators (farms & vaults). */
        REDSTONE
    }

    private final autismclient.api.module.EnumSetting<Mode> mode = add(
        new autismclient.api.module.EnumSetting<>("mode", "Mode", Mode.STORAGE, Mode.values())
        .description("ALL = any block entity. STORAGE = chests/shulkers (loot). UTILITY = furnaces/brewing (lived-in). REDSTONE = hoppers/dispensers (farms/vaults).")
        .group("General"));
    private final autismclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new autismclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH/MEDIUM = one block entity below Y flags. LOW = need 2+ (a single lone chest can be a dungeon).")
        .group("General"));
    private final IntSetting yLevel = add(new IntSetting(
            "y-level", "Y level", 32, -64, 320, 1)
        .description("Only flag chunks whose block-entity activity is at or below this Y level.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for activity.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", false)
        .description("Toast + chat ping when activity is found.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xB4FFDC00)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.")
        .group("Performance"));

    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public ActivityFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-activity-finder", "Activity Finder", category,
            "Flags chunks with block-entity activity at or below a Y level - signs of a worked area.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        // Mode swap: re-judge every chunk under the new filter immediately.
        if ("mode".equals(settingId) || "sensitivity".equals(settingId) || "y-level".equals(settingId)) {
            flagged.clear();
            notified.clear();
        }
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-activity-finder");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        scan(mc);
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-activity-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();
        int yGate = yLevel.get();

        int need = sensitivity.get().scale(1); // HIGH/MEDIUM 1, LOW 2
        Mode m = mode.get();
        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 400, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            boolean active = countActivityAtOrBelow(chunk, yGate, need, m) >= need;
            if (active) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos);
                }
            } else {
                flagged.remove(pos);
            }
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** Count mode-matching block entities at or below {@code yGate}, stopping early at {@code enough}. */
    private static int countActivityAtOrBelow(LevelChunk chunk, int yGate, int enough, Mode mode) {
        int n = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            BlockPos p = be.getBlockPos();
            if (p == null || p.getY() > yGate) continue;
            if (!matchesMode(be, mode)) continue;
            if (++n >= enough) return n;
        }
        return n;
    }

    private static boolean matchesMode(BlockEntity be, Mode mode) {
        if (mode == Mode.ALL) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
            .getKey(be.getType()).getPath();
        return switch (mode) {
            case STORAGE -> id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
                || id.equals("shulker_box") || id.equals("ender_chest") || id.equals("hopper");
            case UTILITY -> id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")
                || id.equals("brewing_stand") || id.equals("enchanting_table") || id.equals("beacon")
                || id.equals("campfire") || id.equals("lectern") || id.equals("crafter");
            case REDSTONE -> id.equals("hopper") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("comparator") || id.equals("piston") || id.equals("daylight_detector")
                || id.equals("sculk_sensor") || id.equals("calibrated_sculk_sensor");
            default -> true;
        };
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Activity detected in chunk " + pos.x() + ", " + pos.z() + " (Y<=" + yLevel.get() + ")";
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§e[ActivityFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
