package com.autism.seedcracker.motion.pure;

/**
 * Per-search cache of cell kinds (and the hazard-neighbour flag) in flat byte arrays, one per
 * cube of cells touched. A lookup is a key compare plus an array index instead of a hash probe.
 * Low 4 bits = kind ordinal + 1 (0 = not looked up); bits 4-5 = hazard flag (0 unknown, 1 no, 2 yes).
 */
final class CellCache {
    private static final GridPathfinder.Kind[] KINDS = GridPathfinder.Kind.values();

    private final GridPathfinder.World world;
    private final int shift, mask;
    private final LongTable<byte[]> cubes;
    private long lastKey = Long.MIN_VALUE;
    private byte[] last;

    /** {@code small}: 4x4x4 cubes for one-hop re-pricing (called every tick), else 16x16x16. */
    CellCache(GridPathfinder.World world, boolean small) {
        this.world = world;
        this.shift = small ? 2 : 4;
        this.mask = (1 << shift) - 1;
        this.cubes = new LongTable<>(small ? 16 : 256);
    }

    private byte[] cube(int x, int y, int z) {
        long key = GridPathfinder.pack(x >> shift, y >> shift, z >> shift);
        if (key == lastKey) return last;
        byte[] c = cubes.get(key);
        if (c == null) {
            c = new byte[1 << (3 * shift)];
            cubes.put(key, c);
        }
        lastKey = key;
        last = c;
        return c;
    }

    private int index(int x, int y, int z) {
        return (y & mask) << (2 * shift) | (z & mask) << shift | (x & mask);
    }

    GridPathfinder.Kind kind(int x, int y, int z) {
        byte[] c = cube(x, y, z);
        int i = index(x, y, z);
        int k = c[i] & 0x0F;
        if (k != 0) return KINDS[k - 1];
        GridPathfinder.Kind kind = world.kind(x, y, z);
        c[i] = (byte) ((c[i] & 0xF0) | (kind.ordinal() + 1));
        return kind;
    }

    /** 0 = not computed, 1 = no hazard near, 2 = hazard adjacent (ring 1), 3 = hazard two cells out (ring 2). */
    int hazard(int x, int y, int z) {
        return (cube(x, y, z)[index(x, y, z)] >> 4) & 3;
    }

    void setHazard(int x, int y, int z, boolean hazard) {
        setHazard(x, y, z, hazard ? 2 : 1);
    }

    void setHazard(int x, int y, int z, int level) {
        byte[] c = cube(x, y, z);
        int i = index(x, y, z);
        c[i] = (byte) ((c[i] & 0x0F) | (level & 3) << 4);
    }

    int cubeCount() {
        return cubes.size();
    }
}
