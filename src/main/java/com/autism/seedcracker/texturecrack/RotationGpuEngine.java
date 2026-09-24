package com.autism.seedcracker.texturecrack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_program;

import com.autism.seedcracker.bedrock.BedrockGpuEngine;

/**
 * In-game GPU backend for the texture-rotation cracker (seedless counterpart to
 * {@link BedrockGpuEngine}). Same OpenCL safety model: one work-item per anchor, adaptive
 * z-slice dispatches (~150ms budget) so the shared render GPU never hits the display watchdog,
 * a startup self-test tile compared exhaustively against the CPU reference, and every GPU
 * match CPU re-verified before it is reported. Because rotation cracking is seedless there is
 * no world-seed factory chain to evaluate - full-world searches are dramatically faster than
 * bedrock for the same coverage.
 */
public final class RotationGpuEngine {

    private static final int TILE = 4096;
    private static final int MATCH_CAP_PER_TILE = 4096;
    private static final int MAX_MATCHES = 100;
    private static final int SLICE_MIN_ROWS = 32, SLICE_MAX_ROWS = 4096;
    private static final long SLICE_BUDGET_MS = 150;

    /** GPU load cap 10-100%: duty-cycles between dispatch slices (keeps the game responsive). */
    public static volatile int gpuLoadPercent = 60;

    private RotationGpuEngine() {}

    /** True when a usable OpenCL GPU exists (delegates to the shared probe). Never throws. */
    public static boolean available() {
        return BedrockGpuEngine.availability() != null;
    }

    public record Match(int x, int z, int orientation, int y) {}

    /**
     * GPU-backed texture-rotation search. Finds anchors (x,z) whose texture-rotation pattern
     * matches the observed grid at the given Y level, across all grid orientations.
     *
     * @param grid     rows x cols of observed rotations (0-3), -1 = unknown
     * @param y        block Y level of the observed blocks
     * @param centerX  search centre block X
     * @param centerZ  search centre block Z
     * @param radius   search radius in blocks
     * @param legacy   true = legacy variant formula (old clients), false = nextInt
     * @param onMatch  optional callback fired for each verified match
     */
    /** Single-Y entry (back-compat): scans just the one block level. */
    public static List<Match> solve(int[][] grid, int y, int centerX, int centerZ, int radius,
                                    boolean legacy, Consumer<Match> onMatch) {
        return solve(grid, y, 1, centerX, centerZ, radius, legacy, onMatch);
    }

