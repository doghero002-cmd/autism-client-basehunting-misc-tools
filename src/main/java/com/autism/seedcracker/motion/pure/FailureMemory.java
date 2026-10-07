package com.autism.seedcracker.motion.pure;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cells where a trip recently got stuck or blocked. Replans charge extra to enter them so the bot
 * tries another way instead of walking into the same invisible snag; strikes stack and expire.
 */
public final class FailureMemory {
    /** Planner cost per strike (a hazard-adjacent cell is 6): a detour of this many blocks beats retrying. */
    public static final double STRIKE_COST = 8.0;
    public static final int MAX_STRIKES = 3;

    public interface Sink {
        void accept(int x, int y, int z, double cost);
    }

    private final long ttlTicks;
    private final int capacity;
    private final LinkedHashMap<Long, long[]> cells = new LinkedHashMap<>();

    public FailureMemory(long ttlTicks, int capacity) {
        this.ttlTicks = ttlTicks;
        this.capacity = Math.max(1, capacity);
    }

    public void record(int x, int y, int z, long now) {
        long key = GridPathfinder.pack(x, y, z);
        long[] e = cells.remove(key);
        if (e == null || now - e[1] > ttlTicks) e = new long[]{0, now};
        e[0] = Math.min(MAX_STRIKES, e[0] + 1);
        e[1] = now;
        cells.put(key, e);
        Iterator<Long> it = cells.keySet().iterator();
        while (cells.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public double penalty(int x, int y, int z, long now) {
        long[] e = cells.get(GridPathfinder.pack(x, y, z));
        return e == null || now - e[1] > ttlTicks ? 0 : e[0] * STRIKE_COST;
    }

    public void forEachActive(long now, Sink sink) {
        for (Map.Entry<Long, long[]> en : cells.entrySet()) {
            long[] e = en.getValue();
            if (now - e[1] > ttlTicks) continue;
            long k = en.getKey();
            sink.accept(GridPathfinder.unpackX(k), GridPathfinder.unpackY(k), GridPathfinder.unpackZ(k), e[0] * STRIKE_COST);
        }
    }

    public int size() {
        return cells.size();
    }

    public void clear() {
        cells.clear();
    }
}
