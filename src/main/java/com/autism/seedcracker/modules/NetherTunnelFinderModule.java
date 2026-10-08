package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Nether Tunnel Finder.
 *
 * Detects player-dug nether highways: long, straight, walkable air corridors at nether-travel
 * height, often paved with obsidian / cobblestone / slabs. A corridor of mostly-air with a flat
 * floor running many blocks in a straight line is almost always a player travel route - and
 * travel routes lead to bases.
 *
 * Flagged chunks are rendered by the shared {@link ChunkFlagRenderer}. Best used in the Nether.
 */
public final class NetherTunnelFinderModule extends Module {

    private final autismclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new autismclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH = corridors half min-length count (more finds). LOW = double min-length (only unmistakable tunnels).")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 6, 1, 12, 1)
        .description("Chunk bubble around you scanned for tunnels.").group("General"));
    private final IntSetting minRunLength = add(new IntSetting("min-run", "Min corridor length", 12, 4, 64, 1)
        .description("Consecutive walkable-air blocks in a straight line needed to count as a tunnel.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", true)
        .description("Toast + chat ping when a tunnel is found.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0xFFFF8040)
        .description("Colour of the flagged chunk marker.").group("Render"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal Y range, scan rate and tracer.").group("General"));
    private final IntSetting minY = add(new IntSetting("min-y", "Min Y", 8, 0, 128, 1)
        .description("Lowest Y level to scan (nether tunnels are usually near the bedrock ceiling or mid-level).")
        .group("Advanced").visibleWhen(showAdvanced::get));
    private final IntSetting maxY = add(new IntSetting("max-y", "Max Y", 118, 8, 127, 1)
        .description("Highest Y level to scan.").group("Advanced").visibleWhen(showAdvanced::get));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false)
        .description("Draw a tracer line to each flagged chunk.").group("Render").visibleWhen(showAdvanced::get));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 1, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.").group("Performance").visibleWhen(showAdvanced::get));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Tunnel", 50);
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public NetherTunnelFinderModule() {
        super(SeedcrackerAddon.ID + ":nether-tunnel-finder", "Nether Tunnel Finder",
            "Detects player-dug nether highways (long straight walkable corridors).");
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.init();
        flagged.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":nether-tunnel-finder");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        scan(mc);
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":nether-tunnel-finder", flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private void scan(Minecraft mc) {
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 1000, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (hasTunnel(mc, chunk)) {
                if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
            } else {
                flagged.remove(pos);
            }
        }
        int pr = radius + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    /**
     * True if the chunk contains a straight horizontal run of walkable air (2-high, solid floor)
     * at least {@code minRunLength} long, on any row (X or Z) within the Y band.
     */
    private boolean hasTunnel(Minecraft mc, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int run = sensitivity.get().scale(minRunLength.get());
        // Clamp: the min-y slider max exceeds max-y's, and an inverted range silently scanned nothing.
        int y0 = Math.min(minY.get(), maxY.get());
        int y1 = Math.max(minY.get(), maxY.get());
        LevelChunkSection[] sections = chunk.getSections();
        int minYWorld = mc.level.getMinY();

        for (int y = y0; y <= y1; y++) {
            int secIdx = (y - minYWorld) >> 4;
            if (secIdx < 0 || secIdx >= sections.length) continue;
            LevelChunkSection sec = sections[secIdx];
            if (sec == null || sec.hasOnlyAir()) continue;
            int ly = y & 15;
            // Scan rows along X.
            for (int z = 0; z < 16; z++) {
                int streak = 0;
                for (int x = 0; x < 16; x++) {
                    if (isWalkable(sections, secIdx, x, ly, z)) { if (++streak >= run) return true; }
                    else streak = 0;
                }
            }
            // Scan columns along Z.
            for (int x = 0; x < 16; x++) {
                int streak = 0;
                for (int z = 0; z < 16; z++) {
                    if (isWalkable(sections, secIdx, x, ly, z)) { if (++streak >= run) return true; }
                    else streak = 0;
                }
            }
        }
        return false;
    }

    /** 2-high air with a solid (non-fluid) floor = a walkable corridor block. Head/floor reads
     * cross into the neighbouring section - skipping them at ly 0/15 false-flagged every section
     * boundary (Y multiples of 16). */
    private boolean isWalkable(LevelChunkSection[] sections, int secIdx, int x, int ly, int z) {
        LevelChunkSection sec = sections[secIdx];
        if (!sec.getBlockState(x, ly, z).isAir()) return false;
        // head room (crosses up into the next section at ly 15)
        if (ly + 1 < 16) {
            if (!sec.getBlockState(x, ly + 1, z).isAir()) return false;
        } else if (secIdx + 1 < sections.length && sections[secIdx + 1] != null) {
            if (!sections[secIdx + 1].getBlockState(x, 0, z).isAir()) return false;
        }
        // floor below must be solid (crosses down into the previous section at ly 0)
        net.minecraft.world.level.block.state.BlockState floor = null;
        if (ly - 1 >= 0) {
            floor = sec.getBlockState(x, ly - 1, z);
        } else if (secIdx - 1 >= 0 && sections[secIdx - 1] != null) {
            floor = sections[secIdx - 1].getBlockState(x, 15, z);
        }
        if (floor == null || floor.isAir() || !floor.getFluidState().isEmpty()) return false;
        return true;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        com.autism.seedcracker.finder.FinderNotify.flag("§6[NetherTunnel]",
            "Nether tunnel at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), true);
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }
}
