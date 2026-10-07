package gpucrack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

/**
 * OpenCL host orchestration for the texture-rotation cracker (seedless counterpart to
 * {@link GpuSearch}). Same correctness policy: a startup self-test tile is compared
 * exhaustively against {@link RotationCpuReference}, and every GPU-reported match is CPU
 * re-verified before it is surfaced.
 */
public final class RotationGpuSearch implements AutoCloseable {

    public record Match(int x, int z, int rotation, int y) {}

    public interface Progress {
        /** @return false to cancel the search. */
        boolean onProgress(long anchorsDone, long anchorsTotal, int matchCount, double anchorsPerSec);

        void onMatch(Match match);
    }

    private static final int MATCH_CAP_PER_TILE = 4096;

    /** Rows per kernel dispatch, adapted toward ~150ms per enqueue (display-watchdog safety). */
    private volatile int adaptiveSliceRows = 256;

    private final GpuSearch.Device device;
    private cl_context context;
    private cl_command_queue queue;
    private cl_program program;
    private cl_kernel kernel;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public RotationGpuSearch(GpuSearch.Device device) {
        this.device = device;
        CL.setExceptionsEnabled(true);
        cl_context_properties props = new cl_context_properties();
        props.addProperty(CL.CL_CONTEXT_PLATFORM, device.platform());
        context = CL.clCreateContext(props, 1, new cl_device_id[]{device.id()}, null, null, null);
        queue = CL.clCreateCommandQueueWithProperties(context, device.id(), null, null);
        program = CL.clCreateProgramWithSource(context, 1, new String[]{loadKernelSource()}, null, null);
        CL.clBuildProgram(program, 0, null, null, null, null);
        kernel = CL.clCreateKernel(program, "search_rotation", null);
    }

