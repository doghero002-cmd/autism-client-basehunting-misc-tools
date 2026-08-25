package com.autism.seedcracker.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Searches for world positions whose Y=-60 bedrock-floor pattern matches a user-drawn
 * pattern. The world generator derives each bedrock block from the world seed mixed with the
 * block's X/Y/Z via the "minecraft:bedrock_floor" positional random; a marked block is bedrock
 * when the draw falls under the depth threshold (0.2 at Y=-60). Because a marked pattern only
 * occurs at specific coordinates, finding it pins the player's location.
 *
 * The pattern is tested in all four rotations since the in-game orientation is irrelevant.
 * Adapted from BedrockPatternFinder (v26.11).
 */
public final class BedrockFinderEngine {
    public static volatile float currentProgress = 0.0f;
    public static volatile String statusText = "Ready";
    public static volatile boolean isSearching = false;
    public static volatile boolean cancelRequested = false;

    private BedrockFinderEngine() {}

    public static void cancel() {
        cancelRequested = true;
    }

    public static PositionalRandomFactory getBedrockSplitter(long seed) {
        XoroshiroRandomSource source = new XoroshiroRandomSource(seed);
        PositionalRandomFactory factory = source.forkPositional();
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "bedrock_floor");
        return factory.fromHashOf(id).forkPositional();
    }

    /** Fills bits[z] with a 16-bit mask of bedrock columns for chunk (chunkX, chunkZ) at Y=-60. */
    private static void generateChunkBits(int[] bits, int chunkX, int chunkZ, PositionalRandomFactory factory) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int z = 0; z < 16; z++) {
            int worldZ = baseZ + z;
            int mask = 0;
            for (int x = 0; x < 16; x++) {
                RandomSource random = factory.at(baseX + x, -60, worldZ);
                if (random.nextFloat() < 0.2f) {
                    mask |= 1 << x;
                }
            }
            bits[z] = mask;
        }
    }

    public static List<Match> findPattern(long seed, int chunkRadius, int centerX, int centerZ, int[][] pattern) {
        return findPattern(seed, chunkRadius, centerX, centerZ, pattern, null);
    }

    /**
     * @param seed        world seed
     * @param chunkRadius search radius in chunks around the centre
     * @param centerX     centre block X
     * @param centerZ     centre block Z
     * @param pattern     grid of 0=unknown, 1=bedrock, 2=not-bedrock (max 16x16)
     * @param onMatch     optional callback fired for each match (off the render thread)
     */
    public static List<Match> findPattern(long seed, int chunkRadius, int centerX, int centerZ, int[][] pattern, Consumer<Match> onMatch) {
        CopyOnWriteArrayList<Match> results = new CopyOnWriteArrayList<>();
        int rows = pattern.length;
        int cols = pattern[0].length;
        if (rows > 16 || cols > 16) {
            throw new IllegalArgumentException("Pattern dimensions cannot exceed 16x16 blocks.");
        }

        RotatedPattern[] rotations = new RotatedPattern[]{
            new RotatedPattern(0, "Rot 0\u00b0", pattern, rows, cols),
            new RotatedPattern(1, "Rot 90\u00b0 CW", pattern, rows, cols),
            new RotatedPattern(2, "Rot 180\u00b0", pattern, rows, cols),
            new RotatedPattern(3, "Rot 270\u00b0 CW", pattern, rows, cols)
        };

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        PositionalRandomFactory factory = getBedrockSplitter(seed);
        int minCX = centerChunkX - chunkRadius;
        int maxCX = centerChunkX + chunkRadius;
        int minCZ = centerChunkZ - chunkRadius;
        int maxCZ = centerChunkZ + chunkRadius;
        long width = (long) maxCX - (long) minCX + 1L;
        long total = width * ((long) maxCZ - (long) minCZ + 1L);

        AtomicLong done = new AtomicLong(0L);
        isSearching = true;
        cancelRequested = false;
        currentProgress = 0.0f;
        statusText = String.format("0%% (0/%,d)", total);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            final int chunkX = cx;
            futures.add(pool.submit(() -> {
                int[] worldBits = new int[32];
                int[] curX = new int[16];
                int[] nextZ = new int[16];
                int[] curXNextZ = new int[16];
                int[] nextXNextZ = new int[16];

                generateChunkBits(curX, chunkX, minCZ, factory);
                generateChunkBits(curXNextZ, chunkX + 1, minCZ, factory);

                for (int cz = minCZ; cz <= maxCZ && !cancelRequested; cz++) {
                    generateChunkBits(nextZ, chunkX, cz + 1, factory);
                    generateChunkBits(nextXNextZ, chunkX + 1, cz + 1, factory);

                    for (int z = 0; z < 16; z++) {
                        worldBits[z] = curX[z] | (curXNextZ[z] << 16);
                        worldBits[z + 16] = nextZ[z] | (nextXNextZ[z] << 16);
                    }

                    long baseX = (long) chunkX * 16L;
                    long baseZ = (long) cz * 16L;

                    for (RotatedPattern rot : rotations) {
                        int rrows = rot.rows;
                        int[] bedrockMask = rot.bedrockMask;
                        int[] activeMask = rot.activeMask;
                        for (int zOff = 0; zOff < 16; zOff++) {
                            for (int xOff = 0; xOff < 16; xOff++) {
                                boolean ok = true;
                                for (int r = 0; r < rrows; r++) {
                                    int rowBits = worldBits[zOff + r] >>> xOff;
                                    if (((rowBits ^ bedrockMask[r]) & activeMask[r]) == 0) continue;
                                    ok = false;
                                    break;
                                }
                                if (!ok) continue;
                                long mx = baseX + xOff;
                                long mz = baseZ + zOff;
                                Match match = new Match(mx, mz, chunkX, cz, xOff, zOff, rot.name);
                                results.add(match);
                                if (onMatch != null) onMatch.accept(match);
                            }
                        }
                    }

                    System.arraycopy(nextZ, 0, curX, 0, 16);
                    System.arraycopy(nextXNextZ, 0, curXNextZ, 0, 16);

                    long finished = done.incrementAndGet();
                    float f = (float) ((double) finished / (double) total);
                    currentProgress = f;
                    statusText = String.format("%d%% (%,d/%,d)", (int) (f * 100.0f), finished, total);
                }
            }));
        }

        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            pool.awaitTermination(1L, TimeUnit.HOURS);
        } catch (Exception e) {
            e.printStackTrace();
        }

        isSearching = false;
        currentProgress = 1.0f;
        statusText = cancelRequested
            ? "Cancelled: " + results.size() + " match" + (results.size() == 1 ? "" : "es")
            : "Done: " + results.size() + " match" + (results.size() == 1 ? "" : "es");
        return new ArrayList<>(results);
    }

    /** A pattern precomputed into per-row bit masks for one rotation. */
    public static final class RotatedPattern {
        public final int rot;
        public final String name;
        public final int rows;
        public final int cols;
        public final int[] bedrockMask;
        public final int[] activeMask;

        public RotatedPattern(int rot, String name, int[][] pattern, int rows, int cols) {
            this.rot = rot;
            this.name = name;
            if (rot == 1 || rot == 3) {
                this.rows = cols;
                this.cols = rows;
            } else {
                this.rows = rows;
                this.cols = cols;
            }
            this.bedrockMask = new int[this.rows];
            this.activeMask = new int[this.rows];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    int cell = pattern[i][j];
                    if (cell == 0) continue;
                    int r;
                    int c;
                    if (rot == 0) { r = i; c = j; }
                    else if (rot == 1) { r = j; c = rows - 1 - i; }
                    else if (rot == 2) { r = rows - 1 - i; c = cols - 1 - j; }
                    else { r = cols - 1 - j; c = i; }

                    if (cell == 1) {
                        this.bedrockMask[r] |= 1 << c;
                        this.activeMask[r] |= 1 << c;
                    } else if (cell == 2) {
                        this.activeMask[r] |= 1 << c;
                    }
                }
            }
        }
    }

    /** A single matching position. */
    public static final class Match {
        public final long x;
        public final long z;
        public final int chunkX;
        public final int chunkZ;
        public final int relX;
        public final int relZ;
        public final String rotation;

        public Match(long x, long z, int chunkX, int chunkZ, int relX, int relZ, String rotation) {
            this.x = x;
            this.z = z;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.relX = relX;
            this.relZ = relZ;
            this.rotation = rotation;
        }
    }
}
