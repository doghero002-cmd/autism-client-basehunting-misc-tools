package com.autism.seedcracker.texturecrack;

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

/**
 * Coordinate cracker from observed block-texture rotations (the "TextureRotations" technique).
 *
 * Blocks like dirt/sand/gravel/netherrack pick one of 4 y-rotated model variants from a hash of
 * the block position ONLY ({@code Mth.getSeed(x,y,z)}) - no world seed involved. A grid of
 * rotations read off a screenshot therefore only fits specific world coordinates: search every
 * position in range and keep the ones whose computed variant pattern matches.
 *
 * Search core: each world position's variant is hashed exactly ONCE per (y, formula) into a
 * rolling window of rows, and the (pre-transformed) pattern slides over it - no per-candidate
 * re-hashing. Orientations are handled by rotating the PATTERN up front, and each orientation is
 * physically coupled to the matching apparent-rotation offset (viewing the floor from a rotated
 * cardinal rotates the grid layout AND every texture by the same quarter turn), which cuts both
 * work and false positives vs testing all 16 combos blindly.
 *
 * Matching is tolerance-based and weighted: each cell mismatch costs that cell's confidence
 * weight (auto-read cells carry their NCC margin), candidates die when cost exceeds the budget,
 * and matches report their total cost so callers can rank them.
 *
 * Formulas: modern clients pick the variant with {@code nextInt(4)} (NEXTINT); old clients used
 * {@code Math.abs((int) nextLong()) % 4} (LEGACY). The screenshotter's CLIENT decides - servers
 * play no part - so NEXTINT is the right default and AUTO tries both.
 */
public final class TextureCrackEngine {

    public record Match(int x, int y, int z, int orientation, int valueOffset, String formula, double cost) {}

    /** Cell of a pre-transformed pattern: world offset + accepted-variant bitmask + mismatch cost. */
    private record PCell(int dx, int dz, int acceptMask, float weight) {}

    private record Pattern(int orientation, int valueOffset, int width, int height, PCell[] cells) {}

    public static volatile boolean searching = false;
    public static volatile boolean cancelRequested = false;
    public static volatile float progress = 0f;
    public static volatile String status = "Ready";

    /** CPU load cap 10-100%: scales worker threads AND duty-cycles the scan loop. */
    public static volatile int cpuLoadPercent = 70;

    public static final int FORMULA_AUTO = 0, FORMULA_LEGACY = 1, FORMULA_NEXTINT = 2;

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private TextureCrackEngine() {}

    public static void cancel() {
        cancelRequested = true;
    }

    /** Vanilla Mth.getSeed - note the x term overflows in INT exactly like vanilla. */
    public static long posSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /** LEGACY formula: Math.abs((int) new Random(seed).nextLong()) % count. */
    public static int idxLegacy(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;            // next(32) #1 (hi word, discarded by the (int) cast)
        s = (s * MULT + ADD) & MASK;            // next(32) #2 = low word of nextLong
        int lo = (int) (s >>> 16);
        int abs = Math.abs(lo);
        return abs < 0 ? 0 : abs % count;       // Math.abs(MIN_VALUE) quirk, matches vanilla
    }

    /** NEXTINT formula: new Random(seed).nextInt(count), including the rejection loop for
     *  non-power-of-two bounds (weighted variant sets produce arbitrary total weights). */
    public static int idxNextInt(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;            // next(31)
        int u = (int) (s >>> 17);
        if ((count & (count - 1)) == 0) {
            return (int) ((count * (long) u) >> 31);
        }
        int m = count - 1;
        int r = u % count;
        while (u - r + m < 0) {                 // reject draws from the biased tail
            s = (s * MULT + ADD) & MASK;
            u = (int) (s >>> 17);
            r = u % count;
        }
        return r;
    }

