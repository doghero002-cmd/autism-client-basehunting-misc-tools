package com.autism.seedcracker.motion.pure;

/** Open-addressing long-keyed map without boxing the keys: the planner does millions of lookups per trip. */
final class LongTable<V> {
    private long[] keys;
    private Object[] vals;
    private boolean[] used;
    private int size;
    private int mask;

    LongTable(int capacityPow2) {
        int cap = Integer.highestOneBit(Math.max(16, capacityPow2 - 1) << 1);
        keys = new long[cap];
        vals = new Object[cap];
        used = new boolean[cap];
        mask = cap - 1;
    }

    private static int mix(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        for (int i = mix(key) & mask; used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) return (V) vals[i];
        }
        return null;
    }

    boolean contains(long key) {
        for (int i = mix(key) & mask; used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) return true;
        }
        return false;
    }

    void put(long key, V val) {
        if ((size + 1) * 4 >= keys.length * 3) grow();
        int i = mix(key) & mask;
        for (; used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) {
                vals[i] = val;
                return;
            }
        }
        used[i] = true;
        keys[i] = key;
        vals[i] = val;
        size++;
    }

    int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        long[] ok = keys;
        Object[] ov = vals;
        boolean[] ou = used;
        keys = new long[ok.length << 1];
        vals = new Object[ok.length << 1];
        used = new boolean[ok.length << 1];
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], (V) ov[i]);
    }
}
