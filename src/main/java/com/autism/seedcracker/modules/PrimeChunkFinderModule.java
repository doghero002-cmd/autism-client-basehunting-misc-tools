package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

/**
 * Prime Chunk Finder.
 *
 * Fluid-flow chunk fingerprinting (a port of Meteor's NewChunks via the CodeEngine
 * "PrimeChunkFinderModule"). Flags chunks that contain flowing (non-still) water/lava with
 * no adjacent still-fluid source — a stranded-flow signature left by past player activity
 * (placed/removed fluid, explosions, etc.). Chunks near world spawn are ignored.
 *
 * Ported to the AUTISM API (Mojang 26.2); flagged chunks are drawn with ChunkFlagRenderer.
 */
public final class PrimeChunkFinderModule extends Module {

    private final IntSetting sensitivity = add(new IntSetting("sensitivity", "Sensitivity", 40, 0, 100, 1)
        .description("Higher values flag weaker fluid-flow evidence. At 40, chunk-border flows are ignored; low values also ignore surface flows and require more evidence.")
        .group("Detect"));
    private final IntSetting minDistance = add(new IntSetting("min-distance", "Min distance from spawn", 200, 0, 10000, 50)
        .description("Ignore chunks closer than this to 0,0.").group("Detect"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Flow blocks threshold", 1, 1, 64, 1)
        .description("Stranded flowing-fluid blocks needed to flag a chunk (LOW sensitivity doubles this).").group("Detect"));
    private final IntSetting range = add(new IntSetting("range", "Range (chunks)", 8, 1, 16, 1)
        .description("Chunk radius around you to scan.").group("Detect"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFB030)
        .description("Highlight colour for flagged chunks.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Draw a line to the nearest flagged chunk.").group("Render"));

    private static final int[][] NEIGHBOURS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private final Set<Long> flagged = ConcurrentHashMap.newKeySet();
    private final Set<Long> scanned = ConcurrentHashMap.newKeySet();
    private int cursor = 0;
    private int lastSensitivity = -1;

    public PrimeChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":prime-chunk-finder", "Prime Chunk Finder", category,
            "Flags chunks with stranded fluid-flow signatures of past player activity.");
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.init();
        flagged.clear();
        scanned.clear();
        cursor = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        scanned.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":prime-chunk-finder");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Sensitivity changed: rescan everything under the new rules.
        if (lastSensitivity != sensitivity.get()) {
            lastSensitivity = sensitivity.get();
            scanned.clear();
            flagged.clear();
        }

        int r = range.get();
        ChunkPos centre = mc.player.chunkPosition();
        int side = r * 2 + 1;
        int total = side * side;
        for (int i = 0; i < 4; i++) {
            int idx = cursor % total;
            cursor = (cursor + 1) % total;
            int dx = idx % side - r;
            int dz = idx / side - r;
            int cx = centre.x() + dx, cz = centre.z() + dz;
            if (!mc.level.hasChunk(cx, cz)) continue;
            scan(mc.level.getChunk(cx, cz));
        }

        // Prune out-of-range flags.
        int pr = r + 2;
        flagged.removeIf(key -> {
            int kx = (int) (key >> 32);
            int kz = (int) (key & 0xffffffffL);
            return Math.abs(kx - centre.x()) > pr || Math.abs(kz - centre.z()) > pr;
        });

        Set<ChunkPos> chunks = new java.util.HashSet<>();
        for (long key : flagged) {
            int kx = (int) (key >> 32);
            int kz = (int) (key & 0xffffffffL);
            chunks.add(new ChunkPos(kx, kz));
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":prime-chunk-finder", chunks, color.get(), tracer.get());
    }

    private void scan(LevelChunk chunk) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();
        long key = ((long) pos.x() << 32) | (pos.z() & 0xffffffffL);
        if (!scanned.add(key)) return;

        long cx = ((long) pos.x() << 4) + 8L;
        long cz = ((long) pos.z() << 4) + 8L;
        long min = minDistance.get();
        if (cx * cx + cz * cz < min * min) return;

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) return;

        int sensitivityValue = sensitivity.get();
        // 40 is the medium profile: border flows are ignored and the configured threshold is
        // unchanged. Higher values widen the detector; lower values suppress natural surface flow
        // and require additional evidence.
        int need = Math.max(1, (int) Math.ceil(threshold.get() * (1.4 - sensitivityValue / 100.0)));
        boolean skipBorder = sensitivityValue < 60;
        int maxSurfaceY = sensitivityValue < 20 ? 50 : Integer.MAX_VALUE;
        int minSectionY = chunk.getMinSectionY();

        int count = 0;
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null || sec.hasOnlyAir()) continue;
            int sectionBaseY = (minSectionY + s) << 4;
            for (int y = 0; y < 16; y++) {
                if (sectionBaseY + y > maxSurfaceY) break;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (skipBorder && (x == 0 || x == 15 || z == 0 || z == 15)) continue;
                        FluidState fluid = sec.getBlockState(x, y, z).getFluidState();
                        if (!fluid.isEmpty() && !fluid.isSource() && !hasStillNeighbour(sections, s, x, y, z)) {
                            if (++count >= need) {
                                flagged.add(key);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /** True if any 6-neighbour (crossing section borders) holds still/source fluid. */
    private static boolean hasStillNeighbour(LevelChunkSection[] sections, int s, int x, int y, int z) {
        for (int[] n : NEIGHBOURS) {
            int nx = x + n[0];
            int ny = y + n[1];
            int nz = z + n[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            int sec = s;
            if (ny < 0) { sec = s - 1; ny = 15; }
            else if (ny > 15) { sec = s + 1; ny = 0; }
            if (sec < 0 || sec >= sections.length) continue;
            LevelChunkSection nsec = sections[sec];
            if (nsec == null || nsec.hasOnlyAir()) continue;
            FluidState nf = nsec.getBlockState(nx, ny, nz).getFluidState();
            if (!nf.isEmpty() && nf.isSource()) return true;
        }
        return false;
    }
}
