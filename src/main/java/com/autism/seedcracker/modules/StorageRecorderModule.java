package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.ActionSetting;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Storage Recorder.
 *
 * Remembers every storage block / spawner it sees and keeps rendering them as ghost boxes after
 * the server unloads or re-hides the chunks - so freecam/ESP can still show a found base's layout
 * once the anti-xray re-hides it. Entries are only dropped when a LOADED chunk shows the block is
 * actually gone (mined). Persists per server+dimension so the record survives relogs (pairs with
 * Relog Loader).
 */
public final class StorageRecorderModule extends Module {

    private final BoolSetting ghosts = add(new BoolSetting("ghosts", "Ghost boxes", true)
        .description("Keep drawing recorded storage in UNLOADED chunks (dimmed ghost boxes).")
        .group("Render"));
    private final ColorSetting liveColor = add(new ColorSetting("live-color", "Live color", 0xFF3BD7FF)
        .description("Box color for storage in currently loaded chunks.")
        .group("Render"));
    private final ColorSetting ghostColor = add(new ColorSetting("ghost-color", "Ghost color", 0x8095A5FF)
        .description("Box color for remembered storage in unloaded/re-hidden chunks.")
        .group("Render"));
    private final IntSetting maxRender = add(new IntSetting("max-render", "Max rendered boxes", 2048, 64, 8192, 64)
        .description("Nearest-first cap on rendered boxes (keeps FPS stable on huge records).")
        .group("Render"));
    private final BoolSetting spawners = add(new BoolSetting("spawners", "Record spawners", true)
        .description("Record monster spawners too.")
        .group("Record"));
    private final BoolSetting persist = add(new BoolSetting("persist", "Persist to disk", true)
        .description("Save the record per server+dimension so it survives relogs.")
        .group("Record"));
    private final IntSetting scanChunks = add(new IntSetting("chunks-per-tick", "Chunks per tick", 16, 1, 64, 1)
        .description("Loaded chunks swept per tick for storage block entities.")
        .group("Record"));
    private final ActionSetting clear = add(new ActionSetting("clear", "Clear record", this::clearRecord)
        .buttonLabel("Clear").description("Forget everything recorded for this server+dimension.")
        .group("Record"));

    /** Recorded storage: packed BlockPos -> block kind ordinal (for color only; kind is cosmetic). */
    private final Map<Long, Byte> record = new ConcurrentHashMap<>();
    private String loadedKey = null;
    private int sweepX = Integer.MIN_VALUE, sweepZ; // rolling chunk sweep cursor
    private long dirtyAtMs = 0L;

    private static final byte KIND_STORAGE = 0;
    private static final byte KIND_SPAWNER = 1;

