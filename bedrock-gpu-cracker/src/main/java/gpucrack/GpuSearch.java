package gpucrack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.jocl.cl_platform_id;
import org.jocl.cl_program;

/**
 * OpenCL host orchestration: device discovery, kernel compilation, self-test, tiled dispatch.
 *
 * Correctness policy: before the first real tile, the kernel is run on a sample tile and
 * compared exhaustively against {@link CpuReference}; any disagreement aborts the search with a
 * clear error. Every match the GPU reports is additionally CPU re-verified before being
 * surfaced. The GPU can therefore be trusted blindly - or not at all - never silently wrong.
 */
public final class GpuSearch implements AutoCloseable {

    public record Device(int index, String name, String vendor, int computeUnits, boolean gpu,
                         cl_platform_id platform, cl_device_id id) {
        @Override
        public String toString() {
            return name + " (" + computeUnits + " CU" + (gpu ? "" : ", CPU") + ")";
        }
    }

    public record Match(int x, int z, int rotation) {}

    public interface Progress {
        /** @return false to cancel the search. */
        boolean onProgress(long anchorsDone, long anchorsTotal, int matchCount, double anchorsPerSec);

        void onMatch(Match match);
    }

    private static final int MATCH_CAP_PER_TILE = 4096;

    /** Rows per kernel dispatch, adapted toward ~150ms per enqueue (display-watchdog safety). */
    private volatile int adaptiveSliceRows = 256;

    private final Device device;
    private cl_context context;
    private cl_command_queue queue;
    private cl_program program;
    private cl_kernel kernel;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public GpuSearch(Device device) {
        this.device = device;
        CL.setExceptionsEnabled(true);
        cl_context_properties props = new cl_context_properties();
        props.addProperty(CL.CL_CONTEXT_PLATFORM, device.platform());
        context = CL.clCreateContext(props, 1, new cl_device_id[]{device.id()}, null, null, null);
        queue = CL.clCreateCommandQueueWithProperties(context, device.id(), null, null);
        program = CL.clCreateProgramWithSource(context, 1, new String[]{loadKernelSource()}, null, null);
        CL.clBuildProgram(program, 0, null, null, null, null);
        kernel = CL.clCreateKernel(program, "search", null);
    }

    private static String loadKernelSource() {
        try (InputStream in = GpuSearch.class.getResourceAsStream("/kernel.cl")) {
            if (in == null) throw new IllegalStateException("kernel.cl missing from jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load kernel.cl", e);
        }
    }

