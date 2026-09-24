package com.autism.seedcracker.bedrock;

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
import org.jocl.cl_platform_id;
import org.jocl.cl_program;

/**
 * In-game GPU backend for the Bedrock Finder (same OpenCL kernel as the standalone
 * bedrock-gpu-cracker tool). One work-item per anchor, early-exit cell loop, tiled dispatch to
 * dodge the display driver's ~2s watchdog - important here because the SAME GPU is rendering the
 * game, so tiles are kept smaller than the standalone tool's.
 *
 * Correctness: a 64x64 self-test tile is compared exhaustively against the CPU engine's verified
 * fast path before any real search, and every GPU match is CPU re-verified before it is
 * reported. {@link #availability()} probes for a usable device without throwing, so the screen
 * can grey the option out when no OpenCL runtime exists.
 */
public final class BedrockGpuEngine {

    /** Tile edge (bookkeeping granularity only - dispatches are z-sliced adaptively below). */
    private static final int TILE = 4096;
    private static final int MATCH_CAP_PER_TILE = 4096;
    private static final int MAX_MATCHES = 500;

    /** GPU load cap 10-100%: duty-cycles between dispatch slices so the game keeps rendering. */
    public static volatile int gpuLoadPercent = 60;

    /**
     * Rows per kernel dispatch, adapted to keep each dispatch under ~BUDGET ms: protects weak
     * GPUs from the OS display watchdog (~2s on Windows = driver reset / black screen) and keeps
     * the render thread's frame pacing intact on shared GPUs. Per-search (was a stale static).
     */
    private static final int SLICE_MIN_ROWS = 32, SLICE_MAX_ROWS = 4096;
    private static final long SLICE_BUDGET_MS = 150;

    private BedrockGpuEngine() {}

    // ---- device probe ----

    public record Device(String name, int computeUnits, boolean gpu,
                         cl_platform_id platform, cl_device_id id) {}

    private static volatile Device cachedDevice;
    private static volatile boolean probed;
    private static volatile String probeReason = "not probed yet";

    /** Best available device name, or null when no OpenCL runtime/device exists. Never throws. */
    public static String availability() {
        Device d = bestDevice();
        return d == null ? null : d.name() + " (" + d.computeUnits() + " CU)";
    }

    /** Why the GPU probe found no usable device (for the screen tooltip). */
    public static String probeFailureReason() {
        bestDevice();
        return probeReason;
    }

