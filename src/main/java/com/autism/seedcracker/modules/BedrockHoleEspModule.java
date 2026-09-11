package com.autism.seedcracker.modules;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/**
 * Bedrock Hole ESP.
 *
 * Finds holes in the bedrock floor (gaps where the bedrock layer is missing, fully surrounded by
 * bedrock) and highlights them - useful for finding spots to drop through the nether roof / void.
 *
 * Clean-room port of the obfuscated Zelith "BedrockHoleESP" module against the AUTISM module API.
 * The original flood-fills air pockets bounded by bedrock; this port keeps that flood-fill
 * detection but spreads the volume scan across ticks and renders via the shared block ESP
 * renderer.
 */
public final class BedrockHoleEspModule extends Module {

    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFE11919)
        .description("Colour of the hole markers.").group("Render"));
    private final IntSetting minHole = add(new IntSetting("min-hole", "Min hole size", 2, 1, 12, 1)
        .description("Minimum connected air blocks (bounded by bedrock) to count as a hole.")
        .group("General"));
    private final IntSetting range = add(new IntSetting("range", "Range", 48, 16, 128, 8)
        .description("Horizontal scan radius around the player.")
        .group("General"));
    private final BoolSetting fill = add(new BoolSetting("fill", "Fill boxes", true)
        .description("Translucent fill on the hole blocks.").group("Render"));

    private final Set<BlockPos> holes = new HashSet<>();
    private final Set<BlockPos> scannedThisPass = new HashSet<>();
    private final Set<BlockPos> visited = new HashSet<>();

    // Volume-scan state (spreads the scan over ~1 second of ticks).
    private boolean scanning = false;
    private int tickCounter = 0;
    private int scanIndex = 0;
    private int rangeX, rangeY, totalVolume;
    private BlockPos origin;

    public BedrockHoleEspModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":bedrock-hole-esp", "Bedrock Hole ESP", category,
            "Highlights holes in the bedrock floor (gaps bounded by bedrock).");
    }

    @Override
    public void onEnable() {
        BlockEspRenderer.init();
        holes.clear();
        scanning = false;
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        holes.clear();
        scanning = false;
        BlockEspRenderer.clear(SeedcrackerAddon.ID + ":bedrock-hole-esp");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Start a fresh scan pass every 20 ticks.
        tickCounter++;
        if (!scanning && tickCounter >= 20) {
            tickCounter = 0;
            rangeX = range.get();
            rangeY = Math.max(range.get() / 2, 8);
            origin = mc.player.blockPosition();
            int sideX = rangeX * 2 + 1;
            int sideY = rangeY * 2 + 1;
            totalVolume = sideX * sideY * sideX;
            scanIndex = 0;
            scannedThisPass.clear();
            visited.clear();
            scanning = true;
        }

        if (scanning) {
            int sideX = rangeX * 2 + 1;
            int sideY = rangeY * 2 + 1;
            int processed = 0;
            int minHoleSize = minHole.get();
            while (scanIndex < totalVolume && processed < 32768) {
                int idx = scanIndex++;
                int dy = idx / sideX % sideY;
                int dx = idx % sideX;
                int dz = idx / (sideX * sideY);
                BlockPos pos = new BlockPos(
                    origin.getX() + dx - rangeX,
                    origin.getY() + dy - rangeY,
                    origin.getZ() + dz - rangeX);
                if (isAir(mc, pos) && !visited.contains(pos) && isBedrockBoundedSeed(mc, pos)) {
                    Set<BlockPos> cluster = floodFill(mc, pos, visited);
                    if (cluster.size() >= minHoleSize && isFullyBedrockBounded(mc, cluster)) {
                        scannedThisPass.addAll(cluster);
                    }
                }
                processed++;
            }
            if (scanIndex >= totalVolume) {
                holes.clear();
                holes.addAll(scannedThisPass);
                scanning = false;
            }
        }

        BlockEspRenderer.feed(SeedcrackerAddon.ID + ":bedrock-hole-esp", holes, color.get(), false, fill.get());
    }

    /** Flood-fill connected air blocks (bounded region) starting at pos. */
    private Set<BlockPos> floodFill(Minecraft mc, BlockPos start, Set<BlockPos> globalVisited) {
        Set<BlockPos> cluster = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        globalVisited.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (!isAir(mc, p)) continue;
            cluster.add(p);
            if (cluster.size() > 20) return Set.of(); // too big - not a hole
            for (Direction dir : Direction.values()) {
                BlockPos next = p.relative(dir);
                if (!globalVisited.contains(next) && isAir(mc, next)) {
                    globalVisited.add(next);
                    queue.add(next);
                }
            }
        }
        return cluster;
    }

    /** Every block neighbouring the cluster must be bedrock (fully enclosed hole). */
    private boolean isFullyBedrockBounded(Minecraft mc, Set<BlockPos> cluster) {
        for (BlockPos p : cluster) {
            for (Direction dir : Direction.values()) {
                BlockPos next = p.relative(dir);
                if (cluster.contains(next)) continue;
                if (isAir(mc, next)) return false;
                if (!mc.level.getBlockState(next).is(Blocks.BEDROCK)) return false;
            }
        }
        return true;
    }

    /** A seed candidate: an air block with at least one bedrock neighbour. */
    private boolean isBedrockBoundedSeed(Minecraft mc, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (mc.level.getBlockState(pos.relative(dir)).is(Blocks.BEDROCK)) return true;
        }
        return false;
    }

    private static boolean isAir(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).isAir();
    }
}
