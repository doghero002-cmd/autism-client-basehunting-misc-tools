// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded, client-thread snapshot of Spectre's raw block-entity evidence, not base proof. */
public final class BlockEntityLeaks {
    public static final int MAX_CHUNKS = 1024;
    public static final int MAX_PER_CHUNK = 512;
    public static final int MAX_ENTRIES = 8192;
    private final LinkedHashMap<Chunk, LinkedHashMap<Position, Entry>> chunks = new LinkedHashMap<>();
    private Object world;
    private String dimension;
    private int size;
    private long revision;

    public record Chunk(int x, int z) {}
    public record Position(int x, int y, int z) {
        public Chunk chunk() { return new Chunk(x >> 4, z >> 4); }
        public boolean valid() {
            return x >= -30_000_000 && x <= 30_000_000 && z >= -30_000_000 && z <= 30_000_000
                && y >= -2048 && y < 2048;
        }
    }
    public record Spawner(String entityId, int activationRange, int delay, boolean activationKnown) {
        public Spawner(String entityId, int activationRange, int delay) { this(entityId, activationRange, delay, true); }
        public Spawner {
            if (entityId == null || entityId.length() > 256 || !entityId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) entityId = "";
            if (activationRange < -1 || activationRange > Short.MAX_VALUE) {
                activationRange = 16;
                activationKnown = false;
            }
            if (delay < -1 || delay > Short.MAX_VALUE) delay = -1;
        }
        public static Spawner unknown() { return new Spawner("", 16, -1, false); }
        public Spawner withLiveFallback(Spawner live) {
            return new Spawner(entityId.isEmpty() ? live.entityId() : entityId,
                activationKnown ? activationRange : live.activationRange(), live.delay(), activationKnown || live.activationKnown());
        }
        public boolean active(double distanceSquared) {
            return Double.isFinite(distanceSquared) && distanceSquared >= 0
                && (activationRange < 0 || distanceSquared < (double) activationRange * activationRange);
        }
    }
    public record Entry(Position pos, String typeId, String blockId, Spawner spawner, boolean visible) {
        public Entry {
            Objects.requireNonNull(pos);
            Objects.requireNonNull(typeId);
            Objects.requireNonNull(blockId);
        }
        public Entry withVisible(boolean value) { return new Entry(pos, typeId, blockId, spawner, value); }
    }

    public void scope(Object currentWorld, String currentDimension) {
        if (world != currentWorld || !Objects.equals(dimension, currentDimension)) {
            clear();
            world = currentWorld;
            dimension = currentDimension;
        }
    }

    public void clear() {
        chunks.clear();
        size = 0;
        world = null;
        dimension = null;
        revision++;
    }

    public int size() { return size; }
    public int chunkCount() { return chunks.size(); }
    public long revision() { return revision; }
    public Entry get(Position pos) {
        var chunk = chunks.get(pos.chunk());
        return chunk == null ? null : chunk.get(pos);
    }
    public List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>(size);
        for (var chunk : chunks.values()) result.addAll(chunk.values());
        return List.copyOf(result);
    }

    public void replaceChunk(Chunk chunk, List<Entry> entries) {
        removeChunk(chunk);
        int accepted = 0;
        for (Entry entry : entries) {
            if (entry.pos().chunk().equals(chunk) && entry.pos().valid()) {
                put(entry);
                if (++accepted >= MAX_PER_CHUNK) break;
            }
        }
    }

    public void put(Entry entry) {
        if (!entry.pos().valid() || entry.typeId().length() > 256 || entry.blockId().length() > 256) return;
        Chunk key = entry.pos().chunk();
        var chunk = chunks.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        Entry previous = chunk.put(entry.pos(), entry);
        if (entry.equals(previous)) return;
        if (previous == null) size++;
        if (chunk.size() > MAX_PER_CHUNK) {
            chunk.remove(chunk.keySet().iterator().next());
            size--;
        }
        while (size > MAX_ENTRIES || chunks.size() > MAX_CHUNKS) removeChunk(chunks.keySet().iterator().next());
        revision++;
    }

    public void remove(Position pos) {
        var chunk = chunks.get(pos.chunk());
        if (chunk != null && chunk.remove(pos) != null) {
            size--;
            if (chunk.isEmpty()) chunks.remove(pos.chunk());
            revision++;
        }
    }

    public void removeChunk(Chunk key) {
        var removed = chunks.remove(key);
        if (removed != null) {
            size -= removed.size();
            revision++;
        }
    }

    public void retainChunks(Predicate<Chunk> keep) {
        var iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!keep.test(entry.getKey())) {
                size -= entry.getValue().size();
                iterator.remove();
                revision++;
            }
        }
    }

    public void observe(Position pos, String blockId) {
        Entry entry = get(pos);
        if (entry == null) return;
        if (entry.blockId().equals(blockId)) {
            if (!entry.visible()) put(entry.withVisible(true));
        } else if (entry.visible() || !maskingBlock(blockId)) {
            remove(pos);
        }
    }

    public static boolean maskingBlock(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
            || id.equals("minecraft:stone") || id.equals("minecraft:deepslate");
    }

    public static String blockFromType(String id) {
        return switch (id) {
            case "minecraft:mob_spawner" -> "minecraft:spawner";
            case "minecraft:piston" -> "minecraft:moving_piston";
            case "minecraft:sign" -> "minecraft:oak_sign";
            case "minecraft:hanging_sign" -> "minecraft:oak_hanging_sign";
            case "minecraft:skull" -> "minecraft:skeleton_skull";
            case "minecraft:banner" -> "minecraft:white_banner";
            case "minecraft:bed" -> "minecraft:red_bed";
            case "minecraft:brushable_block" -> "minecraft:suspicious_sand";
            default -> id;
        };
    }

    /** Spectre BlockColorListSetting's block-entity palette, with a white unknown fallback. */
    public static int color(String id) {
        if (!id.startsWith("minecraft:")) return 0xffffffff;
        String path = id.substring("minecraft:".length());
        int rgb;
        if (path.endsWith("copper_chest")) rgb = 10246912;
        else if (path.endsWith("shulker_box")) rgb = 8781982;
        else rgb = switch (path) {
            case "chest" -> 10246912;
            case "trapped_chest" -> 13775410;
            case "ender_chest" -> 7667967;
            case "barrel" -> 6572850;
            case "hopper" -> 5263440;
            case "dispenser", "dropper" -> 7887420;
            case "brewing_stand" -> 7874760;
            case "furnace", "blast_furnace", "smoker" -> 6579300;
            case "spawner" -> 9076390;
            case "enchanting_table" -> 13107200;
            case "crafter" -> 3978400;
            case "moving_piston", "piston", "sticky_piston", "piston_head" -> 65280;
            case "lectern" -> 13806160;
            case "jukebox" -> 2017380;
            case "decorated_pot" -> 11822120;
            case "beehive", "bee_nest" -> 15779840;
            case "beacon" -> 61660;
            case "conduit" -> 46320;
            case "comparator" -> 13120050;
            default -> 0xffffff;
        };
        return 0xff000000 | rgb;
    }
}
