package com.autism.seedcracker.motion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk + memory cache of every chunk the player has seen, so the pathfinder can plan routes through
 * areas now outside render distance (the gap Baritone fills with its ICachedWorld). Chunks are stored
 * as compact global block-state ids ({@link Block#getId}) per section, which round-trips losslessly
 * through {@code Block.BLOCK_STATE_REGISTRY.byId}.
 *
 * Write path: {@link #recordLoaded} captures a chunk when it loads / the player moves through it;
 * {@link #flush} writes dirty chunks to {@code config/autism/chunkcache/<dim>/<regionX>,<regionZ>.bin}.
 * Read path: {@link #state} returns the cached block at any position, or null if the chunk was never seen.
 * {@link ChunkSnapshot} consults it for chunks not in the loaded grid.
 */
public final class ChunkCache {
    private ChunkCache() {}

    /** One chunk = up to 24 sections (world height / 16); each section is 16x16x16 global-state ids. */
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int MAX_SECTIONS = 24;
    private static final int MAGIC = 0x4343484B; // "CCHK"
    private static final int VERSION = 1;

    /** Pack chunk coords the same way the rest of the codebase does (x high, z low). */
    private static long pack(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    private static int unpackX(long p) { return (int) (p >> 32); }
    private static int unpackZ(long p) { return (int) p; }

    /** chunkPos (long) -> sections of global-state ids (null entry = section all air). */
    private static final Map<Long, short[][]> memory = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private static String worldKey;
    private static Path dir;
    private static int minY;
    private static int sectionCount;

    /** Call on world join: pick the storage dir for this world+dimension and reset state. */
    public static synchronized void attach(Minecraft mc) {
        memory.clear();
        dirty.clear();
        regionsTried.clear();
        if (mc.level == null) { worldKey = null; dir = null; return; }
        minY = mc.level.getMinY();
        sectionCount = mc.level.getSectionsCount();
        String dim = mc.level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        String world = "singleplayer";
        try {
            if (mc.getSingleplayerServer() != null) world = mc.getSingleplayerServer().getWorldData().getLevelName();
            else if (mc.getCurrentServer() != null) world = mc.getCurrentServer().ip.replace(':', '_').replace('/', '_');
        } catch (Throwable ignored) {}
        worldKey = world + "_" + dim;
        dir = mc.gameDirectory.toPath().resolve("config").resolve("autism").resolve("chunkcache").resolve(worldKey);
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
    }

    /** Call when leaving a world: flush any unsaved chunks. */
    public static synchronized void detach() {
        flush();
        memory.clear();
        dirty.clear();
        regionsTried.clear();
        worldKey = null;
        dir = null;
    }

    /** Record a chunk the player can currently see (call on the game thread when it loads / periodically). */
    public static void recordLoaded(Minecraft mc, LevelChunk chunk) {
        if (worldKey == null || chunk == null || chunk.isEmpty()) return;
        long cp = chunk.getPos().pack();
        LevelChunkSection[] secs = chunk.getSections();
        short[][] ids = new short[secs.length][];
        boolean any = false;
        int baseSecY = mc.level.getMinSectionY();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection sec = secs[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            short[] arr = new short[SECTION_VOLUME];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState s = sec.getBlockState(x, y, z);
                arr[(y << 8) | (z << 4) | x] = (short) Block.getId(s);
            }
            ids[i] = arr;
            any = true;
        }
        if (!any) return;
        memory.put(cp, ids);
        dirty.add(cp);
    }

    /** Cached block at a world position, or null if that chunk was never seen. */
    public static BlockState state(int x, int y, int z) {
        long cp = pack(x >> 4, z >> 4);
        short[][] ids = memory.get(cp);
        if (ids == null) return null;
        int secIdx = (y - minY) >> 4;
        if (secIdx < 0 || secIdx >= ids.length) return Blocks.AIR.defaultBlockState();
        short[] arr = ids[secIdx];
        if (arr == null) return Blocks.AIR.defaultBlockState();
        int id = arr[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] & 0xFFFF;
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(id);
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    /** Whether we've ever recorded this chunk (loaded or not). Memory only — regions are preloaded at plan
     * start by {@link #preloadBetween}, never read off disk per-chunk here (that hung the planner thread). */
    public static boolean hasChunk(int cx, int cz) {
        return memory.containsKey(pack(cx, cz));
    }

    /** Single background thread for region preloading (kept off the game thread). */
    private static final java.util.concurrent.ExecutorService PRELOAD =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "seedcracker-chunkcache-preload");
            t.setDaemon(true);
            return t;
        });

    /**
     * Batch-load every region covering the loaded area around the player PLUS the corridor to the goal, so
     * {@link #hasChunk}/{@link #state} can serve beyond-render-distance lookups from memory with no per-chunk
     * disk reads. Runs on a background thread: doing this on the game thread stalled it ~40s for far goals
     * (many region files of disk I/O). {@link #state} already treats a not-yet-loaded chunk as UNLOADED, so the
     * planner simply re-plans once it actually gets there — the preload is an optimization, never required.
     */
    public static void preloadBetween(int startCx, int startCz, int goalCx, int goalCz, int padRegions) {
        if (worldKey == null || dir == null) return;
        int minRx = Math.min(startCx, goalCx) - padRegions, maxRx = Math.max(startCx, goalCx) + padRegions;
        int minRz = Math.min(startCz, goalCz) - padRegions, maxRz = Math.max(startCz, goalCz) + padRegions;
        PRELOAD.execute(() -> {
            for (int rx = minRx >> 5; rx <= maxRx >> 5; rx++)
                for (int rz = minRz >> 5; rz <= maxRz >> 5; rz++)
                    loadRegionIntoMemory(rx, rz);
        });
    }

    private static Path regionFile(int regionX, int regionZ) {
        return dir.resolve(regionX + "," + regionZ + ".bin");
    }

    /** Write dirty chunks to disk (region files of 32x32 chunks). Cheap enough to call every few seconds. */
    public static synchronized void flush() {
        if (worldKey == null || dir == null || dirty.isEmpty()) return;
        Map<Long, short[][]> toWrite = new HashMap<>();
        for (Long cp : dirty) {
            short[][] ids = memory.get(cp);
            if (ids != null) toWrite.put(cp, ids);
        }
        dirty.clear();
        // Group by region (32x32 chunks) so each file holds a contiguous area.
        Map<Long, Map<Long, short[][]>> byRegion = new HashMap<>();
        for (var e : toWrite.entrySet()) {
            long cp = e.getKey();
            int cx = unpackX(cp), cz = unpackZ(cp);
            long rp = pack(cx >> 5, cz >> 5);
            byRegion.computeIfAbsent(rp, k -> new HashMap<>()).put(cp, e.getValue());
        }
        for (var re : byRegion.entrySet()) {
            int rx = unpackX(re.getKey()), rz = unpackZ(re.getKey());
            // Load any existing chunks in this region file first so we merge, not overwrite.
            Map<Long, short[][]> merged = readRegion(rx, rz);
            merged.putAll(re.getValue());
            writeRegion(rx, rz, merged);
        }
    }

    private static void writeRegion(int rx, int rz, Map<Long, short[][]> chunks) {
        Path f = regionFile(rx, rz);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(f))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(chunks.size());
            for (var e : chunks.entrySet()) {
                out.writeLong(e.getKey());
                short[][] ids = e.getValue();
                out.writeInt(ids.length);
                for (short[] arr : ids) {
                    if (arr == null) { out.writeInt(0); continue; }
                    out.writeInt(arr.length);
                    for (short v : arr) out.writeShort(v);
                }
            }
        } catch (IOException ignored) {}
    }

    private static Map<Long, short[][]> readRegion(int rx, int rz) {
        Map<Long, short[][]> out = new HashMap<>();
        Path f = regionFile(rx, rz);
        if (!Files.exists(f)) return out;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            if (in.readInt() != MAGIC) return out;
            in.readInt(); // version
            int count = in.readInt();
            for (int c = 0; c < count; c++) {
                long cp = in.readLong();
                int secLen = in.readInt();
                short[][] ids = new short[secLen][];
                for (int s = 0; s < secLen; s++) {
                    int len = in.readInt();
                    if (len == 0) continue;
                    short[] arr = new short[len];
                    for (int i = 0; i < len; i++) arr[i] = in.readShort();
                    ids[s] = arr;
                }
                out.put(cp, ids);
            }
        } catch (IOException ignored) {}
        return out;
    }

    /** Regions we've already tried to load from disk (hit or miss): never re-read the same region twice. */
    private static final java.util.Set<Long> regionsTried = ConcurrentHashMap.newKeySet();

    /** Load a region's chunks from disk into memory (on demand when the planner needs that area). */
    public static synchronized void loadRegionIntoMemory(int regionX, int regionZ) {
        if (worldKey == null || dir == null) return;
        long rp = pack(regionX, regionZ);
        if (!regionsTried.add(rp)) return; // already loaded (or already known-missing)
        Map<Long, short[][]> r = readRegion(regionX, regionZ);
        memory.putAll(r);
    }
}
