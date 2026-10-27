// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre util/quantum/ChunkSnapshotStore, d5a7dd3 (Cody / cody-raves).
package com.autism.seedcracker.spectre.activity;

import java.util.LinkedHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

public final class ChunkSnapshotStore {
    public static final int MIN_SECTION_INDEX = 6;
    public static final int MAX_SECTION_INDEX = 23;
    private static final int MAX_CHUNKS = 289;
    private final LinkedHashMap<Long, PalettedContainer<BlockState>[]> snapshots = new LinkedHashMap<>();
    private final BlockState air;
    private final Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    public ChunkSnapshotStore(BlockState air) { this.air = air; }

    public boolean contains(long key) { return snapshots.containsKey(key); }
    public void remove(long key) { snapshots.remove(key); }
    public void retain(LongPredicate keep) { snapshots.keySet().removeIf(key -> !keep.test(key)); }
    public void clear() { snapshots.clear(); }

    public void store(int chunkX, int chunkZ, PalettedContainer<BlockState>[] sections) {
        PalettedContainer<BlockState>[] copy = sections.clone();
        for (int index = 0; index < copy.length; index++) {
            copy[index] = index >= MIN_SECTION_INDEX && index <= MAX_SECTION_INDEX && sections[index] != null
                ? sections[index].copy() : null;
        }
        snapshots.put(QuantumRules.key(chunkX, chunkZ), copy);
        while (snapshots.size() > MAX_CHUNKS) snapshots.pollFirstEntry();
    }

    public void update(int chunkX, int chunkZ, int sectionIndex, int localX, int localY, int localZ, BlockState state) {
        if (state == null || sectionIndex < MIN_SECTION_INDEX || sectionIndex > MAX_SECTION_INDEX) return;
        PalettedContainer<BlockState>[] sections = snapshots.get(QuantumRules.key(chunkX, chunkZ));
        if (sections == null || sectionIndex >= sections.length) return;
        PalettedContainer<BlockState> section = sections[sectionIndex];
        if (section == null) {
            if (state == air) return;
            sections[sectionIndex] = section = new PalettedContainer<>(air, strategy);
        }
        section.set(localX, localY, localZ, state);
    }

    public BlockChange firstChange(int chunkX, int chunkZ, PalettedContainer<BlockState>[] current,
                                  int minSectionY, BiPredicate<BlockState, BlockState> accept) {
        PalettedContainer<BlockState>[] old = snapshots.get(QuantumRules.key(chunkX, chunkZ));
        if (old == null) return null;
        boolean baseline = false;
        for (int index = MIN_SECTION_INDEX; index <= MAX_SECTION_INDEX && index < old.length; index++) {
            if (old[index] != null) { baseline = true; break; }
        }
        if (!baseline) return null;
        for (int index = MIN_SECTION_INDEX; index <= MAX_SECTION_INDEX; index++) {
            PalettedContainer<BlockState> before = index < old.length ? old[index] : null;
            PalettedContainer<BlockState> after = index < current.length ? current[index] : null;
            if (before == null && after == null) continue;
            int baseY = (index + minSectionY) * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState oldState = before == null ? air : before.get(x, y, z);
                BlockState newState = after == null ? air : after.get(x, y, z);
                if (oldState != newState && accept.test(oldState, newState)) {
                    return new BlockChange(chunkX * 16 + x, baseY + y, chunkZ * 16 + z, newState, oldState);
                }
            }
        }
        return null;
    }

    public record BlockChange(int worldX, int worldY, int worldZ, BlockState newState, BlockState oldState) {}
}
