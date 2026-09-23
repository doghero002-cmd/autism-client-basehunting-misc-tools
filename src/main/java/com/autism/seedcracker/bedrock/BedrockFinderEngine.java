package com.autism.seedcracker.bedrock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Searches for world positions whose bedrock pattern matches a user-drawn pattern. Bedrock is
 * derived from the world seed mixed with the block position via the "minecraft:bedrock_floor"
 * (or "bedrock_roof") positional random; a block is bedrock when one float draw falls under the
 * layer's density threshold. A marked pattern only occurs at specific coordinates, so finding it
 * pins the player's location.
 *
 * Multi-layer: patterns may span several Y layers of the SAME columns (a bedrock wall shows
 * Y-63..-60 stacked). Every layer must match at the same (x,z) anchor, and rotations rotate all
 * layers together as one rigid pillar - each extra layer multiplies the evidence, so 2 layers x
 * 8 cells is already ~unique.
 *
 * Speed: the vanilla path allocates a XoroshiroRandomSource PER BLOCK. This engine derives the
 * positional factory's two seed longs once (pure-Java re-implementation of the seed chain:
 * stafford mix -> xoroshiro fork -> MD5 hash of the resource id -> fork) and then evaluates each
 * position with ~6 arithmetic ops, no allocation. The derivation is VERIFIED at runtime against
 * the real vanilla factory on a sample of positions - if Mojang ever changes the chain, the
 * engine falls back to the (slow) vanilla path instead of returning wrong results.
 *
 * Layers: floor Y -63..-60 (densities 0.8/0.6/0.4/0.2) and nether roof Y 123..126
 * (0.2/0.4/0.6/0.8 top-down).
 */
public final class BedrockFinderEngine {
    public static volatile float currentProgress = 0.0f;
    public static volatile String statusText = "Ready";
    public static volatile boolean isSearching = false;
    public static volatile boolean cancelRequested = false;

    /** Matches from the most recent completed search (x,z pairs) - read by .crosscheck. */
    public static volatile List<long[]> lastMatches = List.of();

    /** CPU load cap 10-100%: scales worker thread count AND duty-cycles the scan loop. */
    public static volatile int cpuLoadPercent = 70;

    private static final int MAX_MATCHES = 500;

    private BedrockFinderEngine() {}

    public static void cancel() {
        cancelRequested = true;
    }

    // ---- vanilla derivation (kept for verification + fallback) ----

    public static PositionalRandomFactory getBedrockSplitter(long seed, String surfaceId) {
        XoroshiroRandomSource source = new XoroshiroRandomSource(seed);
        PositionalRandomFactory factory = source.forkPositional();
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", surfaceId);
        return factory.fromHashOf(id).forkPositional();
    }

    // ---- pure-Java seed chain (allocation-free per-position evaluation) ----