    public StorageRecorderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":storage-recorder", "Storage Recorder", category,
            "Remembers storage/spawners it saw and ghost-renders them after the server re-hides the chunks.");
    }

    @Override
    public void onEnable() {
        BlockEspRenderer.init();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            setEnabledSilently(false);
            return;
        }
        loadRecord(mc);
    }

    @Override
    public void onDisable() {
        if (persist.get()) saveRecord();
        record.clear();
        loadedKey = null;
        cachedLive = null;
        cachedGhost = null;
        BlockEspRenderer.clear(id() + ":live");
        BlockEspRenderer.clear(id() + ":ghost");
    }

    @Override
    public void onGameLeft() {
        if (persist.get()) saveRecord();
        record.clear();
        loadedKey = null;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Dimension/server switch: swap the record file.
        String key = recordKey(mc);
        if (!key.equals(loadedKey)) {
            if (loadedKey != null && persist.get()) saveRecord();
            record.clear();
            cachedLive = null;
            cachedGhost = null;
            loadedKey = key;
            if (persist.get()) loadRecordFile();
        }

        sweepChunks(mc);
        // Re-split/sort every 5 ticks (boxes are static; the live/ghost split changes on
        // chunk-load timescales) but FEED every tick - the renderer TTL is 300ms and a single
        // slow tick past a 5-tick feed gap would flicker the boxes.
        if (++renderTicks >= 5 || cachedLive == null) {
            renderTicks = 0;
            rebuildRenderLists(mc);
        }
        BlockEspRenderer.feedBoxes(id() + ":live", cachedLive, liveColor.get());
        BlockEspRenderer.feedBoxes(id() + ":ghost", cachedGhost, ghostColor.get());

        // Debounced autosave (records grow while flying/tunneling; don't write every tick).
        if (persist.get() && dirtyAtMs != 0L && System.currentTimeMillis() - dirtyAtMs > 10_000L) {
            saveRecord();
            dirtyAtMs = 0L;
        }
    }

    private int renderTicks = 0;
    private List<AABB> cachedLive;
    private List<AABB> cachedGhost;

    /** Rolling sweep: scanChunks loaded chunks per tick, recording + pruning against reality. */
    private void sweepChunks(Minecraft mc) {
        int vd = mc.options.getEffectiveRenderDistance();
        ChunkPos center = mc.player.chunkPosition();
        int side = vd * 2 + 1;
        if (sweepX == Integer.MIN_VALUE) { sweepX = 0; sweepZ = 0; }
        int budget = scanChunks.get();
        java.util.Map<Long, LevelChunk> swept = new java.util.HashMap<>();
        for (int i = 0; i < side * side && budget > 0; i++) {
            int cx = center.x() + (sweepX - vd);
            int cz = center.z() + (sweepZ - vd);
            if (++sweepX >= side) { sweepX = 0; if (++sweepZ >= side) sweepZ = 0; }
            if (!mc.level.hasChunk(cx, cz)) continue;
            budget--;
            LevelChunk chunk = mc.level.getChunk(cx, cz);
            swept.put(chunk.getPos().pack(), chunk);

            // Record what's here now.
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                byte kind = classify(e.getValue());
                if (kind < 0) continue;
                if (record.put(e.getKey().asLong(), kind) == null) dirtyAtMs = System.currentTimeMillis();
            }
        }
        if (swept.isEmpty()) return;
        // ONE prune pass for all swept chunks (a full-map removeIf per chunk was 16 passes/tick).
        // Entries in unloaded chunks are untouched - that's the whole point of the recorder.
        record.keySet().removeIf(packed -> {
            BlockPos pos = BlockPos.of(packed);
            LevelChunk chunk = swept.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
            if (chunk == null) return false;
            Block b = chunk.getBlockState(pos).getBlock();
            boolean gone = !isStorageBlock(b) && !(spawners.get() && b == Blocks.SPAWNER);
            if (gone) dirtyAtMs = System.currentTimeMillis();
            return gone;
        });
    }

    private byte classify(BlockEntity be) {
        if (be == null) return -1;
        if (be instanceof SpawnerBlockEntity) return spawners.get() ? KIND_SPAWNER : -1;
        Block b = be.getBlockState().getBlock();
        return isStorageBlock(b) ? KIND_STORAGE : -1;
    }

    private static boolean isStorageBlock(Block b) {
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
            || b == Blocks.ENDER_CHEST || b == Blocks.HOPPER
            || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    /** Split the record into live (loaded chunk) and ghost (unloaded) boxes, nearest-first capped. */
    private void rebuildRenderLists(Minecraft mc) {
        if (record.isEmpty()) {
            cachedLive = List.of();
            cachedGhost = List.of();
            return;
        }
        BlockPos eye = mc.player.blockPosition();
        int cap = maxRender.get();
        List<long[]> sorted = new ArrayList<>(record.size()); // [distSq, packedPos]
        for (Long packed : record.keySet()) {
            BlockPos pos = BlockPos.of(packed);
            sorted.add(new long[]{ (long) pos.distSqr(eye), packed });
        }
        sorted.sort(java.util.Comparator.comparingLong(a -> a[0]));

        List<AABB> live = new ArrayList<>();
        List<AABB> ghost = new ArrayList<>();
        boolean showGhosts = ghosts.get();
        for (int i = 0; i < sorted.size() && i < cap; i++) {
            BlockPos pos = BlockPos.of(sorted.get(i)[1]);
            boolean loaded = mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (loaded) live.add(new AABB(pos));
            else if (showGhosts) ghost.add(new AABB(pos));
        }
        cachedLive = live;
        cachedGhost = ghost;
    }

    // ---- persistence ----

    private String recordKey(Minecraft mc) {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        String dim = mc.level.dimension().identifier().toString();
        return (server + "_" + dim).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private java.nio.file.Path recordFile() {
        return autismclient.AutismClientAddon.FOLDER.toPath()
            .resolve("storage-recorder").resolve(loadedKey + ".txt");
    }

    private void loadRecord(Minecraft mc) {
        loadedKey = recordKey(mc);
        record.clear();
        if (persist.get()) loadRecordFile();
    }

    private void loadRecordFile() {
        try {
            java.nio.file.Path f = recordFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] p = line.trim().split(",");
                if (p.length != 2) continue;
                record.put(Long.parseLong(p[0]), Byte.parseByte(p[1]));
            }
        } catch (Throwable ignored) {}
    }

    private void saveRecord() {
        if (loadedKey == null) return;
        try {
            java.nio.file.Path f = recordFile();
            java.nio.file.Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>(record.size());
            for (Map.Entry<Long, Byte> e : record.entrySet()) lines.add(e.getKey() + "," + e.getValue());
            java.nio.file.Files.write(f, lines);
        } catch (Throwable ignored) {}
    }

    private void clearRecord() {
        record.clear();
        cachedLive = null;
        cachedGhost = null;
        if (loadedKey != null) {
            try { java.nio.file.Files.deleteIfExists(recordFile()); } catch (Throwable ignored) {}
        }
        BlockEspRenderer.clear(id() + ":live");
        BlockEspRenderer.clear(id() + ":ghost");
        AutismClientMessaging.sendPrefixed("Storage Recorder: record cleared.");
    }

    @Override
    public String info() {
        return record.size() + " recorded";
    }
}
