package com.autism.seedcracker.finder;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Shared base-confidence scoring for the finder modules.
 *
 * A single chunk can look "sus" for many reasons (a natural mineshaft, a geode, one chest in a
 * ruin). This helper turns several weak signals into one 0-100 confidence that a chunk is a
 * real player base, so finders can:
 *   - suppress low-confidence false positives instead of flagging everything,
 *   - show the user *why* a chunk was flagged (premium feedback),
 *   - agree on a common notion of "base" across Stash / Sus / Activity / Growth finders.
 *
 * Signals (weighted): storage block entities, player-placed light sources, workstations,
 * redstone/mechanism blocks, and artificial building blocks. Pure natural features (ores,
 * stone, deepslate, natural fluid) reduce confidence.
 */
public final class BaseConfidence {

    private BaseConfidence() {}

    public record Result(int score, String topReason) {}

    /** Returns a heuristic evidence score, not a calibrated probability of a player base. */
    public static Result score(LevelChunk chunk) {
        if (chunk == null) return new Result(0, "");
        return scoreSections(chunk.getSections());
    }

    static Result scoreSections(LevelChunkSection[] sections) {
        int storage = 0, lights = 0, workstations = 0, mechanisms = 0, artificial = 0, natural = 0;
        var counts = new java.util.HashMap<Block, Integer>();
        for (LevelChunkSection sec : sections) {
            if (sec == null || sec.hasOnlyAir()) continue;
            sec.getStates().count((state, count) -> counts.merge(state.getBlock(), count, Integer::sum));
        }
        for (var entry : counts.entrySet()) {
            Block block = entry.getKey();
            int count = entry.getValue();
            if (isStorage(block)) storage += count;
            else if (isWorkstation(block)) workstations += count;
            else if (isLight(block)) lights += count;
            else if (isMechanism(block)) mechanisms += count;
            else if (isArtificial(block)) artificial += count;
            else if (isNatural(block)) natural += count;
        }

        int score = 0;
        String reason = "";
        score += Math.min(50, storage * 8);
        if (storage > 0) reason = storage + " storage";
        int ws = Math.min(20, workstations * 5);
        score += ws;
        if (workstations >= 2 && reason.isEmpty()) reason = workstations + " workstations";
        int li = Math.min(15, lights * 3);
        score += li;
        int mech = Math.min(10, mechanisms * 2);
        score += mech;
        int art = Math.min(15, artificial / 8);
        score += art;
        if (artificial >= 24 && reason.isEmpty()) reason = "built structure";
        // Heavy natural content (a cave/geode) lowers confidence this is a base.
        if (natural > 3000 && storage == 0 && lights == 0) score -= 15;

        if (reason.isEmpty() && storage > 0) reason = storage + " storage";
        if (reason.isEmpty() && lights >= 3) reason = lights + " light sources";
        return new Result(Math.max(0, Math.min(100, score)), reason);
    }

    private static boolean isStorage(Block b) {
        return b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.TRAPPED_CHEST
            || b == Blocks.ENDER_CHEST || b == Blocks.HOPPER || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
            || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.DISPENSER || b == Blocks.DROPPER;
    }

    private static boolean isWorkstation(Block b) {
        return b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE || b instanceof net.minecraft.world.level.block.AnvilBlock
            || b == Blocks.BREWING_STAND || b == Blocks.GRINDSTONE || b == Blocks.SMITHING_TABLE
            || b == Blocks.LOOM || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.STONECUTTER
            || b == Blocks.FLETCHING_TABLE;
    }

    private static boolean isLight(Block b) {
        return b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.SOUL_TORCH
            || b == Blocks.SOUL_WALL_TORCH || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT || b == Blocks.REDSTONE_LAMP
            || b == Blocks.SEA_LANTERN || b == Blocks.JACK_O_LANTERN || b == Blocks.CAMPFIRE
            || b == Blocks.SOUL_CAMPFIRE;
    }

    private static boolean isMechanism(Block b) {
        return b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.OBSERVER
            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.REDSTONE_WIRE
            || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_BLOCK || b == Blocks.RAIL
            || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    private static boolean isArtificial(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.STONE_BRICKS || b == Blocks.BRICKS
            || b == Blocks.SMOOTH_STONE || b == Blocks.POLISHED_DEEPSLATE || b == Blocks.DEEPSLATE_BRICKS
            || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.BIRCH_PLANKS
            || b == Blocks.GLASS;
    }

    private static boolean isNatural(Block b) {
        return b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.DIRT || b == Blocks.GRASS_BLOCK
            || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.NETHERRACK;
    }
}