    /**
     * Y-range search: scans every block level in [yStart, yStart+ySpan-1] at each anchor
     * (matches the .texcrack y-range setting, so an unknown-height screenshot still cracks).
     */
    public static List<Match> solve(int[][] grid, int yStart, int ySpan, int centerX, int centerZ, int radius,
                                    boolean legacy, Consumer<Match> onMatch) {
        if (!available()) {
            throw new IllegalStateException("No OpenCL GPU available - install your GPU vendor's driver, or use CPU mode.");
        }
        BedrockGpuEngine.Device device = device();
        if (device == null) {
            throw new IllegalStateException("No OpenCL GPU available - install your GPU vendor's driver, or use CPU mode.");
        }

        // Flatten known cells: (dx, dz, rotation, unused). Grid is [row][col].
        int rows = grid.length;
        int cols = 0;
        for (int[] r : grid) cols = Math.max(cols, r.length);
        List<int[]> cellList = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                int v = grid[r][c];
                if (v < 0) continue;
                cellList.add(new int[]{c, r, v & 3, 0});
            }
        }
        int cellCount = cellList.size();
        if (cellCount == 0) throw new IllegalArgumentException("grid has no known cells");
        int[] cellData = new int[cellCount * 4];
        for (int i = 0; i < cellCount; i++) {
            int[] cell = cellList.get(i);
            cellData[i * 4] = cell[0];
            cellData[i * 4 + 1] = cell[1];
            cellData[i * 4 + 2] = cell[2];
            cellData[i * 4 + 3] = cell[3];
        }

        cl_context context = null;
        cl_command_queue queue = null;
        cl_program program = null;
        cl_kernel kernel = null;
        List<Match> results = new ArrayList<>();
        try {
            CL.setExceptionsEnabled(true);
            cl_context_properties props = new cl_context_properties();
            props.addProperty(CL.CL_CONTEXT_PLATFORM, device.platform());
            context = CL.clCreateContext(props, 1, new cl_device_id[]{device.id()}, null, null, null);
            queue = CL.clCreateCommandQueueWithProperties(context, device.id(), null, null);
            program = CL.clCreateProgramWithSource(context, 1, new String[]{loadKernelSource()}, null, null);
            CL.clBuildProgram(program, 0, null, null, null, null);
            kernel = CL.clCreateKernel(program, "search_rotation", null);

            // Self-test: kernel vs the CPU reference on a 64x64 tile.
            String err = selfTest(context, queue, kernel, cellData, cellCount, cols, rows, yStart, ySpan, legacy);
            if (err != null) throw new IllegalStateException("GPU self-test failed: " + err);

            int minX = centerX - radius, maxX = centerX + radius;
            int minZ = centerZ - radius, maxZ = centerZ + radius;

            record Tile(int x0, int z0) {}
            List<Tile> tiles = new ArrayList<>();
            for (int tz = minZ; tz <= maxZ; tz += TILE) {
                for (int tx = minX; tx <= maxX; tx += TILE) {
                    tiles.add(new Tile(tx, tz));
                }
            }
            tiles.sort(Comparator.comparingLong(t ->
                Math.abs(t.x0() + TILE / 2L - centerX) + Math.abs(t.z0() + TILE / 2L - centerZ)));

            for (Tile tile : tiles) {
                if (TextureCrackEngine.cancelRequested || results.size() >= MAX_MATCHES) break;
                int spanX = Math.min(TILE, maxX - tile.x0() + 1);
                int spanZ = Math.min(TILE, maxZ - tile.z0() + 1);
                int[] raw = runTile(context, queue, kernel, cellData, cellCount, cols, rows, yStart, ySpan, legacy,
                    tile.x0(), tile.z0(), spanX, spanZ);
                for (int i = 0; i < raw.length; i += 4) {
                    if (!cpuVerify(raw[i], raw[i + 1], raw[i + 2], cellList, cols, rows, raw[i + 3], legacy)) continue;
                    Match m = new Match(raw[i], raw[i + 1], raw[i + 2], raw[i + 3]);
                    results.add(m);
                    if (onMatch != null) onMatch.accept(m);
                    if (results.size() >= MAX_MATCHES) break;
                }
            }
        } finally {
            try { if (kernel != null) CL.clReleaseKernel(kernel); } catch (Throwable ignored) {}
            try { if (program != null) CL.clReleaseProgram(program); } catch (Throwable ignored) {}
            try { if (queue != null) CL.clReleaseCommandQueue(queue); } catch (Throwable ignored) {}
            try { if (context != null) CL.clReleaseContext(context); } catch (Throwable ignored) {}
        }
        return results;
    }

    /** Re-fetch the shared probed device (the bedrock engine caches it). */
    private static BedrockGpuEngine.Device device() {
        return BedrockGpuEngine.bestDevice();
    }

    /** CPU re-verify one candidate match against the pure-Java reference. */
    private static boolean cpuVerify(int ax, int az, int rot, List<int[]> cells, int cols, int rows,
                                     int y, boolean legacy) {
        for (int[] cell : cells) {
            int dx, dz;
            switch (rot) {
                case 1 -> { dx = rows - 1 - cell[1]; dz = cell[0]; }
                case 2 -> { dx = cols - 1 - cell[0]; dz = rows - 1 - cell[1]; }
                case 3 -> { dx = cell[1]; dz = cols - 1 - cell[0]; }
                default -> { dx = cell[0]; dz = cell[1]; }
            }
            int observed = variantAt(ax + dx, y, az + dz, legacy);
            int want = (cell[2] + rot) & 3;
            if (observed != want) return false;
        }
        return true;
    }

    private static String selfTest(cl_context context, cl_command_queue queue, cl_kernel kernel,
                                   int[] cellData, int cellCount, int cols, int rows, int yStart, int ySpan, boolean legacy) {
        final int span = 64, origin = -32;
        int[] raw = runTile(context, queue, kernel, cellData, cellCount, cols, rows, yStart, ySpan, legacy,
            origin, origin, span, span);
        java.util.Set<Long> gpuSet = new java.util.HashSet<>();
        for (int i = 0; i < raw.length; i += 4) {
            gpuSet.add(((long) raw[i] & 0xFFFFFFL) << 26 | ((long) raw[i + 1] & 0xFFFFFFL) << 2 | raw[i + 2]
                | ((long) (raw[i + 3] & 0x3FF) << 50));
        }
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) cells.add(new int[]{cellData[i * 4], cellData[i * 4 + 1], cellData[i * 4 + 2]});
        for (int yy = 0; yy < ySpan; yy++) {
            int y = yStart + yy;
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    for (int rot = 0; rot < 4; rot++) {
                        boolean want = cpuVerify(origin + x, origin + z, rot, cells, cols, rows, y, legacy);
                        boolean got = gpuSet.contains(((long) (origin + x) & 0xFFFFFFL) << 26
                            | ((long) (origin + z) & 0xFFFFFFL) << 2 | rot | ((long) (y & 0x3FF) << 50));
                        if (want != got) {
                            return String.format("mismatch at x=%d z=%d rot=%d y=%d (cpu=%b gpu=%b)",
                                origin + x, origin + z, rot, y, want, got);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Executes the kernel on one tile; returns raw (x, z, rot, y) quads. */
    private static int[] runTile(cl_context context, cl_command_queue queue, cl_kernel kernel,
                                 int[] cellData, int cellCount, int cols, int rows, int yStart, int ySpan, boolean legacy,
                                 int originX, int originZ, int spanX, int spanZ) {
        cl_mem cellsBuf = null, countBuf = null, matchBuf = null;
        int adaptiveSliceRows = 256;
        try {
            cellsBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int4 * cellCount, Pointer.to(cellData), null);
            int[] zero = {0};
            countBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE | CL.CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(zero), null);
            matchBuf = CL.clCreateBuffer(context, CL.CL_MEM_WRITE_ONLY,
                (long) Sizeof.cl_int * 4 * MATCH_CAP_PER_TILE, null, null);

            int a = 0;
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{yStart}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{ySpan}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellCount}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cols}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{rows}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{legacy ? 1 : 0}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(countBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(matchBuf));
            CL.clSetKernelArg(kernel, a, Sizeof.cl_int, Pointer.to(new int[]{MATCH_CAP_PER_TILE}));

            // Adaptive z-slice dispatches (watchdog-safe) + duty-cycle for load < 100%.
            // (originZ/spanZ are args 3 and 5 now that yStart/ySpan lead.)
            int zOff = 0;
            while (zOff < spanZ) {
                if (TextureCrackEngine.cancelRequested) break;
                int slice = Math.min(Math.max(SLICE_MIN_ROWS, adaptiveSliceRows), spanZ - zOff);
                CL.clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{originZ + zOff}));
                CL.clSetKernelArg(kernel, 5, Sizeof.cl_int, Pointer.to(new int[]{slice}));
                long t0 = System.nanoTime();
                long[] global = {roundUp(spanX, 16), roundUp(slice, 16)};
                CL.clEnqueueNDRangeKernel(queue, kernel, 2, null, global, null, 0, null, null);
                CL.clFinish(queue);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (ms < SLICE_BUDGET_MS / 2 && adaptiveSliceRows < SLICE_MAX_ROWS) {
                    adaptiveSliceRows = Math.min(SLICE_MAX_ROWS, adaptiveSliceRows * 2);
                } else if (ms > SLICE_BUDGET_MS * 2 && adaptiveSliceRows > SLICE_MIN_ROWS) {
                    adaptiveSliceRows = Math.max(SLICE_MIN_ROWS, adaptiveSliceRows / 2);
                }
                int load = Math.max(10, Math.min(100, gpuLoadPercent));
                if (load < 100 && ms > 0) {
                    long sleepMs = Math.min(1000, ms * (100 - load) / load);
                    if (sleepMs > 0) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }
                zOff += slice;
            }

            int[] count = new int[1];
            CL.clEnqueueReadBuffer(queue, countBuf, CL.CL_TRUE, 0, Sizeof.cl_int, Pointer.to(count), 0, null, null);
            int n = Math.min(count[0], MATCH_CAP_PER_TILE);
            int[] data = new int[n * 4];
            if (n > 0) {
                CL.clEnqueueReadBuffer(queue, matchBuf, CL.CL_TRUE, 0,
                    (long) Sizeof.cl_int * data.length, Pointer.to(data), 0, null, null);
            }
            return data;
        } finally {
            for (cl_mem b : new cl_mem[]{cellsBuf, countBuf, matchBuf}) {
                if (b != null) {
                    try { CL.clReleaseMemObject(b); } catch (Throwable ignored) {}
                }
            }
        }
    }

    // ---- pure-Java reference (mirrors the kernel) ----

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private static long posSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    private static int idxNextInt(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;
        int u = (int) (s >>> 17);
        if ((count & (count - 1)) == 0) {
            return (int) ((count * (long) u) >> 31);
        }
        int m = count - 1;
        int r = u % count;
        while (u - r + m < 0) {
            s = (s * MULT + ADD) & MASK;
            u = (int) (s >>> 17);
            r = u % count;
        }
        return r;
    }

    private static int idxLegacy(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;
        s = (s * MULT + ADD) & MASK;
        int lo = (int) (s >>> 16);
        int abs = Math.abs(lo);
        return abs < 0 ? 0 : abs % count;
    }

    private static int variantAt(int x, int y, int z, boolean legacy) {
        long seed = posSeed(x, y, z);
        return legacy ? idxLegacy(seed, 4) : idxNextInt(seed, 4);
    }

    private static long roundUp(long v, long multiple) {
        return ((v + multiple - 1) / multiple) * multiple;
    }

    private static String loadKernelSource() {
        try (InputStream in = RotationGpuEngine.class.getResourceAsStream("/assets/seedcrackerx/rotation_kernel.cl")) {
            if (in == null) throw new IllegalStateException("rotation_kernel.cl missing from jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rotation kernel", e);
        }
    }
}