    /** Back-compat entry: plain 4-rotation blocks (dirt-style). */
    public static void solve(int[][] grid, float[][] weights, int obsY, int yRange,
                             int centerX, int centerZ, int radius,
                             int formulaMode, int facingLock, boolean allOffsets, double tolerance,
                             int maxMatches, Consumer<Match> onMatch, Runnable onDone) {
        solve(grid, weights, null, obsY, yRange, centerX, centerZ, radius,
            formulaMode, facingLock, allOffsets, tolerance, maxMatches, onMatch, onDone);
    }

    /**
     * @param grid        rows x cols of observed transforms (rot 0-3 | mirror bit 4), -1 = unknown
     * @param weights     per-cell mismatch cost, same shape as grid (null = all 1.0)
     * @param variants    the block's variant set (null = uniform 4-rotation, dirt-style)
     * @param obsY        Y level of the observed blocks
     * @param yRange      also try Y +- this many levels (0 = exact)
     * @param centerX     search centre block X
     * @param centerZ     search centre block Z
     * @param radius      search radius in blocks
     * @param formulaMode FORMULA_AUTO / FORMULA_LEGACY / FORMULA_NEXTINT
     * @param facingLock  -1 = try all orientations, 0-3 = only that grid orientation (N/E/S/W)
     * @param allOffsets  true = test all 16 orientation x offset combos (convention paranoia);
     *                    false = only the physically-coupled pairs (default, fewer FPs)
     * @param tolerance   max total mismatch cost a match may accumulate (0 = exact)
     * @param maxMatches  stop after this many matches
     */
    public static void solve(int[][] grid, float[][] weights, BlockVariantSet variants, int obsY, int yRange,
                             int centerX, int centerZ, int radius,
                             int formulaMode, int facingLock, boolean allOffsets, double tolerance,
                             int maxMatches, Consumer<Match> onMatch, Runnable onDone) {
        final BlockVariantSet vs = variants;
        List<Pattern> patterns = buildPatterns(grid, weights, vs, facingLock, allOffsets);
        // Draw -> variant lookup: vanilla picks nextInt(totalWeight) and walks cumulative weights.
        final int drawRange;
        final byte[] drawToVariant;
        if (vs == null) {
            drawRange = 4;
            drawToVariant = new byte[]{0, 1, 2, 3};
        } else {
            drawRange = vs.totalWeight();
            drawToVariant = new byte[drawRange];
            int di = 0;
            for (int v = 0; v < vs.count(); v++) {
                for (int w = 0; w < vs.weight(v); w++) drawToVariant[di++] = (byte) v;
            }
        }
        if (patterns.isEmpty()) {
            status = "No known cells in grid";
            if (onDone != null) onDone.run();
            return;
        }

        searching = true;
        cancelRequested = false;
        progress = 0f;
        status = "Searching...";

        boolean tryLegacy = formulaMode != FORMULA_NEXTINT;
        boolean tryNextInt = formulaMode != FORMULA_LEGACY;

        int minX = centerX - radius, maxX = centerX + radius;
        int minZ = centerZ - radius, maxZ = centerZ + radius;

        // Contiguous X-bands, ordered centre-out so nearby matches surface first.
        final int bandW = 512;
        List<int[]> bands = new ArrayList<>();
        for (int x0 = minX; x0 <= maxX; x0 += bandW) {
            bands.add(new int[]{x0, Math.min(x0 + bandW - 1, maxX)});
        }
        bands.sort(Comparator.comparingInt(b -> Math.abs((b[0] + b[1]) / 2 - centerX)));

        CopyOnWriteArrayList<Match> results = new CopyOnWriteArrayList<>();
        AtomicInteger found = new AtomicInteger(0);
        AtomicLong rowsDone = new AtomicLong(0);
        AtomicInteger nextBand = new AtomicInteger(0);
        int yCount = 2 * yRange + 1;
        int formulaCount = (tryLegacy ? 1 : 0) + (tryNextInt ? 1 : 0);
        long totalRows = (long) bands.size() * ((long) maxZ - minZ + 1) * yCount * formulaCount;

        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, cores * Math.max(10, Math.min(100, cpuLoadPercent)) / 100);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "TextureCrack");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                int bi;
                while ((bi = nextBand.getAndIncrement()) < bands.size()
                        && !cancelRequested && found.get() < maxMatches) {
                    int[] band = bands.get(bi);
                    for (int y = obsY - yRange; y <= obsY + yRange; y++) {
                        if (tryNextInt) scanBand(band[0], band[1], minZ, maxZ, y, false, drawRange, drawToVariant,
                            patterns, tolerance, results, found, maxMatches, onMatch, rowsDone, totalRows);
                        if (tryLegacy) scanBand(band[0], band[1], minZ, maxZ, y, true, drawRange, drawToVariant,
                            patterns, tolerance, results, found, maxMatches, onMatch, rowsDone, totalRows);
                        if (cancelRequested || found.get() >= maxMatches) break;
                    }
                }
            }));
        }

        Thread waiter = new Thread(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (Throwable ignored) {}
            }
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            searching = false;
            progress = 1f;
            status = (cancelRequested ? "Stopped - " : "Done - ") + found.get() + " match(es)";
            if (onDone != null) onDone.run();
        }, "TextureCrack-Waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Scans one X-band: hashes each position's variant exactly once into a rolling row window,
     * then slides every pattern over it.
     */
    private static void scanBand(int x0, int x1, int minZ, int maxZ, int y, boolean legacy,
                                 int drawRange, byte[] drawToVariant,
                                 List<Pattern> patterns, double tolerance,
                                 CopyOnWriteArrayList<Match> results, AtomicInteger found, int maxMatches,
                                 Consumer<Match> onMatch, AtomicLong rowsDone, long totalRows) {
        int maxPw = 0, maxPh = 0;
        for (Pattern p : patterns) {
            maxPw = Math.max(maxPw, p.width());
            maxPh = Math.max(maxPh, p.height());
        }
        // Rows extend maxPw-1 beyond the band so anchors near x1 see their full pattern width.
        int rowLen = (x1 - x0 + 1) + maxPw - 1;
        byte[][] ring = new byte[maxPh][rowLen];
        int filled = 0;
        long dutyStartNs = System.nanoTime();
        int dutyRows = 0;

        for (int z = minZ; z <= maxZ && !cancelRequested && found.get() < maxMatches; z++) {
            // Duty-cycle like the bedrock engine: at N% load idle (100-N)/N of the time.
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
            byte[] row = ring[Math.floorMod(z - minZ, maxPh)];
            for (int i = 0; i < rowLen; i++) {
                long seed = posSeed(x0 + i, y, z);
                row[i] = drawToVariant[legacy ? idxLegacy(seed, drawRange) : idxNextInt(seed, drawRange)];
            }
            filled++;

            long done = rowsDone.incrementAndGet();
            if ((done & 1023) == 0) {
                progress = (float) done / totalRows;
                status = String.format("%.0f%% - %d match(es)", progress * 100, found.get());
            }
            if (filled < maxPh) continue;

            // Anchor row: the topmost row still in the ring.
            int anchorZ = z - maxPh + 1;
            for (Pattern p : patterns) {
                // Patterns shorter than maxPh anchor lower so their rows are all in the ring.
                int aZ = anchorZ + (maxPh - p.height());
                int maxAx = rowLen - p.width();
                for (int ax = 0; ax <= maxAx; ax++) {
                    double cost = 0;
                    boolean dead = false;
                    for (PCell cell : p.cells()) {
                        byte v = ring[Math.floorMod(aZ - minZ + cell.dz(), maxPh)][ax + cell.dx()];
                        if ((cell.acceptMask() >> v & 1) == 0) {
                            cost += cell.weight();
                            if (cost > tolerance) { dead = true; break; }
                        }
                    }
                    if (dead) continue;
                    Match m = new Match(x0 + ax, y, aZ, p.orientation(), p.valueOffset(),
                        legacy ? "legacy" : "nextInt", cost);
                    if (found.incrementAndGet() <= maxMatches) {
                        results.add(m);
                        if (onMatch != null) onMatch.accept(m);
                    } else {
                        cancelRequested = true;
                        return;
                    }
                }
            }
        }
    }

    // ---- side-face WALL cracking (CoordsFinder technique) ----
    // On SIDE faces, y-rotations are invisible (every side face of cube/cube_mirrored carries the
    // same texture + UV), but the "_mirrored" models flip the texture horizontally - so each wall
    // cell of stone/deepslate/bedrock leaks 1 bit of Mth.getSeed(x,y,z). A wall grid is a vertical
    // plane: rows = Y descending from the anchor, cols = horizontal as seen on the screenshot.
    // 32+ known cells recommended (1 bit/cell vs 2 bits for top-face rotations).

    /** Wall cell observation -> bitmask of variant indices with that mirror bit (rotation ignored). */
    private static int wallAcceptMask(BlockVariantSet vs, boolean mirrored) {
        int mask = 0;
        for (int v = 0; v < vs.count(); v++) {
            if (((vs.transform(v) & BlockVariantSet.T_MIRROR) != 0) == mirrored) mask |= 1 << v;
        }
        return mask;
    }

    /** One wall-pattern cell: horizontal offset + Y-down offset + accepted variants + cost. */
    private record WCell(int dh, int dyDown, int acceptMask, float weight) {}

    /**
     * Wall solve: searches every (x, z, anchorY) for a vertical mirror-bit pattern.
     *
     * @param mirrorGrid rows x cols, -1 unknown, 0 normal, T_MIRROR mirrored; row 0 = TOP row
     * @param obsYTop    estimated Y of the TOP row
     * @param yRange     anchor Y uncertainty (+-)
     * @param facingLock -1 = try all 4 observer facings, 0-3 = N/E/S/W only
     */
    public static void solveWall(int[][] mirrorGrid, float[][] weights, BlockVariantSet variants,
                                 int obsYTop, int yRange, int centerX, int centerZ, int radius,
                                 int formulaMode, int facingLock, double tolerance,
                                 int maxMatches, Consumer<Match> onMatch, Runnable onDone) {
        if (variants == null || !variants.hasMirrors()) {
            status = "Block has no mirrored variants - walls carry no signal (use stone/deepslate/bedrock)";
            if (onDone != null) onDone.run();
            return;
        }
        int rowsTmp = mirrorGrid.length;
        int colsTmp = 0;
        for (int[] r : mirrorGrid) colsTmp = Math.max(colsTmp, r.length);
        final int rows = rowsTmp, cols = colsTmp;
        if (rows == 0 || cols == 0) {
            status = "Empty wall grid";
            if (onDone != null) onDone.run();
            return;
        }

        // Build cells for both horizontal directions (observer facing flips column order) and both
        // mirror interpretations (viewing side ambiguity), heaviest-first for early kill.
        // dirFlip=false: cols map to +axis; true: reversed. inv: observed flip inverted.
        List<List<WCell>> cellSets = new ArrayList<>();
        List<String> cellLabels = new ArrayList<>();
        for (int flip = 0; flip < 2; flip++) {
            for (int inv = 0; inv < 2; inv++) {
                List<WCell> cells = new ArrayList<>();
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < mirrorGrid[r].length; c++) {
                        int obs = mirrorGrid[r][c];
                        if (obs < 0) continue;
                        boolean mirrored = (obs & BlockVariantSet.T_MIRROR) != 0;
                        if (inv == 1) mirrored = !mirrored;
                        int dh = flip == 0 ? c : (cols - 1 - c);
                        float wgt = weights != null && r < weights.length && c < weights[r].length
                            ? Math.max(0.01f, weights[r][c]) : 1.0f;
                        cells.add(new WCell(dh, r, wallAcceptMask(variants, mirrored), wgt));
                    }
                }
                if (cells.isEmpty()) continue;
                cells.sort((a, b) -> Float.compare(b.weight(), a.weight()));
                cellSets.add(cells);
                cellLabels.add((flip == 0 ? "fwd" : "rev") + (inv == 1 ? "+inv" : ""));
            }
        }
        if (cellSets.isEmpty()) {
            status = "No known cells in wall grid";
            if (onDone != null) onDone.run();
            return;
        }

        // Draw -> variant table (same as the floor solve).
        final int drawRange = variants.totalWeight();
        final byte[] drawToVariant = new byte[drawRange];
        {
            int di = 0;
            for (int v = 0; v < variants.count(); v++) {
                for (int w = 0; w < variants.weight(v); w++) drawToVariant[di++] = (byte) v;
            }
        }

        searching = true;
        cancelRequested = false;
        progress = 0f;
        status = "Searching walls...";

        boolean tryLegacy = formulaMode != FORMULA_NEXTINT;
        boolean tryNextInt = formulaMode != FORMULA_LEGACY;
        // Observer facings: 0=N (wall along X, sees +Z faces), 1=E (along Z), 2=S (along X), 3=W.
        boolean alongXn = facingLock < 0 || facingLock == 0;
        boolean alongXs = facingLock < 0 || facingLock == 2;
        boolean alongZe = facingLock < 0 || facingLock == 1;
        boolean alongZw = facingLock < 0 || facingLock == 3;
        // Facing only decides column direction, which cellSets already covers via fwd/rev - so the
        // real split is wall-along-X vs wall-along-Z.
        boolean scanAlongX = alongXn || alongXs;
        boolean scanAlongZ = alongZe || alongZw;

        int minX = centerX - radius, maxX = centerX + radius;
        int minZ = centerZ - radius, maxZ = centerZ + radius;
        int yLo = obsYTop - yRange, yHi = obsYTop + yRange;
        int yWin = (yHi - yLo) + rows;              // hashed Y window per strip

        final int bandW = 256;
        List<int[]> bands = new ArrayList<>();
        for (int h0 = (scanAlongX ? minX : minZ); h0 <= (scanAlongX ? maxX : maxZ); h0 += bandW) {
            bands.add(new int[]{h0, Math.min(h0 + bandW - 1, scanAlongX ? maxX : maxZ)});
        }

        CopyOnWriteArrayList<Match> results = new CopyOnWriteArrayList<>();
        AtomicInteger found = new AtomicInteger(0);
        AtomicLong stripsDone = new AtomicLong(0);
        AtomicInteger nextStrip = new AtomicInteger(0);
        int axesCount = (scanAlongX ? 1 : 0) + (scanAlongZ ? 1 : 0);
        int formulaCount = (tryLegacy ? 1 : 0) + (tryNextInt ? 1 : 0);
        // One strip = one fixed perpendicular coordinate (z for X-walls, x for Z-walls).
        long totalStrips = (long) ((maxZ - minZ + 1) + (scanAlongZ && scanAlongX ? (maxX - minX + 1) : 0))
            * formulaCount * Math.max(1, bands.size());
        if (!scanAlongX) totalStrips = (long) (maxX - minX + 1) * formulaCount * bands.size();

        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, cores * Math.max(10, Math.min(100, cpuLoadPercent)) / 100);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "TextureCrack-Wall");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        final long fTotalStrips = Math.max(1, totalStrips);
        final List<List<WCell>> fCellSets = cellSets;
        final List<String> fLabels = cellLabels;

        // Work items: (axis, band) pairs; each item walks all perpendicular strips for its band.
        List<int[]> work = new ArrayList<>();
        for (int b = 0; b < bands.size(); b++) {
            if (scanAlongX) work.add(new int[]{0, b});
            if (scanAlongZ) work.add(new int[]{1, b});
        }

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                int wi;
                while ((wi = nextStrip.getAndIncrement()) < work.size()
                        && !cancelRequested && found.get() < maxMatches) {
                    int[] item = work.get(wi);
                    boolean axisX = item[0] == 0;
                    int[] band = bands.get(item[1]);
                    if (tryNextInt) scanWallBand(axisX, band[0], band[1], axisX ? minZ : minX, axisX ? maxZ : maxX,
                        yLo, yWin, rows, cols, false, drawRange, drawToVariant, variants,
                        fCellSets, fLabels, tolerance, results, found, maxMatches, onMatch, stripsDone, fTotalStrips);
                    if (tryLegacy && !cancelRequested && found.get() < maxMatches)
                        scanWallBand(axisX, band[0], band[1], axisX ? minZ : minX, axisX ? maxZ : maxX,
                            yLo, yWin, rows, cols, true, drawRange, drawToVariant, variants,
                            fCellSets, fLabels, tolerance, results, found, maxMatches, onMatch, stripsDone, fTotalStrips);
                }
            }));
        }

        Thread waiter = new Thread(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (Throwable ignored) {}
            }
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            searching = false;
            progress = 1f;
            status = (cancelRequested ? "Stopped - " : "Done - ") + found.get() + " wall match(es)";
            if (onDone != null) onDone.run();
        }, "TextureCrack-WallWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Scans one horizontal band of wall anchors. For each perpendicular strip (fixed z for X-walls,
     * fixed x for Z-walls) the small Y-window of variant rows is hashed once, then every cell set
     * slides across the band. Match.y reports the TOP row's Y.
     */
    private static void scanWallBand(boolean axisX, int h0, int h1, int p0, int p1,
                                     int yLo, int yWin, int rows, int cols, boolean legacy,
                                     int drawRange, byte[] drawToVariant, BlockVariantSet variants,
                                     List<List<WCell>> cellSets, List<String> labels, double tolerance,
                                     CopyOnWriteArrayList<Match> results, AtomicInteger found, int maxMatches,
                                     Consumer<Match> onMatch, AtomicLong stripsDone, long totalStrips) {
        int bandLen = (h1 - h0 + 1) + cols - 1;     // anchors near h1 still see full pattern width
        byte[][] plane = new byte[yWin][bandLen];
        long dutyStartNs = System.nanoTime();
        int dutyStrips = 0;

        for (int p = p0; p <= p1 && !cancelRequested && found.get() < maxMatches; p++) {
            if (++dutyStrips >= 8) {
                int load = Math.max(10, Math.min(100, cpuLoadPercent));
                if (load < 100) {
                    long busyMs = (System.nanoTime() - dutyStartNs) / 1_000_000;
                    long sleepMs = Math.min(250, busyMs * (100 - load) / load);
                    if (sleepMs > 0) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
                dutyStrips = 0;
                dutyStartNs = System.nanoTime();
            }
            // Hash the strip's Y window.
            for (int yi = 0; yi < yWin; yi++) {
                int y = yLo + yi;
                byte[] row = plane[yi];
                for (int i = 0; i < bandLen; i++) {
                    int wx = axisX ? h0 + i : p;
                    int wz = axisX ? p : h0 + i;
                    long seed = posSeed(wx, y, wz);
                    row[i] = drawToVariant[legacy ? idxLegacy(seed, drawRange) : idxNextInt(seed, drawRange)];
                }
            }
            // Anchor Y = top row; it can sit anywhere such that all rows fit in the window.
            int anchorYiMax = yWin - rows;
            for (int s = 0; s < cellSets.size(); s++) {
                List<WCell> cells = cellSets.get(s);
                for (int ayi = anchorYiMax; ayi >= 0; ayi--) {
                    int maxAh = bandLen - cols;
                    for (int ah = 0; ah <= maxAh; ah++) {
                        double cost = 0;
                        boolean dead = false;
                        for (WCell cell : cells) {
                            byte v = plane[ayi + cell.dyDown()][ah + cell.dh()];
                            if ((cell.acceptMask() >> v & 1) == 0) {
                                cost += cell.weight();
                                if (cost > tolerance) { dead = true; break; }
                            }
                        }
                        if (dead) continue;
                        int wx = axisX ? h0 + ah : p;
                        int wz = axisX ? p : h0 + ah;
                        Match m = new Match(wx, yLo + ayi, wz, axisX ? 0 : 1, s,
                            (legacy ? "legacy" : "nextInt") + " wall-" + (axisX ? "X" : "Z") + " " + labels.get(s), cost);
                        if (found.incrementAndGet() <= maxMatches) {
                            results.add(m);
                            if (onMatch != null) onMatch.accept(m);
                        } else {
                            cancelRequested = true;
                            return;
                        }
                    }
                }
            }
            long done = stripsDone.incrementAndGet();
            if ((done & 63) == 0) {
                progress = (float) done / totalStrips;
                status = String.format("%.0f%% - %d wall match(es)", Math.min(100f, progress * 100), found.get());
            }
        }
    }

    /**
     * Pre-transforms the observed grid into world-space patterns, one per orientation/offset
     * combo. Coupled mode pairs each grid orientation with the apparent-rotation offsets it can
     * physically produce (both handedness conventions); all mode tests every offset. Each cell
     * carries the bitmask of variant indices that could show the observed transform under that
     * combo (equality for plain 4-rotation blocks; mirror-aware for stone/bedrock-style sets).
     * Cells are sorted heaviest-first so mismatching candidates die on the most confident cell.
     */
    private static List<Pattern> buildPatterns(int[][] grid, float[][] weights, BlockVariantSet variants,
                                               int facingLock, boolean allOffsets) {
        int rows = grid.length;
        int cols = 0;
        for (int[] r : grid) cols = Math.max(cols, r.length);

        List<Pattern> out = new ArrayList<>();
        for (int o = 0; o < 4; o++) {
            if (facingLock >= 0 && o != facingLock) continue;
            int[] offsets = allOffsets ? new int[]{0, 1, 2, 3}
                : (o == 0 || o == 2) ? new int[]{o} : new int[]{o, (4 - o) & 3};
            for (int vo : offsets) {
                List<PCell> cells = new ArrayList<>();
                int w = (o & 1) == 0 ? cols : rows;
                int h = (o & 1) == 0 ? rows : cols;
                boolean impossible = false;
                for (int r = 0; r < rows && !impossible; r++) {
                    for (int c = 0; c < grid[r].length; c++) {
                        if (grid[r][c] < 0) continue;
                        // Rotate grid coords into non-negative world offsets.
                        int dx, dz;
                        switch (o) {
                            case 1 -> { dx = rows - 1 - r; dz = c; }
                            case 2 -> { dx = cols - 1 - c; dz = rows - 1 - r; }
                            case 3 -> { dx = r; dz = cols - 1 - c; }
                            default -> { dx = c; dz = r; }
                        }
                        float wgt = weights != null && r < weights.length && c < weights[r].length
                            ? Math.max(0.01f, weights[r][c]) : 1.0f;
                        int mask;
                        if (variants == null) {
                            // Mirror-coded observations can't be matched without the variant set:
                            // treating code 4|rot as a rotation would silently corrupt the solve.
                            if ((grid[r][c] & ~3) != 0) continue;
                            mask = 1 << ((grid[r][c] + vo) & 3);
                        } else {
                            mask = variants.acceptMask(vo, (byte) grid[r][c], true);
                            if (mask == 0) { impossible = true; break; } // observation unreachable here
                        }
                        cells.add(new PCell(dx, dz, mask, wgt));
                    }
                }
                if (impossible || cells.isEmpty()) continue;
                cells.sort((a, b) -> Float.compare(b.weight(), a.weight()));
                out.add(new Pattern(o, vo, w, h, cells.toArray(new PCell[0])));
            }
        }
        return out;
    }
}
