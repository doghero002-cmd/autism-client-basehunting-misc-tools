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
    private final BoolSetting syncEsp = add(new BoolSetting("sync-esp", "Match Storage ESP", true)
        .description("Auto-use the client Storage ESP module's selected block types AND per-type colours for recording + rendering. Off (or Storage ESP missing) = builtin chest/barrel/shulker/hopper list with the colours above.")
        .group("Record"));
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
    /** ESP-synced kinds: KIND_ESP_BASE + colour-class index (class order mirrors the client's ColorSet). */
    private static final byte KIND_ESP_BASE = 10;

    // ---- Storage ESP sync (targets + colours mirrored from the client's storage-esp module) ----
    private static final String[] ESP_COLOR_OPTIONS = {
        "trapped-chest-color", "chest-color", "ender-chest-color", "barrel-color", "shulker-color",
        "hopper-color", "dispenser-color", "furnace-color", "crafter-color", "other-color"
    };
    private static final int[] ESP_COLOR_DEFAULTS = {
        0xCCFF2020, 0xCCFFA000, 0xCC7800FF, 0xCCFFA000, 0xCCB766FF,
        0xCC7C8AFF, 0xCCB04848, 0xCCCCB266, 0xCCD8AE6B, 0xFF8C8C8C
    };
    private final int[] espColors = ESP_COLOR_DEFAULTS.clone();
    private java.util.Map<Block, Byte> espKinds = java.util.Map.of();
    private boolean espShulkers = false;
    private boolean espAvailable = false;
    private long espRefreshAtMs = 0;

    /** Re-read the Storage ESP module's target list + colours (throttled to 2x/s). */
    private void refreshEspSync() {
        long now = System.currentTimeMillis();
        if (now < espRefreshAtMs) return;
        espRefreshAtMs = now + 500;
        espAvailable = false;
        if (!syncEsp.get()) return;
        try {
            autismclient.modules.Module esp = autismclient.modules.ModuleRegistry.get("storage-esp");
            if (esp == null) return;
            for (int i = 0; i < ESP_COLOR_OPTIONS.length; i++) {
                espColors[i] = autismclient.modules.ModuleRenderUtil.color(esp, ESP_COLOR_OPTIONS[i], ESP_COLOR_DEFAULTS[i]);
            }
            String list = esp.value("storage-list");
            java.util.Map<Block, Byte> kinds = new java.util.HashMap<>();
            boolean shulkers = false;
            if (list != null) {
                for (String raw : list.split("\\|")) {
                    switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                        case "minecraft:trapped_chest" -> kinds.put(Blocks.TRAPPED_CHEST, (byte) (KIND_ESP_BASE));
                        case "minecraft:chest" -> kinds.put(Blocks.CHEST, (byte) (KIND_ESP_BASE + 1));
                        case "minecraft:ender_chest" -> kinds.put(Blocks.ENDER_CHEST, (byte) (KIND_ESP_BASE + 2));
                        case "minecraft:barrel" -> kinds.put(Blocks.BARREL, (byte) (KIND_ESP_BASE + 3));
                        case "minecraft:shulker_box" -> shulkers = true;
                        case "minecraft:hopper" -> kinds.put(Blocks.HOPPER, (byte) (KIND_ESP_BASE + 5));
                        case "minecraft:dispenser" -> kinds.put(Blocks.DISPENSER, (byte) (KIND_ESP_BASE + 6));
                        case "minecraft:dropper" -> kinds.put(Blocks.DROPPER, (byte) (KIND_ESP_BASE + 6));
                        case "minecraft:furnace" -> kinds.put(Blocks.FURNACE, (byte) (KIND_ESP_BASE + 7));
                        case "minecraft:smoker" -> kinds.put(Blocks.SMOKER, (byte) (KIND_ESP_BASE + 7));
                        case "minecraft:blast_furnace" -> kinds.put(Blocks.BLAST_FURNACE, (byte) (KIND_ESP_BASE + 7));
                        case "minecraft:brewing_stand" -> kinds.put(Blocks.BREWING_STAND, (byte) (KIND_ESP_BASE + 7));
                        case "minecraft:crafter" -> kinds.put(Blocks.CRAFTER, (byte) (KIND_ESP_BASE + 8));
                        case "minecraft:decorated_pot" -> kinds.put(Blocks.DECORATED_POT, (byte) (KIND_ESP_BASE + 8));
                        case "minecraft:chiseled_bookshelf" -> kinds.put(Blocks.CHISELED_BOOKSHELF, (byte) (KIND_ESP_BASE + 8));
                        case "minecraft:campfire" -> {
                            kinds.put(Blocks.CAMPFIRE, (byte) (KIND_ESP_BASE + 8));
                            kinds.put(Blocks.SOUL_CAMPFIRE, (byte) (KIND_ESP_BASE + 8));
                        }
                        default -> { } // entity targets (minecarts/boats) - not block entities, skip
                    }
                }
            }
            espKinds = kinds;
            espShulkers = shulkers;
            espAvailable = true;
        } catch (Throwable ignored) {
            espAvailable = false;
        }
    }

    /** True when the block matches the active target set (ESP-synced or builtin). */
    private boolean matchesTargets(Block b) {
        if (espAvailable) {
            if (b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) return espShulkers;
            return espKinds.containsKey(b);
        }
        return isStorageBlock(b);
    }

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
        cachedFeeds = null;
        clearFeeds();
    }

    private void clearFeeds() {
        BlockEspRenderer.clear(id() + ":live");
        BlockEspRenderer.clear(id() + ":ghost");
        for (int i = 0; i < espColors.length; i++) {
            BlockEspRenderer.clear(id() + ":live-" + i);
            BlockEspRenderer.clear(id() + ":ghost-" + i);
        }
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
            cachedFeeds = null;
            loadedKey = key;
            if (persist.get()) loadRecordFile();
        }

        refreshEspSync();
        sweepChunks(mc);
        // Re-split/sort every 5 ticks (boxes are static; the live/ghost split changes on
        // chunk-load timescales) but FEED every tick - the renderer TTL is 300ms and a single
        // slow tick past a 5-tick feed gap would flicker the boxes.
        if (++renderTicks >= 5 || cachedFeeds == null) {
            renderTicks = 0;
            rebuildRenderLists(mc);
        }
        for (FeedGroup g : cachedFeeds) {
            BlockEspRenderer.feedBoxes(id() + ":" + g.key, g.boxes, g.argb);
        }

        // Debounced autosave (records grow while flying/tunneling; don't write every tick).
        if (persist.get() && dirtyAtMs != 0L && System.currentTimeMillis() - dirtyAtMs > 10_000L) {
            saveRecord();
            dirtyAtMs = 0L;
        }
    }

    private int renderTicks = 0;
    private List<FeedGroup> cachedFeeds;

    private static final class FeedGroup {
        final String key;
        final List<AABB> boxes = new ArrayList<>();
        final int argb;
        FeedGroup(String key, int argb) { this.key = key; this.argb = argb; }
    }

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
            boolean gone = !matchesTargets(b) && !(spawners.get() && b == Blocks.SPAWNER);
            if (gone) dirtyAtMs = System.currentTimeMillis();
            return gone;
        });
    }

    private byte classify(BlockEntity be) {
        if (be == null) return -1;
        if (be instanceof SpawnerBlockEntity) return spawners.get() ? KIND_SPAWNER : -1;
        Block b = be.getBlockState().getBlock();
        if (espAvailable) {
            if (b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                return espShulkers ? (byte) (KIND_ESP_BASE + 4) : -1;
            }
            Byte k = espKinds.get(b);
            return k != null ? k : -1;
        }
        return isStorageBlock(b) ? KIND_STORAGE : -1;
    }

    private static boolean isStorageBlock(Block b) {
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
            || b == Blocks.ENDER_CHEST || b == Blocks.HOPPER
            || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    /** Split the record into per-colour live/ghost feed groups, nearest-first capped. */
    private void rebuildRenderLists(Minecraft mc) {
        if (record.isEmpty()) {
            cachedFeeds = List.of();
            return;
        }
        BlockPos eye = mc.player.blockPosition();
        int cap = maxRender.get();
        List<long[]> sorted = new ArrayList<>(record.size()); // [distSq, packedPos, kind]
        for (Map.Entry<Long, Byte> e : record.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            sorted.add(new long[]{ (long) pos.distSqr(eye), e.getKey(), e.getValue() });
        }
        sorted.sort(java.util.Comparator.comparingLong(a -> a[0]));

        java.util.Map<String, FeedGroup> groups = new java.util.LinkedHashMap<>();
        boolean showGhosts = ghosts.get();
        for (int i = 0; i < sorted.size() && i < cap; i++) {
            long[] row = sorted.get(i);
            BlockPos pos = BlockPos.of(row[1]);
            byte kind = (byte) row[2];
            boolean loaded = mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (!loaded && !showGhosts) continue;
            String gkey;
            int argb;
            if (espAvailable && kind >= KIND_ESP_BASE) {
                int cls = Math.min(kind - KIND_ESP_BASE, espColors.length - 1);
                // Ghosts reuse the ESP hue, dimmed, so types stay tellable-apart when unloaded.
                gkey = (loaded ? "live-" : "ghost-") + cls;
                argb = loaded ? espColors[cls] : ((espColors[cls] & 0x00FFFFFF) | 0x66000000);
            } else {
                gkey = loaded ? "live" : "ghost";
                argb = loaded ? liveColor.get() : ghostColor.get();
            }
            groups.computeIfAbsent(gkey, k -> new FeedGroup(k, argb)).boxes.add(new AABB(pos));
        }
        cachedFeeds = List.copyOf(groups.values());
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
        cachedFeeds = null;
        if (loadedKey != null) {
            try { java.nio.file.Files.deleteIfExists(recordFile()); } catch (Throwable ignored) {}
        }
        clearFeeds();
        AutismClientMessaging.sendPrefixed("Storage Recorder: record cleared.");
    }

    @Override
    public String info() {
        return record.size() + " recorded";
    }
}
