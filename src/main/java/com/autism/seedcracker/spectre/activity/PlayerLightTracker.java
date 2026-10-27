// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre PlayerBypassLightTracker/ChunkEntryGuard, d5a7dd3 (Cody / cody-raves).
package com.autism.seedcracker.spectre.activity;

import com.autism.seedcracker.util.pure.PlayerFinderSignals;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/** Raw skylight evidence; a zero-filled PRESENT layer is not an empty-mask layer. */
public final class PlayerLightTracker {
    public static final int MAX_CHUNKS = 4096;
    private record Snapshot(int sections, int bottom, byte[] sky) {
        boolean signal() { return PlayerFinderSignals.darkSignal(sky, bottom); }
    }
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Snapshot> held = new LinkedHashMap<>();
    private final Set<Long> alerted = new LinkedHashSet<>();
    private final LinkedHashSet<Long> protectedChunks = new LinkedHashSet<>();
    private final Set<Long> waiting = new LinkedHashSet<>();
    private boolean entered;
    private boolean entryPending;
    private long playerChunk;

    public boolean accept(int x, int z, int sections, int bottom, BitSet present, BitSet empty,
                          List<byte[]> updates, boolean belowZero) {
        if (sections <= 0 || sections > 256 || present == null || empty == null || updates == null) return false;
        long key = QuantumRules.key(x, z);
        if (belowZero || held.containsKey(key)) {
            Snapshot snapshot = apply(held.get(key), sections, bottom, present, empty, updates);
            if (snapshot.signal() && !protectedChunks.contains(key)) held.put(key, snapshot);
            else held.remove(key);
            while (held.size() > MAX_CHUNKS) held.pollFirstEntry();
            if (belowZero) return false;
        }
        boolean wasFlagged = isFlagged(key);
        Snapshot snapshot = apply(snapshots.get(key), sections, bottom, present, empty, updates);
        if (snapshot.signal() && (wasFlagged || !protectedChunks.contains(key))) {
            snapshots.put(key, snapshot);
            boolean first = alerted.add(key);
            trimSnapshots();
            return first;
        }
        if (wasFlagged) snapshots.put(key, snapshot);
        else snapshots.remove(key);
        return false;
    }

    private static Snapshot apply(Snapshot previous, int sections, int bottom, BitSet present,
                                  BitSet empty, List<byte[]> updates) {
        Snapshot snapshot = previous != null && previous.sections == sections && previous.bottom == bottom
            ? previous : new Snapshot(sections, bottom, new byte[sections + 2]);
        PlayerFinderSignals.apply(snapshot.sky, present, empty, updates);
        return snapshot;
    }

    private void trimSnapshots() {
        while (snapshots.size() > MAX_CHUNKS) alerted.remove(snapshots.pollFirstEntry().getKey());
    }

    public boolean isFlagged(long key) {
        Snapshot snapshot = snapshots.get(key);
        return snapshot != null && (alerted.contains(key) || snapshot.signal());
    }

    public Set<Long> flagged() { return Set.copyOf(alerted); }

    public void updateEntry(int x, int z, LongPredicate loaded) {
        long current = QuantumRules.key(x, z);
        if (!entered || current != playerChunk) {
            entered = true;
            playerChunk = current;
            entryPending = true;
            waiting.clear();
        }
        if (entryPending) {
            if (!loaded.test(current)) return;
            entryPending = false;
            if (isFlagged(current) || held.containsKey(current)) return;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                long key = QuantumRules.key(x + dx, z + dz);
                if (!loaded.test(key)) waiting.add(key);
                else if (!isFlagged(key) && !held.containsKey(key)) protect(key);
            }
        } else {
            waiting.removeIf(key -> {
                if (!loaded.test(key)) return false;
                if (!isFlagged(key) && !held.containsKey(key)) protect(key);
                return true;
            });
        }
    }

    private void protect(long key) {
        // ponytail: protection is session-local and capped; persistent history needs an explicit storage policy.
        protectedChunks.add(key);
        while (protectedChunks.size() > MAX_CHUNKS) protectedChunks.removeFirst();
    }

    public void retain(LongPredicate keep) {
        snapshots.keySet().removeIf(key -> !keep.test(key));
        alerted.retainAll(snapshots.keySet());
        held.keySet().removeIf(key -> !keep.test(key));
        protectedChunks.removeIf(key -> !keep.test(key));
    }

    public void forget(long key) {
        snapshots.remove(key);
        alerted.remove(key);
        held.remove(key);
        protectedChunks.remove(key);
        waiting.remove(key);
        if (entered && playerChunk == key) { entered = false; entryPending = false; waiting.clear(); }
    }

    public void clear() {
        snapshots.clear();
        alerted.clear();
        held.clear();
        protectedChunks.clear();
        waiting.clear();
        entered = false;
        entryPending = false;
    }
}