    private static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * -4658895280553007687L;
        z = (z ^ (z >>> 27)) * -7723592293110705685L;
        return z ^ (z >>> 31);
    }

    /** Xoroshiro128++ state stepper; {@code next()} matches vanilla nextLong exactly. */
    private static final class Xoro {
        long lo, hi;
        Xoro(long lo, long hi) {
            if ((lo | hi) == 0L) { // vanilla zero-state guard
                this.lo = -7046029254386353131L;
                this.hi = 7640891576956012809L;
            } else {
                this.lo = lo;
                this.hi = hi;
            }
        }
        long next() {
            long l = lo, m = hi;
            long n = Long.rotateLeft(l + m, 17) + l;
            m ^= l;
            lo = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
            hi = Long.rotateLeft(m, 28);
            return n;
        }
    }

    /** Vanilla Mth.getSeed (the x multiply overflows in int on purpose). */
    private static long posSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /** Replays worldSeed -> forkPositional -> fromHashOf(id) -> forkPositional; returns {lo, hi}. */
    private static long[] deriveSeeds(long worldSeed, String surfaceId) throws Exception {
        long sl = worldSeed ^ 0x6A09E667F3BCC909L;               // silver ratio
        long sh = sl + -7046029254386353131L;                     // golden ratio
        Xoro root = new Xoro(mixStafford13(sl), mixStafford13(sh));
        long fLo = root.next(), fHi = root.next();                // forkPositional

        byte[] md5 = MessageDigest.getInstance("MD5")
            .digest(("minecraft:" + surfaceId).getBytes(StandardCharsets.UTF_8));
        long hLo = beLong(md5, 0), hHi = beLong(md5, 8);
        Xoro hashed = new Xoro(hLo ^ fLo, hHi ^ fHi);             // fromHashOf
        return new long[]{hashed.next(), hashed.next()};          // forkPositional
    }

    private static long beLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    /** One-draw bedrock check: at(x,y,z).nextFloat() < threshold, fully inlined. */
    private static boolean isBedrockFast(long facLo, long facHi, int x, int y, int z, float threshold) {
        long lo = posSeed(x, y, z) ^ facLo;
        long hi = facHi;
        if ((lo | hi) == 0L) { lo = -7046029254386353131L; hi = 7640891576956012809L; }
        long n = Long.rotateLeft(lo + hi, 17) + lo;
        return (float) (n >>> 40) * 5.9604645E-8F < threshold;
    }

    /** Confirms the inlined chain against the real vanilla factory on a coordinate spread. */
    private static boolean verifyFastPath(long worldSeed, String surfaceId, long[] seeds, int y, float threshold) {
        try {
            PositionalRandomFactory vanilla = getBedrockSplitter(worldSeed, surfaceId);
            int[] probes = {0, 1, -1, 7, -8, 123, -456, 30000, -30000, 1234567, -7654321};
            for (int px : probes) {
                for (int pz : probes) {
                    RandomSource r = vanilla.at(px, y, pz);
                    boolean want = r.nextFloat() < threshold;
                    if (isBedrockFast(seeds[0], seeds[1], px, y, pz, threshold) != want) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bedrock density at a layer: floor Y-63..-60 = 0.8/0.6/0.4/0.2, roof Y123..126 mirrored. */
    public static float layerThreshold(int y, boolean roof) {
        return roof ? 1.0f - (127 - y) / 5.0f : 1.0f - (y + 64) / 5.0f;
    }

    public static List<Match> findPattern(long seed, int chunkRadius, int centerX, int centerZ, int[][] pattern) {
        return findPattern(seed, chunkRadius, centerX, centerZ, pattern, -60, false, null);
    }

    public static List<Match> findPattern(long seed, int chunkRadius, int centerX, int centerZ, int[][] pattern,
                                          Consumer<Match> onMatch) {
        return findPattern(seed, chunkRadius, centerX, centerZ, pattern, -60, false, onMatch);
    }

    /** Single-layer entry: delegates to the multi-layer scan with one layer. */
    public static List<Match> findPattern(long seed, int chunkRadius, int centerX, int centerZ, int[][] pattern,
                                          int layerY, boolean roof, Consumer<Match> onMatch) {
        return findPatternMulti(seed, chunkRadius, centerX, centerZ,
            new int[][][]{pattern}, new int[]{layerY}, roof, onMatch);
    }

    /**
     * Multi-layer pattern search. All layers must match at the same (x,z) anchor; rotations
     * rotate the whole layer stack together.
     *
     * @param seed          world seed
     * @param chunkRadius   search radius in chunks around the centre
     * @param centerX       centre block X
     * @param centerZ       centre block Z
     * @param layerPatterns per-layer grids of 0=unknown, 1=bedrock, 2=not-bedrock, [col][row];
     *                      all layers must share the same dimensions (max 16x16)
     * @param layerYs       Y level of each layer (floor -63..-60, roof 123..126)
     * @param roof          true = nether roof ("bedrock_roof"), false = floor ("bedrock_floor")
     * @param onMatch       optional callback fired for each match (off the render thread)
     */
    public static List<Match> findPatternMulti(long seed, int chunkRadius, int centerX, int centerZ,
                                               int[][][] layerPatterns, int[] layerYs, boolean roof,
                                               Consumer<Match> onMatch) {
        int layers = layerPatterns.length;
        if (layers == 0 || layers != layerYs.length) {
            throw new IllegalArgumentException("layer patterns and Y levels must pair up");
        }
        int cols = layerPatterns[0].length;
        int rows = layerPatterns[0][0].length;
        if (rows > 16 || cols > 16) {
            throw new IllegalArgumentException("Pattern dimensions cannot exceed 16x16 blocks.");
        }
        for (int[][] p : layerPatterns) {
            if (p.length != cols || p[0].length != rows) {
                throw new IllegalArgumentException("all layers must share the same dimensions");
            }
        }
        String surfaceId = roof ? "bedrock_roof" : "bedrock_floor";
        float[] thresholds = new float[layers];
        for (int i = 0; i < layers; i++) thresholds[i] = layerThreshold(layerYs[i], roof);

        // [layer][rotation] masks; rotation index applies to every layer simultaneously.
        RotatedPattern[][] rotations = new RotatedPattern[layers][];
        for (int l = 0; l < layers; l++) {
            rotations[l] = new RotatedPattern[]{
                new RotatedPattern(0, "Rot 0\u00b0", layerPatterns[l], cols, rows),
                new RotatedPattern(1, "Rot 90\u00b0 CW", layerPatterns[l], cols, rows),
                new RotatedPattern(2, "Rot 180\u00b0", layerPatterns[l], cols, rows),
                new RotatedPattern(3, "Rot 270\u00b0 CW", layerPatterns[l], cols, rows)
            };
        }
        int maxRows = 0, maxCols = 0;
        for (RotatedPattern r : rotations[0]) {
            maxRows = Math.max(maxRows, r.rows);
            maxCols = Math.max(maxCols, r.cols);
        }

        // Fast path setup + verification.
        long facLo = 0, facHi = 0;
        boolean fast = false;
        PositionalRandomFactory vanillaFactory = null;
        try {
            long[] s = deriveSeeds(seed, surfaceId);
            if (verifyFastPath(seed, surfaceId, s, layerYs[0], thresholds[0])) {
                facLo = s[0];
                facHi = s[1];
                fast = true;
            }
        } catch (Throwable ignored) {}
        if (!fast) vanillaFactory = getBedrockSplitter(seed, surfaceId);

        int minX = (centerX >> 4 << 4) - chunkRadius * 16;
        int maxX = (centerX >> 4 << 4) + chunkRadius * 16 + 15;
        int minZ = (centerZ >> 4 << 4) - chunkRadius * 16;
        int maxZ = (centerZ >> 4 << 4) + chunkRadius * 16 + 15;

        // Contiguous X-bands ordered centre-out so nearby matches surface first.
        final int bandW = 4096;
        List<int[]> bands = new ArrayList<>();
        for (int x0 = minX; x0 <= maxX; x0 += bandW) {
            bands.add(new int[]{x0, Math.min(x0 + bandW - 1, maxX)});
        }
        bands.sort(Comparator.comparingInt(b -> Math.abs((b[0] + b[1]) / 2 - centerX)));

        CopyOnWriteArrayList<Match> results = new CopyOnWriteArrayList<>();
        AtomicInteger found = new AtomicInteger(0);
        AtomicInteger nextBand = new AtomicInteger(0);
        AtomicLong rowsDone = new AtomicLong(0);
        long totalRows = (long) bands.size() * ((long) maxZ - minZ + 1);
        long startMs = System.currentTimeMillis();

        isSearching = true;
        cancelRequested = false;
        currentProgress = 0.0f;
        statusText = "0%";

        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, cores * Math.max(10, Math.min(100, cpuLoadPercent)) / 100);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "BedrockFinder");
            t.setDaemon(true);
            // Lowest priority: the OS scheduler always favours the render/game threads over us.
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();

        final boolean fFast = fast;
        final long fLo = facLo, fHi = facHi;
        final PositionalRandomFactory fVanilla = vanillaFactory;
        final int fMaxRows = maxRows, fMaxCols = maxCols;

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                int bi;
                while ((bi = nextBand.getAndIncrement()) < bands.size()
                        && !cancelRequested && found.get() < MAX_MATCHES) {
                    int[] band = bands.get(bi);
                    scanBand(band[0], band[1], minZ, maxZ, layerYs, thresholds,
                        fFast, fLo, fHi, fVanilla, rotations, fMaxRows, fMaxCols,
                        results, found, onMatch, rowsDone, totalRows, startMs);
                }
            }));
        }

        pool.shutdown();
        for (Future<?> future : futures) {
            try { future.get(); } catch (Exception e) { e.printStackTrace(); }
        }
        try { pool.awaitTermination(1L, TimeUnit.HOURS); } catch (Exception ignored) {}

        isSearching = false;
        currentProgress = 1.0f;
        int n = results.size();
        statusText = (cancelRequested ? "Stopped: " : "Done: ") + n + " match" + (n == 1 ? "" : "es")
            + (fFast ? "" : " (slow path)");

        List<long[]> coords = new ArrayList<>(results.size());
        for (Match m : results) coords.add(new long[]{m.x, m.z});
        lastMatches = List.copyOf(coords);
        return new ArrayList<>(results);
    }

    /**
     * Scans one X-band with per-layer rolling windows of bit-packed rows: each position is
     * evaluated once per layer, and every rotation's masks are compared for ALL layers at the
     * same anchor (early break on the first failing layer/row).
     */
    private static void scanBand(int x0, int x1, int minZ, int maxZ, int[] layerYs, float[] thresholds,
                                 boolean fast, long facLo, long facHi, PositionalRandomFactory vanilla,
                                 RotatedPattern[][] rotations, int maxRows, int maxCols,
                                 CopyOnWriteArrayList<Match> results, AtomicInteger found,
                                 Consumer<Match> onMatch, AtomicLong rowsDone, long totalRows, long startMs) {
        int layers = layerYs.length;
        // Rows extend maxCols-1 past the band so anchors near x1 see their full pattern width.
        int rowBlocks = (x1 - x0 + 1) + maxCols - 1;
        int rowLongs = (rowBlocks + 63) / 64;
        long[][][] ring = new long[layers][maxRows][rowLongs];
        int filled = 0;
        long dutyStartNs = System.nanoTime();
        int dutyRows = 0;

        for (int z = minZ; z <= maxZ && !cancelRequested && found.get() < MAX_MATCHES; z++) {
            // Duty-cycle: at N% load, idle (100-N)/N of the time in fine slices so the game
            // stays responsive even while every worker is running.
            if (++dutyRows >= 64) {
                int load = Math.max(10, Math.min(100, cpuLoadPercent));
                if (load < 100) {
                    long busyMs = (System.nanoTime() - dutyStartNs) / 1_000_000;
                    long sleepMs = Math.min(250, busyMs * (100 - load) / load);
                    if (sleepMs > 0) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
                dutyRows = 0;
                dutyStartNs = System.nanoTime();
            }
            int slot = Math.floorMod(z - minZ, maxRows);
            for (int l = 0; l < layers; l++) {
                long[] row = ring[l][slot];
                java.util.Arrays.fill(row, 0L);
                int y = layerYs[l];
                float th = thresholds[l];
                if (fast) {
                    for (int i = 0; i < rowBlocks; i++) {
                        if (isBedrockFast(facLo, facHi, x0 + i, y, z, th)) {
                            row[i >> 6] |= 1L << (i & 63);
                        }
                    }
                } else {
                    for (int i = 0; i < rowBlocks; i++) {
                        if (vanilla.at(x0 + i, y, z).nextFloat() < th) {
                            row[i >> 6] |= 1L << (i & 63);
                        }
                    }
                }
            }
            filled++;

            long done = rowsDone.incrementAndGet();
            if ((done & 255) == 0) {
                currentProgress = (float) done / totalRows;
                long elapsed = System.currentTimeMillis() - startMs;
                long etaSec = elapsed > 500 ? (long) ((totalRows - done) * (elapsed / 1000.0) / done) : -1;
                statusText = String.format("%d%% - %d match(es)%s",
                    (int) (currentProgress * 100), found.get(),
                    etaSec >= 0 ? String.format(" - ETA %d:%02d", etaSec / 60, etaSec % 60) : "");
            }
            if (filled < maxRows) continue;

            int anchorZ = z - maxRows + 1;
            for (int rot = 0; rot < 4; rot++) {
                RotatedPattern shape = rotations[0][rot];
                int zShift = maxRows - shape.rows;
                int maxOff = rowBlocks - shape.cols;
                for (int off = 0; off <= maxOff; off++) {
                    boolean ok = true;
                    for (int l = 0; l < layers && ok; l++) {
                        RotatedPattern rp = rotations[l][rot];
                        for (int r = 0; r < rp.rows; r++) {
                            long[] bits = ring[l][Math.floorMod(anchorZ - minZ + zShift + r, maxRows)];
                            int idx = off >> 6, sh = off & 63;
                            long w = bits[idx] >>> sh;
                            if (sh != 0 && idx + 1 < bits.length) w |= bits[idx + 1] << (64 - sh);
                            if ((((int) w ^ rp.bedrockMask[r]) & rp.activeMask[r]) != 0) { ok = false; break; }
                        }
                    }
                    if (!ok) continue;
                    long mx = x0 + off;
                    long mz = anchorZ + zShift;
                    Match match = new Match(mx, mz, (int) (mx >> 4), (int) (mz >> 4),
                        (int) (mx & 15), (int) (mz & 15), shape.name);
                    if (found.incrementAndGet() <= MAX_MATCHES) {
                        results.add(match);
                        if (onMatch != null) onMatch.accept(match);
                    } else {
                        cancelRequested = true;
                        return;
                    }
                }
            }
        }
    }

    /** A pattern precomputed into per-row bit masks for one rotation. Input pattern is [col][row]. */
    public static final class RotatedPattern {
        public final int rot;
        public final String name;
        public final int rows;
        public final int cols;
        public final int[] bedrockMask;
        public final int[] activeMask;

        public RotatedPattern(int rot, String name, int[][] pattern, int pCols, int pRows) {
            this.rot = rot;
            this.name = name;
            if (rot == 1 || rot == 3) {
                this.rows = pCols;
                this.cols = pRows;
            } else {
                this.rows = pRows;
                this.cols = pCols;
            }
            this.bedrockMask = new int[this.rows];
            this.activeMask = new int[this.rows];
            for (int c = 0; c < pCols; c++) {
                for (int r = 0; r < pRows; r++) {
                    int cell = pattern[c][r];
                    if (cell == 0) continue;
                    int rr, cc;
                    switch (rot) {
                        case 1 -> { rr = c; cc = pRows - 1 - r; }
                        case 2 -> { rr = pRows - 1 - r; cc = pCols - 1 - c; }
                        case 3 -> { rr = pCols - 1 - c; cc = r; }
                        default -> { rr = r; cc = c; }
                    }
                    if (cell == 1) {
                        this.bedrockMask[rr] |= 1 << cc;
                        this.activeMask[rr] |= 1 << cc;
                    } else if (cell == 2) {
                        this.activeMask[rr] |= 1 << cc;
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
