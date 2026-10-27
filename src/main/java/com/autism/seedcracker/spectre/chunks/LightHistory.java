// SPDX-License-Identifier: GPL-3.0-only
// Spectre PrimeChunkFinderModule light baselines, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.spectre.chunks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure bounded packet history. Missing sections in an update are not zero-light updates. */
public final class LightHistory {
    private static final byte[] ZERO = new byte[2048];
    public record Delta(long chunk, int sectionY, byte[] previous, byte[] incoming) {}
    private final int capacity;
    private final LinkedHashMap<Long, Map<Integer, byte[]>> chunks = new LinkedHashMap<>(256, .75f, true);

    public LightHistory(int capacity) {
        if (capacity < 1 || capacity > 5000) throw new IllegalArgumentException("Invalid light cache capacity");
        this.capacity = capacity;
    }
    public boolean contains(long key) { return chunks.containsKey(key); }
    public int size() { return chunks.size(); }
    public void clear() { chunks.clear(); }
    public void forget(long key) { chunks.remove(key); }

    public void snapshot(long key, Map<Integer, byte[]> snapshot) {
        if (snapshot.size() > 64) throw new IllegalArgumentException("Too many tracked light sections");
        var copy = new HashMap<Integer, byte[]>();
        snapshot.forEach((section, bytes) -> {
            if (bytes == null || bytes.length != 2048) throw new IllegalArgumentException("Invalid light section");
            copy.put(section, bytes.clone());
        });
        chunks.put(key, copy); trim();
    }

    public List<Delta> apply(long key, int minSection, int topExclusive, int lightBottom,
                             BitSet present, BitSet empty, List<byte[]> updates, boolean baseline) {
        if (topExclusive < minSection || topExclusive - minSection > 64) throw new IllegalArgumentException("Invalid light band");
        if (present.cardinality() != updates.size() || updates.size() > 1024 || present.length() > 1024 || empty.length() > 1024
            || updates.stream().anyMatch(bytes -> bytes == null || bytes.length != 2048)) {
            chunks.remove(key); return List.of();
        }
        Map<Integer, byte[]> sections = baseline ? new HashMap<>() : chunks.get(key);
        if (sections == null) return List.of(); // Unknown history must be baselined, not guessed.
        if (baseline) for (int y = minSection; y < topExclusive; y++) sections.put(y, ZERO);
        var deltas = new ArrayList<Delta>();
        int next = 0;
        for (int bit = present.nextSetBit(0); bit >= 0; bit = present.nextSetBit(bit + 1)) {
            byte[] bytes = updates.get(next++);
            int y = lightBottom + bit;
            if (y >= minSection && y < topExclusive) replace(key, y, bytes.clone(), sections, baseline, deltas);
        }
        for (int bit = empty.nextSetBit(0); bit >= 0; bit = empty.nextSetBit(bit + 1)) {
            int y = lightBottom + bit;
            if (!present.get(bit) && y >= minSection && y < topExclusive) replace(key, y, ZERO, sections, baseline, deltas);
        }
        chunks.put(key, sections); trim();
        return List.copyOf(deltas);
    }

    private static void replace(long key, int y, byte[] bytes, Map<Integer, byte[]> sections, boolean baseline, List<Delta> deltas) {
        byte[] previous = sections.put(y, bytes);
        if (!baseline && previous != null && !Arrays.equals(previous, bytes)) deltas.add(new Delta(key, y, previous, bytes));
    }
    private void trim() { while (chunks.size() > capacity) chunks.pollFirstEntry(); }
}