    private static String loadKernelSource() {
        try (InputStream in = RotationGpuSearch.class.getResourceAsStream("/rotation_kernel.cl")) {
            if (in == null) throw new IllegalStateException("rotation_kernel.cl missing from jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rotation_kernel.cl", e);
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    /** Self-test: run the kernel on a small tile, compare EVERY anchor to the CPU reference. */
    public String selfTest(RotationPattern pattern, int yStart, int ySpan, boolean legacy) {
        final int span = 64;
        final int origin = -32;
        List<Match> gpu = runTile(pattern, yStart, ySpan, legacy, origin, origin, span, span);
        if (gpu == null) return "self-test tile failed to execute";
        java.util.Set<Long> gpuSet = new java.util.HashSet<>();
        for (Match m : gpu) gpuSet.add(key(m.x(), m.z(), m.rotation(), m.y()));
        for (int yy = 0; yy < ySpan; yy++) {
            int y = yStart + yy;
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    for (int rot = 0; rot < 4; rot++) {
                        boolean want = RotationCpuReference.matchesAt(pattern, y, legacy, origin + x, origin + z, rot);
                        boolean got = gpuSet.contains(key(origin + x, origin + z, rot, y));
                        if (want != got) {
                            return String.format("kernel disagrees with CPU at x=%d z=%d rot=%d y=%d (cpu=%b gpu=%b)",
                                origin + x, origin + z, rot, y, want, got);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static long key(int x, int z, int rot, int y) {
        return (((long) x & 0xFFFFFFL) << 26) | (((long) z & 0xFFFFFFL) << 2) | rot
            | ((long) (y & 0x3FF) << 50);
    }

    /**
     * Tiled search over the square region (centre-out Chebyshev rings, lazy like
     * {@link GpuSearch#search}). Matches are CPU-verified before being surfaced.
     *
     * @return all verified matches, or null if cancelled mid-way (partials already delivered)
     */
    /** Single-Y entry (back-compat): scans just the one block level. */
    public List<Match> search(RotationPattern pattern, int y, boolean legacy,
                              int centerX, int centerZ, long radius, int tileSize, Progress progress) {
        return search(pattern, y, 1, legacy, centerX, centerZ, radius, tileSize, progress);
    }

    /**
     * Y-range search: scans every block level in [yStart, yStart+ySpan-1] at each (x,z) anchor.
     * Unknown-height screenshots still crack. Matches are CPU-verified before being surfaced.
     */
    public List<Match> search(RotationPattern pattern, int yStart, int ySpan, boolean legacy,
                              int centerX, int centerZ, long radius, int tileSize, Progress progress) {
        cancelled.set(false);
        String selfTestError = selfTest(pattern, yStart, ySpan, legacy);
        if (selfTestError != null) throw new IllegalStateException("GPU self-test FAILED: " + selfTestError);

        long minX = centerX - radius, maxX = centerX + radius;
        long minZ = centerZ - radius, maxZ = centerZ + radius;

        int countX = (int) ((maxX - minX) / tileSize + 1);
        int countZ = (int) ((maxZ - minZ) / tileSize + 1);
        int centreTx = (int) Math.max(0, Math.min(countX - 1, (centerX - minX) / tileSize));
        int centreTz = (int) Math.max(0, Math.min(countZ - 1, (centerZ - minZ) / tileSize));
        int maxRing = Math.max(Math.max(centreTx, countX - 1 - centreTx),
                               Math.max(centreTz, countZ - 1 - centreTz));

        long anchorsTotal = (maxX - minX + 1) * (maxZ - minZ + 1);
        long anchorsDone = 0;
        long startNs = System.nanoTime();
        List<Match> all = new ArrayList<>();

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int tz = centreTz - ring; tz <= centreTz + ring; tz++) {
                if (tz < 0 || tz >= countZ) continue;
                boolean zEdge = tz == centreTz - ring || tz == centreTz + ring;
                int stepX = zEdge ? 1 : 2 * ring;
                for (int tx = centreTx - ring; tx <= centreTx + ring; tx += Math.max(1, stepX)) {
                    if (tx < 0 || tx >= countX) continue;
                    if (cancelled.get()) return null;
                    long x0 = minX + (long) tx * tileSize;
                    long z0 = minZ + (long) tz * tileSize;
                    int spanX = (int) Math.min(tileSize, maxX - x0 + 1);
                    int spanZ = (int) Math.min(tileSize, maxZ - z0 + 1);
                    List<Match> raw = runTile(pattern, yStart, ySpan, legacy, (int) x0, (int) z0, spanX, spanZ);
                    if (raw == null) return null;
                    for (Match m : raw) {
                        if (RotationCpuReference.matchesAt(pattern, m.y(), legacy, m.x(), m.z(), m.rotation())) {
                            all.add(m);
                            if (progress != null) progress.onMatch(m);
                        }
                    }
                    anchorsDone += (long) spanX * spanZ;
                    double secs = (System.nanoTime() - startNs) / 1e9;
                    double rate = secs > 0.1 ? anchorsDone / secs : 0;
                    if (progress != null && !progress.onProgress(anchorsDone, anchorsTotal, all.size(), rate)) {
                        cancelled.set(true);
                        return null;
                    }
                }
            }
        }
        return all;
    }

    /** Builds the rotated cell set for one grid orientation: (dx, dz, mask16) per known cell. */
    private static int[] buildRotatedCells(RotationPattern pattern, int rot, boolean legacy) {
        int cols = pattern.cols(), rows = pattern.rows();
        List<int[]> out = new ArrayList<>();
        for (RotationPattern.Cell cell : pattern.cells()) {
            int code = cell.rotation();
            if (code < 0) continue; // unknown accepts everything - skip
            int[] d = RotationCpuReference.rotate(cell.dx(), cell.dz(), rot, cols, rows);
            int mask = FaceModel.acceptMask(code, rot, legacy);
            out.add(new int[]{d[0], d[1], mask, 0});
        }
        int[] flat = new int[out.size() * 4];
        for (int i = 0; i < out.size(); i++) {
            int[] c = out.get(i);
            flat[i * 4] = c[0];
            flat[i * 4 + 1] = c[1];
            flat[i * 4 + 2] = c[2];
            flat[i * 4 + 3] = c[3];
        }
        return flat;
    }

    /** Executes the kernel on one tile; returns raw (unverified) matches or null when cancelled. */
    private List<Match> runTile(RotationPattern pattern, int yStart, int ySpan, boolean legacy,
                                int originX, int originZ, int spanX, int spanZ) {
        // Build one rotated cell set per orientation, with host-computed 16-bit masks.
        int[][] cellSets = new int[4][];
        for (int rot = 0; rot < 4; rot++) cellSets[rot] = buildRotatedCells(pattern, rot, legacy);

        cl_mem cellsBuf0 = null, cellsBuf1 = null, cellsBuf2 = null, cellsBuf3 = null;
        cl_mem countBuf = null, matchBuf = null;
        try {
            cellsBuf0 = createCellBuffer(context, cellSets[0]);
            cellsBuf1 = createCellBuffer(context, cellSets[1]);
            cellsBuf2 = createCellBuffer(context, cellSets[2]);
            cellsBuf3 = createCellBuffer(context, cellSets[3]);
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
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf0));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellSets[0].length / 4}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf1));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellSets[1].length / 4}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf2));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellSets[2].length / 4}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf3));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellSets[3].length / 4}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{legacy ? 1 : 0}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(countBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(matchBuf));
            CL.clSetKernelArg(kernel, a, Sizeof.cl_int, Pointer.to(new int[]{MATCH_CAP_PER_TILE}));

            // Adaptive z-slice dispatches: each enqueue targets ~150ms so weak GPUs never hit
            // the OS display watchdog (~2s on Windows = driver reset). (originZ/spanZ are args
            // 3 and 5 now that yStart/ySpan lead.)
            int zOff = 0;
            while (zOff < spanZ && !cancelled.get()) {
                int slice = Math.min(Math.max(32, adaptiveSliceRows), spanZ - zOff);
                CL.clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{originZ + zOff}));
                CL.clSetKernelArg(kernel, 5, Sizeof.cl_int, Pointer.to(new int[]{slice}));
                long t0 = System.nanoTime();
                long[] global = {roundUp(spanX, 16), roundUp(slice, 16)};
                CL.clEnqueueNDRangeKernel(queue, kernel, 2, null, global, null, 0, null, null);
                CL.clFinish(queue);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (ms < 75 && adaptiveSliceRows < 8192) adaptiveSliceRows *= 2;
                else if (ms > 300 && adaptiveSliceRows > 32) adaptiveSliceRows /= 2;
                zOff += slice;
            }

            int[] count = new int[1];
            CL.clEnqueueReadBuffer(queue, countBuf, CL.CL_TRUE, 0, Sizeof.cl_int, Pointer.to(count), 0, null, null);
            int n = Math.min(count[0], MATCH_CAP_PER_TILE);
            List<Match> out = new ArrayList<>(n);
            if (n > 0) {
                int[] data = new int[n * 4];
                CL.clEnqueueReadBuffer(queue, matchBuf, CL.CL_TRUE, 0,
                    (long) Sizeof.cl_int * data.length, Pointer.to(data), 0, null, null);
                for (int i = 0; i < n; i++) {
                    out.add(new Match(data[i * 4], data[i * 4 + 1], data[i * 4 + 2], data[i * 4 + 3]));
                }
            }
            if (count[0] > MATCH_CAP_PER_TILE) {
                System.err.printf("warning: tile at %d,%d overflowed the match cap (%d) - pattern too weak%n",
                    originX, originZ, count[0]);
            }
            return out;
        } finally {
            release(cellsBuf0, cellsBuf1, cellsBuf2, cellsBuf3, countBuf, matchBuf);
        }
    }

    /** Creates a device buffer holding one rotated cell set (empty -> 1 dummy cell, mask 0xFFFF). */
    private static cl_mem createCellBuffer(cl_context context, int[] cellData) {
        if (cellData.length == 0) cellData = new int[]{0, 0, 0xFFFF, 0};
        return CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
            (long) Sizeof.cl_int4 * (cellData.length / 4), Pointer.to(cellData), null);
    }

    private static long roundUp(long v, long multiple) {
        return ((v + multiple - 1) / multiple) * multiple;
    }

    private static void release(cl_mem... buffers) {
        for (cl_mem b : buffers) {
            if (b != null) {
                try { CL.clReleaseMemObject(b); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public void close() {
        try { if (kernel != null) CL.clReleaseKernel(kernel); } catch (Throwable ignored) {}
        try { if (program != null) CL.clReleaseProgram(program); } catch (Throwable ignored) {}
        try { if (queue != null) CL.clReleaseCommandQueue(queue); } catch (Throwable ignored) {}
        try { if (context != null) CL.clReleaseContext(context); } catch (Throwable ignored) {}
        kernel = null;
        program = null;
        queue = null;
        context = null;
    }
}