    /** All OpenCL devices on the system, GPUs first, most compute units first. */
    public static List<Device> listDevices() {
        List<Device> out = new ArrayList<>();
        try {
            CL.setExceptionsEnabled(false);
            int[] numPlatforms = new int[1];
            CL.clGetPlatformIDs(0, null, numPlatforms);
            if (numPlatforms[0] == 0) return out;
            cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
            CL.clGetPlatformIDs(platforms.length, platforms, null);
            int index = 0;
            for (cl_platform_id platform : platforms) {
                int[] numDevices = new int[1];
                if (CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_ALL, 0, null, numDevices) != CL.CL_SUCCESS
                    || numDevices[0] == 0) continue;
                cl_device_id[] devices = new cl_device_id[numDevices[0]];
                CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_ALL, devices.length, devices, null);
                for (cl_device_id dev : devices) {
                    String name = deviceString(dev, CL.CL_DEVICE_NAME);
                    String vendor = deviceString(dev, CL.CL_DEVICE_VENDOR);
                    long[] type = new long[1];
                    CL.clGetDeviceInfo(dev, CL.CL_DEVICE_TYPE, Sizeof.cl_long, Pointer.to(type), null);
                    int[] cus = new int[1];
                    CL.clGetDeviceInfo(dev, CL.CL_DEVICE_MAX_COMPUTE_UNITS, Sizeof.cl_int, Pointer.to(cus), null);
                    boolean isGpu = (type[0] & CL.CL_DEVICE_TYPE_GPU) != 0;
                    out.add(new Device(index++, name.trim(), vendor.trim(), cus[0], isGpu, platform, dev));
                }
            }
        } catch (Throwable ignored) {
            // No OpenCL runtime installed: return what we have (possibly empty).
        } finally {
            CL.setExceptionsEnabled(true);
        }
        // Discrete GPUs first (CU counts are not comparable across vendors - a 20-CU discrete
        // NVIDIA beats a 32-CU integrated Intel), then by CU within the same class.
        out.sort(Comparator.comparing((Device d) -> !d.gpu())
            .thenComparing(d -> isIntegrated(d) ? 1 : 0)
            .thenComparing(d -> -d.computeUnits()));
        return out;
    }

    private static boolean isIntegrated(Device d) {
        String n = (d.vendor() + " " + d.name()).toLowerCase(java.util.Locale.ROOT);
        return n.contains("intel") && (n.contains("uhd") || n.contains("iris") || n.contains("hd graphics"));
    }

    private static String deviceString(cl_device_id device, int param) {
        long[] size = new long[1];
        CL.clGetDeviceInfo(device, param, 0, null, size);
        byte[] buf = new byte[(int) size[0]];
        CL.clGetDeviceInfo(device, param, buf.length, Pointer.to(buf), null);
        return new String(buf, 0, buf.length - 1, StandardCharsets.UTF_8);
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Runs the kernel on a small tile and compares EVERY anchor's result to the CPU reference.
     *
     * @return null on success, else a human-readable failure description
     */
    public String selfTest(PatternFile pattern, long facLo, long facHi) {
        final int span = 64;
        final int origin = -32;
        List<Match> gpu = runTile(pattern, facLo, facHi, origin, origin, span, span);
        if (gpu == null) return "self-test tile failed to execute";
        java.util.Set<Long> gpuSet = new java.util.HashSet<>();
        for (Match m : gpu) gpuSet.add(key(m.x(), m.z(), m.rotation()));
        for (int z = 0; z < span; z++) {
            for (int x = 0; x < span; x++) {
                for (int rot = 0; rot < 4; rot++) {
                    boolean want = CpuReference.matchesAt(pattern, facLo, facHi, origin + x, origin + z, rot);
                    boolean got = gpuSet.contains(key(origin + x, origin + z, rot));
                    if (want != got) {
                        return String.format("kernel disagrees with CPU at x=%d z=%d rot=%d (cpu=%b gpu=%b)",
                            origin + x, origin + z, rot, want, got);
                    }
                }
            }
        }
        return null;
    }

    private static long key(int x, int z, int rot) {
        return (((long) x & 0xFFFFFFL) << 26) | (((long) z & 0xFFFFFFL) << 2) | rot;
    }

    /**
     * Tiled search over the square region. Matches are CPU-verified before being surfaced.
     *
     * @return all verified matches, or null if cancelled mid-way (partials already delivered)
     */
    public List<Match> search(PatternFile pattern, long worldSeed, int centerX, int centerZ,
                              long radius, int tileSize, Progress progress) {
        cancelled.set(false);
        long[] fac = SeedChain.deriveSeeds(worldSeed, pattern.roof());
        long facLo = fac[0], facHi = fac[1];

        String selfTestError = selfTest(pattern, facLo, facHi);
        if (selfTestError != null) throw new IllegalStateException("GPU self-test FAILED: " + selfTestError);

        long minX = centerX - radius, maxX = centerX + radius;
        long minZ = centerZ - radius, maxZ = centerZ + radius;

        // Tile grid walked lazily in centre-out Chebyshev rings: a full-world search would need
        // ~54M tile objects if materialized into a sorted list (guaranteed OOM).
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
                // Edge rows sweep fully; inner rows only the two ring columns.
                int stepX = zEdge ? 1 : 2 * ring;
                for (int tx = centreTx - ring; tx <= centreTx + ring; tx += Math.max(1, stepX)) {
                    if (tx < 0 || tx >= countX) continue;
                    if (cancelled.get()) return null;
                    long x0 = minX + (long) tx * tileSize;
                    long z0 = minZ + (long) tz * tileSize;
                    int spanX = (int) Math.min(tileSize, maxX - x0 + 1);
                    int spanZ = (int) Math.min(tileSize, maxZ - z0 + 1);
                    List<Match> raw = runTile(pattern, facLo, facHi, (int) x0, (int) z0, spanX, spanZ);
                    if (raw == null) return null;
                    for (Match m : raw) {
                        // CPU verification: the GPU proposes, the reference disposes.
                        if (CpuReference.matchesAt(pattern, facLo, facHi, m.x(), m.z(), m.rotation())) {
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

    /** Executes the kernel on one tile; returns raw (unverified) matches or null when cancelled. */
    private List<Match> runTile(PatternFile pattern, long facLo, long facHi,
                                int originX, int originZ, int spanX, int spanZ) {
        // Flatten marked cells across layers: (dx, dz, layerIdx, wantBedrock).
        List<PatternFile.Layer> layers = pattern.layers();
        int cellCount = pattern.markedCells();
        int[] cellData = new int[cellCount * 4];
        int[] layerYs = new int[layers.size()];
        float[] thresholds = new float[layers.size()];
        int ci = 0;
        for (int li = 0; li < layers.size(); li++) {
            PatternFile.Layer layer = layers.get(li);
            layerYs[li] = layer.y();
            thresholds[li] = SeedChain.layerThreshold(layer.y(), pattern.roof());
            for (PatternFile.Cell cell : layer.cells()) {
                cellData[ci * 4] = cell.dx();
                cellData[ci * 4 + 1] = cell.dz();
                cellData[ci * 4 + 2] = li;
                cellData[ci * 4 + 3] = cell.bedrock() ? 1 : 0;
                ci++;
            }
        }

        cl_mem cellsBuf = null, layerYBuf = null, thresholdBuf = null, countBuf = null, matchBuf = null;
        try {
            cellsBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int4 * cellCount, Pointer.to(cellData), null);
            layerYBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int * layerYs.length, Pointer.to(layerYs), null);
            thresholdBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_float * thresholds.length, Pointer.to(thresholds), null);
            int[] zero = {0};
            countBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE | CL.CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(zero), null);
            matchBuf = CL.clCreateBuffer(context, CL.CL_MEM_WRITE_ONLY,
                (long) Sizeof.cl_int * 3 * MATCH_CAP_PER_TILE, null, null);

            int a = 0;
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_long, Pointer.to(new long[]{facLo}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_long, Pointer.to(new long[]{facHi}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellCount}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(layerYBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(thresholdBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{pattern.cols()}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{pattern.rows()}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(countBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(matchBuf));
            CL.clSetKernelArg(kernel, a, Sizeof.cl_int, Pointer.to(new int[]{MATCH_CAP_PER_TILE}));

            // Adaptive z-slice dispatches: each enqueue targets ~150ms so weak GPUs never hit
            // the OS display watchdog (~2s on Windows = driver reset).
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
                int[] data = new int[n * 3];
                CL.clEnqueueReadBuffer(queue, matchBuf, CL.CL_TRUE, 0,
                    (long) Sizeof.cl_int * data.length, Pointer.to(data), 0, null, null);
                for (int i = 0; i < n; i++) {
                    out.add(new Match(data[i * 3], data[i * 3 + 1], data[i * 3 + 2]));
                }
            }
            if (count[0] > MATCH_CAP_PER_TILE) {
                System.err.printf("warning: tile at %d,%d overflowed the match cap (%d) - pattern too weak%n",
                    originX, originZ, count[0]);
            }
            return out;
        } finally {
            release(cellsBuf, layerYBuf, thresholdBuf, countBuf, matchBuf);
        }
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