    private static synchronized Device bestDevice() {
        if (probed) return cachedDevice;
        probed = true;
        try {
            CL.setExceptionsEnabled(false);
            List<Device> found = new ArrayList<>();
            int[] numPlatforms = new int[1];
            int platErr = CL.clGetPlatformIDs(0, null, numPlatforms);
            if (numPlatforms[0] == 0) {
                probeReason = "no OpenCL platform (install your GPU vendor's driver)";
                return null;
            }
            cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
            CL.clGetPlatformIDs(platforms.length, platforms, null);
            int gpuPlatforms = 0;
            for (cl_platform_id platform : platforms) {
                int[] numDevices = new int[1];
                if (CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevices) != CL.CL_SUCCESS
                    || numDevices[0] == 0) continue;
                gpuPlatforms++;
                cl_device_id[] devices = new cl_device_id[numDevices[0]];
                CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, devices.length, devices, null);
                for (cl_device_id dev : devices) {
                    long[] size = new long[1];
                    CL.clGetDeviceInfo(dev, CL.CL_DEVICE_NAME, 0, null, size);
                    byte[] buf = new byte[(int) size[0]];
                    CL.clGetDeviceInfo(dev, CL.CL_DEVICE_NAME, buf.length, Pointer.to(buf), null);
                    int[] cus = new int[1];
                    CL.clGetDeviceInfo(dev, CL.CL_DEVICE_MAX_COMPUTE_UNITS, Sizeof.cl_int, Pointer.to(cus), null);
                    found.add(new Device(new String(buf, 0, buf.length - 1, StandardCharsets.UTF_8).trim(),
                        cus[0], true, platform, dev));
                }
            }
            // Discrete over integrated (CU counts are not comparable across vendors).
            found.sort(Comparator.comparing((Device d) -> isIntegrated(d) ? 1 : 0)
                .thenComparing(d -> -d.computeUnits()));
            cachedDevice = found.isEmpty() ? null : found.get(0);
            probeReason = cachedDevice != null ? "ok"
                : (gpuPlatforms == 0
                    ? "OpenCL runtime present but no GPU device (CPU-only driver?)"
                    : "no usable OpenCL GPU device");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError t) {
            cachedDevice = null;
            probeReason = "OpenCL runtime not installed (install your GPU vendor's driver)";
        } catch (Throwable t) {
            cachedDevice = null;
            probeReason = "probe failed: " + t.getClass().getSimpleName();
        } finally {
            try { CL.setExceptionsEnabled(true); } catch (Throwable ignored) {}
        }
        return cachedDevice;
    }

    private static boolean isIntegrated(Device d) {
        String n = d.name().toLowerCase(java.util.Locale.ROOT);
        return n.contains("intel") && (n.contains("uhd") || n.contains("iris") || n.contains("hd graphics"));
    }

    // ---- search ----

    /**
     * GPU-backed equivalent of {@link BedrockFinderEngine#findPatternMulti}. Same pattern
     * semantics ([col][row], 0/1/2), same Match type, same status/progress statics.
     * Falls back by THROWING with a clear message - callers should offer the CPU path.
     */
    public static List<BedrockFinderEngine.Match> findPatternMulti(
            long seed, int chunkRadius, int centerX, int centerZ,
            int[][][] layerPatterns, int[] layerYs, boolean roof,
            Consumer<BedrockFinderEngine.Match> onMatch) {
        Device device = bestDevice();
        if (device == null) {
            throw new IllegalStateException("No OpenCL GPU available - install your GPU vendor's driver, or use CPU mode.");
        }

        int layers = layerPatterns.length;
        int cols = layerPatterns[0].length;
        int rows = layerPatterns[0][0].length;
        long[] fac = deriveSeedsViaCpuEngine(seed, roof);

        // Flatten marked cells: (dx, dz, layerIdx, wantBedrock).
        List<int[]> cellList = new ArrayList<>();
        int[] ys = new int[layers];
        float[] thresholds = new float[layers];
        for (int l = 0; l < layers; l++) {
            ys[l] = layerYs[l];
            thresholds[l] = BedrockFinderEngine.layerThreshold(layerYs[l], roof);
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    int cell = layerPatterns[l][c][r];
                    if (cell == 0) continue;
                    cellList.add(new int[]{c, r, l, cell == 1 ? 1 : 0});
                }
            }
        }
        int cellCount = cellList.size();
        int[] cellData = new int[cellCount * 4];
        for (int i = 0; i < cellCount; i++) {
            int[] cell = cellList.get(i);
            cellData[i * 4] = cell[0];
            cellData[i * 4 + 1] = cell[1];
            cellData[i * 4 + 2] = cell[2];
            cellData[i * 4 + 3] = cell[3];
        }

        BedrockFinderEngine.isSearching = true;
        BedrockFinderEngine.cancelRequested = false;
        BedrockFinderEngine.currentProgress = 0f;
        BedrockFinderEngine.statusText = "GPU: compiling kernel...";

        cl_context context = null;
        cl_command_queue queue = null;
        cl_program program = null;
        cl_kernel kernel = null;
        List<BedrockFinderEngine.Match> results = new ArrayList<>();
        try {
            CL.setExceptionsEnabled(true);
            cl_context_properties props = new cl_context_properties();
            props.addProperty(CL.CL_CONTEXT_PLATFORM, device.platform());
            context = CL.clCreateContext(props, 1, new cl_device_id[]{device.id()}, null, null, null);
            queue = CL.clCreateCommandQueueWithProperties(context, device.id(), null, null);
            program = CL.clCreateProgramWithSource(context, 1, new String[]{loadKernelSource()}, null, null);
            CL.clBuildProgram(program, 0, null, null, null, null);
            kernel = CL.clCreateKernel(program, "search", null);

            // Self-test: kernel vs the CPU engine on a 64x64 tile around a negative origin.
            BedrockFinderEngine.statusText = "GPU: self-test...";
            String err = selfTest(context, queue, kernel, fac, cellData, cellCount, ys, thresholds,
                cols, rows, layerPatterns, layerYs, roof, seed);
            if (err != null) throw new IllegalStateException("GPU self-test failed: " + err);

            int minX = (centerX >> 4 << 4) - chunkRadius * 16;
            int maxX = (centerX >> 4 << 4) + chunkRadius * 16 + 15;
            int minZ = (centerZ >> 4 << 4) - chunkRadius * 16;
            int maxZ = (centerZ >> 4 << 4) + chunkRadius * 16 + 15;

            record Tile(int x0, int z0) {}
            List<Tile> tiles = new ArrayList<>();
            for (int tz = minZ; tz <= maxZ; tz += TILE) {
                for (int tx = minX; tx <= maxX; tx += TILE) {
                    tiles.add(new Tile(tx, tz));
                }
            }
            tiles.sort(Comparator.comparingLong(t ->
                Math.abs(t.x0() + TILE / 2L - centerX) + Math.abs(t.z0() + TILE / 2L - centerZ)));

            long anchorsTotal = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
            long anchorsDone = 0;
            long startMs = System.currentTimeMillis();

            for (Tile tile : tiles) {
                if (BedrockFinderEngine.cancelRequested || results.size() >= MAX_MATCHES) break;
                int spanX = Math.min(TILE, maxX - tile.x0() + 1);
                int spanZ = Math.min(TILE, maxZ - tile.z0() + 1);
                int[] raw = runTile(context, queue, kernel, fac, cellData, cellCount, ys, thresholds,
                    cols, rows, tile.x0(), tile.z0(), spanX, spanZ);
                for (int i = 0; i < raw.length; i += 3) {
                    // CPU verification via the (already vanilla-verified) CPU engine.
                    if (!cpuVerify(seed, raw[i], raw[i + 1], raw[i + 2], layerPatterns, layerYs, roof)) continue;
                    BedrockFinderEngine.Match m = new BedrockFinderEngine.Match(
                        raw[i], raw[i + 1], raw[i] >> 4, raw[i + 1] >> 4,
                        raw[i] & 15, raw[i + 1] & 15, "Rot " + raw[i + 2] * 90 + "\u00b0");
                    results.add(m);
                    if (onMatch != null) onMatch.accept(m);
                    if (results.size() >= MAX_MATCHES) break;
                }
                anchorsDone += (long) spanX * spanZ;
                BedrockFinderEngine.currentProgress = (float) ((double) anchorsDone / anchorsTotal);
                long elapsed = System.currentTimeMillis() - startMs;
                double rate = elapsed > 200 ? anchorsDone * 1000.0 / elapsed : 0;
                long etaSec = rate > 1 ? (long) ((anchorsTotal - anchorsDone) / rate) : -1;
                BedrockFinderEngine.statusText = String.format("GPU %d%% - %d match(es)%s",
                    (int) (BedrockFinderEngine.currentProgress * 100), results.size(),
                    etaSec >= 0 ? String.format(" - ETA %d:%02d", etaSec / 60, etaSec % 60) : "");
            }
        } finally {
            try { if (kernel != null) CL.clReleaseKernel(kernel); } catch (Throwable ignored) {}
            try { if (program != null) CL.clReleaseProgram(program); } catch (Throwable ignored) {}
            try { if (queue != null) CL.clReleaseCommandQueue(queue); } catch (Throwable ignored) {}
            try { if (context != null) CL.clReleaseContext(context); } catch (Throwable ignored) {}
            BedrockFinderEngine.isSearching = false;
            BedrockFinderEngine.currentProgress = 1f;
            BedrockFinderEngine.statusText = (BedrockFinderEngine.cancelRequested ? "Stopped: " : "Done: ")
                + results.size() + " match" + (results.size() == 1 ? "" : "es") + " (GPU)";
        }

        List<long[]> coords = new ArrayList<>(results.size());
        for (BedrockFinderEngine.Match m : results) coords.add(new long[]{m.x, m.z});
        BedrockFinderEngine.lastMatches = List.copyOf(coords);
        return results;
    }

    /** One-position re-check through the CPU engine's public single-layer path composition. */
    private static boolean cpuVerify(long seed, int ax, int az, int rot,
                                     int[][][] layerPatterns, int[] layerYs, boolean roof) {
        // Rotate each marked cell like the kernel does and evaluate via the vanilla factory
        // (slow path, but only runs per candidate match - a handful per search).
        var factory = BedrockFinderEngine.getBedrockSplitter(seed, roof ? "bedrock_roof" : "bedrock_floor");
        int cols = layerPatterns[0].length;
        int rows = layerPatterns[0][0].length;
        for (int l = 0; l < layerPatterns.length; l++) {
            float th = BedrockFinderEngine.layerThreshold(layerYs[l], roof);
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    int cell = layerPatterns[l][c][r];
                    if (cell == 0) continue;
                    int dx, dz;
                    switch (rot) {
                        case 1 -> { dx = rows - 1 - r; dz = c; }
                        case 2 -> { dx = cols - 1 - c; dz = rows - 1 - r; }
                        case 3 -> { dx = r; dz = cols - 1 - c; }
                        default -> { dx = c; dz = r; }
                    }
                    boolean bedrock = factory.at(ax + dx, layerYs[l], az + dz).nextFloat() < th;
                    if (bedrock != (cell == 1)) return false;
                }
            }
        }
        return true;
    }

    private static String selfTest(cl_context context, cl_command_queue queue, cl_kernel kernel,
                                   long[] fac, int[] cellData, int cellCount, int[] ys, float[] thresholds,
                                   int cols, int rows, int[][][] layerPatterns, int[] layerYs, boolean roof,
                                   long seed) {
        final int span = 64, origin = -32;
        int[] raw = runTile(context, queue, kernel, fac, cellData, cellCount, ys, thresholds,
            cols, rows, origin, origin, span, span);
        java.util.Set<Long> gpuSet = new java.util.HashSet<>();
        for (int i = 0; i < raw.length; i += 3) {
            gpuSet.add(((long) raw[i] & 0xFFFFFFL) << 26 | ((long) raw[i + 1] & 0xFFFFFFL) << 2 | raw[i + 2]);
        }
        // Reference via the inlined fast path (itself vanilla-verified by the CPU engine): the
        // vanilla-factory path here would allocate ~4M RandomSource objects per self-test.
        for (int z = 0; z < span; z++) {
            for (int x = 0; x < span; x++) {
                for (int rot = 0; rot < 4; rot++) {
                    boolean want = fastVerify(fac, origin + x, origin + z, rot, layerPatterns, layerYs, roof);
                    boolean got = gpuSet.contains(((long) (origin + x) & 0xFFFFFFL) << 26
                        | ((long) (origin + z) & 0xFFFFFFL) << 2 | rot);
                    if (want != got) {
                        return String.format("mismatch at x=%d z=%d rot=%d (cpu=%b gpu=%b)",
                            origin + x, origin + z, rot, want, got);
                    }
                }
            }
        }
        return null;
    }

    /** Allocation-free pattern check with the inlined chain (self-test reference). */
    private static boolean fastVerify(long[] fac, int ax, int az, int rot,
                                      int[][][] layerPatterns, int[] layerYs, boolean roof) {
        int cols = layerPatterns[0].length;
        int rows = layerPatterns[0][0].length;
        for (int l = 0; l < layerPatterns.length; l++) {
            float th = BedrockFinderEngine.layerThreshold(layerYs[l], roof);
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    int cell = layerPatterns[l][c][r];
                    if (cell == 0) continue;
                    int dx, dz;
                    switch (rot) {
                        case 1 -> { dx = rows - 1 - r; dz = c; }
                        case 2 -> { dx = cols - 1 - c; dz = rows - 1 - r; }
                        case 3 -> { dx = r; dz = cols - 1 - c; }
                        default -> { dx = c; dz = r; }
                    }
                    if (isBedrockInline(fac[0], fac[1], ax + dx, layerYs[l], az + dz, th) != (cell == 1)) return false;
                }
            }
        }
        return true;
    }

    private static boolean isBedrockInline(long facLo, long facHi, int x, int y, int z, float threshold) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        long lo = (l >> 16) ^ facLo;
        long hi = facHi;
        if ((lo | hi) == 0L) { lo = -7046029254386353131L; hi = 7640891576956012809L; }
        long n = Long.rotateLeft(lo + hi, 17) + lo;
        return (float) (n >>> 40) * 5.9604645E-8F < threshold;
    }

    /** Executes the kernel on one tile; returns raw (x, z, rot) triplets. */
    private static int[] runTile(cl_context context, cl_command_queue queue, cl_kernel kernel,
                                 long[] fac, int[] cellData, int cellCount, int[] ys, float[] thresholds,
                                 int cols, int rows, int originX, int originZ, int spanX, int spanZ) {
        cl_mem cellsBuf = null, layerYBuf = null, thresholdBuf = null, countBuf = null, matchBuf = null;
        try {
            cellsBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int4 * cellCount, Pointer.to(cellData), null);
            layerYBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int * ys.length, Pointer.to(ys), null);
            thresholdBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_float * thresholds.length, Pointer.to(thresholds), null);
            int[] zero = {0};
            countBuf = CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE | CL.CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(zero), null);
            matchBuf = CL.clCreateBuffer(context, CL.CL_MEM_WRITE_ONLY,
                (long) Sizeof.cl_int * 3 * MATCH_CAP_PER_TILE, null, null);

            int a = 0;
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_long, Pointer.to(new long[]{fac[0]}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_long, Pointer.to(new long[]{fac[1]}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{originZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanX}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{spanZ}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(cellsBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cellCount}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(layerYBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(thresholdBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{cols}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{rows}));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(countBuf));
            CL.clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(matchBuf));
            CL.clSetKernelArg(kernel, a, Sizeof.cl_int, Pointer.to(new int[]{MATCH_CAP_PER_TILE}));

            // Adaptive z-slice dispatches: each enqueue targets SLICE_BUDGET_MS so a weak/shared
            // GPU never hits the display watchdog, and load<100% idles between slices.
            // Local, starting conservative each tile (the old static carried a stale calibration
            // across searches / GPU switches).
            int adaptiveSliceRows = 256;
            int zOff = 0;
            while (zOff < spanZ) {
                if (BedrockFinderEngine.cancelRequested) break;
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
            int[] data = new int[n * 3];
            if (n > 0) {
                CL.clEnqueueReadBuffer(queue, matchBuf, CL.CL_TRUE, 0,
                    (long) Sizeof.cl_int * data.length, Pointer.to(data), 0, null, null);
            }
            return data;
        } finally {
            for (cl_mem b : new cl_mem[]{cellsBuf, layerYBuf, thresholdBuf, countBuf, matchBuf}) {
                if (b != null) {
                    try { CL.clReleaseMemObject(b); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static long roundUp(long v, long multiple) {
        return ((v + multiple - 1) / multiple) * multiple;
    }

    /** Same derivation as the CPU engine's fast path (already runtime-verified against vanilla). */
    private static long[] deriveSeedsViaCpuEngine(long seed, boolean roof) {
        try {
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            long sl = seed ^ 0x6A09E667F3BCC909L;
            long sh = sl + -7046029254386353131L;
            long[] st = {mix(sl), mix(sh)};
            if ((st[0] | st[1]) == 0L) { st[0] = -7046029254386353131L; st[1] = 7640891576956012809L; }
            long fLo = xoro(st), fHi = xoro(st);
            byte[] hash = md5.digest(("minecraft:" + (roof ? "bedrock_roof" : "bedrock_floor"))
                .getBytes(StandardCharsets.UTF_8));
            long hLo = beLong(hash, 0), hHi = beLong(hash, 8);
            long[] st2 = {hLo ^ fLo, hHi ^ fHi};
            if ((st2[0] | st2[1]) == 0L) { st2[0] = -7046029254386353131L; st2[1] = 7640891576956012809L; }
            return new long[]{xoro(st2), xoro(st2)};
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * -4658895280553007687L;
        z = (z ^ (z >>> 27)) * -7723592293110705685L;
        return z ^ (z >>> 31);
    }

    private static long xoro(long[] s) {
        long l = s[0], m = s[1];
        long n = Long.rotateLeft(l + m, 17) + l;
        m ^= l;
        s[0] = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
        s[1] = Long.rotateLeft(m, 28);
        return n;
    }

    private static long beLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static String loadKernelSource() {
        try (InputStream in = BedrockGpuEngine.class.getResourceAsStream("/assets/seedcrackerx/bedrock_kernel.cl")) {
            if (in == null) throw new IllegalStateException("bedrock_kernel.cl missing from jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load bedrock kernel", e);
        }
    }
}
