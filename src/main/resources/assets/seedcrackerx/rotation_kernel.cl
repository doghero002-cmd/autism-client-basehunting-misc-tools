// Texture-rotation pattern search kernel (in-mod). One work-item = one anchor (x, z); tests
// every grid orientation with early exit. Seedless: a block's texture variant is a pure
// function of position (posSeed -> nextInt), so no world seed / factory chain is involved.
// Mirrors the Java fastVerify in RotationGpuEngine exactly; the host self-tests a tile before
// any real search and re-verifies every reported match on CPU.

// Vanilla Mth.getSeed: the x multiply overflows in 32-bit on purpose.
static inline long pos_seed(int x, int y, int z) {
    long l = (long)(x * 3129871) ^ ((long)z * 116129781L) ^ (long)y;
    l = l * l * 42317861L + l * 11L;
    return l >> 16;
}

static const ulong MULT = 0x5DEECE66DUL;
static const ulong LCG_ADD = 0xBUL;
static const ulong LCG_MASK = (1UL << 48) - 1;

static inline int idx_next_int(long seed, int count) {
    long s = (seed ^ (long)MULT) & (long)LCG_MASK;
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;
    int u = (int)((ulong)s >> 17);
    if ((count & (count - 1)) == 0) {
        return (int)(((long)count * (long)u) >> 31);
    }
    int m = count - 1;
    int r = u % count;
    while (u - r + m < 0) {
        s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;
        u = (int)((ulong)s >> 17);
        r = u % count;
    }
    return r;
}

static inline int idx_legacy(long seed, int count) {
    long s = (seed ^ (long)MULT) & (long)LCG_MASK;
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;
    int lo = (int)((ulong)s >> 16);
    int a = lo < 0 ? -lo : lo;
    if (a < 0) a = 0;
    return a % count;
}

static inline int variant_at(int x, int y, int z, int legacy) {
    long seed = pos_seed(x, y, z);
    return legacy != 0 ? idx_legacy(seed, 4) : idx_next_int(seed, 4);
}

// cells: one int4 per known cell = (dx, dz, rotation 0..3, unused). Grid orientation rot maps
// grid offsets to world offsets and advances the expected texture rotation by the same turn.
__kernel void search_rotation(
    const int posY,
    const int originX, const int originZ,
    const int spanX, const int spanZ,
    __constant int4* cells, const int cellCount,
    const int cols, const int rows,
    const int legacy,
    __global int* matchCount,
    __global int* matches,                          // (x, z, rot) triplets
    const int matchCap
) {
    int gx = get_global_id(0);
    int gz = get_global_id(1);
    if (gx >= spanX || gz >= spanZ) return;
    int ax = originX + gx;
    int az = originZ + gz;

    for (int rot = 0; rot < 4; rot++) {
        bool ok = true;
        for (int i = 0; i < cellCount && ok; i++) {
            int4 cell = cells[i];
            int dx, dz;
            switch (rot) {
                case 1:  dx = rows - 1 - cell.y; dz = cell.x; break;
                case 2:  dx = cols - 1 - cell.x; dz = rows - 1 - cell.y; break;
                case 3:  dx = cell.y; dz = cols - 1 - cell.x; break;
                default: dx = cell.x; dz = cell.y; break;
            }
            int observed = variant_at(ax + dx, posY, az + dz, legacy);
            int want = (cell.z + rot) & 3;
            if (observed != want) ok = false;
        }
        if (ok) {
            int slot = atomic_inc(matchCount);
            if (slot < matchCap) {
                matches[slot * 3] = ax;
                matches[slot * 3 + 1] = az;
                matches[slot * 3 + 2] = rot;
            }
        }
    }
}
