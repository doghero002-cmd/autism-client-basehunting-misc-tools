// Texture-rotation pattern search kernel. One work-item = one anchor (x, z); tests every
// rotation of the observed rotation-grid with early exit. Seedless: a block's texture variant
// is a pure function of position (posSeed -> nextInt), so no world seed is involved.
// Mirrors gpucrack.RotationCpuReference exactly - the host self-tests a tile against the Java
// reference before any real search and re-verifies every reported match on CPU.

// Vanilla Mth.getSeed: the x multiply overflows in 32-bit on purpose.
static inline long pos_seed(int x, int y, int z) {
    long l = (long)(x * 3129871) ^ ((long)z * 116129781L) ^ (long)y;
    l = l * l * 42317861L + l * 11L;
    return l >> 16;
}

static const ulong MULT = 0x5DEECE66DUL;
static const ulong LCG_ADD = 0xBUL;
static const ulong LCG_MASK = (1UL << 48) - 1;

// Modern nextInt(count): rejection loop for non-power-of-two bounds (uniform here for 4).
static inline int idx_next_int(long seed, int count) {
    long s = (seed ^ (long)MULT) & (long)LCG_MASK;
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;   // next(31)
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

// Legacy abs((int)nextLong) % count (with the MIN_VALUE quirk).
static inline int idx_legacy(long seed, int count) {
    long s = (seed ^ (long)MULT) & (long)LCG_MASK;
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;   // hi word (discarded by cast)
    s = (s * (long)MULT + (long)LCG_ADD) & (long)LCG_MASK;   // low word of nextLong
    int lo = (int)((ulong)s >> 16);
    int a = lo < 0 ? -lo : lo;
    if (a < 0) a = 0;                                         // Math.abs(MIN_VALUE) quirk
    return a % count;
}

// The texture variant a block at (x,y,z) shows (0..3 for a plain 4-rotation block).
static inline int variant_at(int x, int y, int z, bool legacy) {
    long seed = pos_seed(x, y, z);
    return legacy ? idx_legacy(seed, 4) : idx_next_int(seed, 4);
}

// The 16-way model index a block at (x,y,z) picks (unified multi-face selector, CoordsFinder).
static inline int model_index_at(int x, int y, int z, bool legacy) {
    long seed = pos_seed(x, y, z);
    return legacy ? idx_legacy(seed, 16) : idx_next_int(seed, 16);
}

// cells: one int4 per known cell = (dx, dz, mask16, unused). mask16 is the 16-bit acceptance
// mask of model indices the cell's observation allows (computed on host per rotation).
// Rotation rot maps grid offsets to world offsets exactly like RotationCpuReference.rotate.
// Y-range: each work-item scans a band of Y levels at its (x,z) anchor. Matches store
// (x, z, rot, y) quads. Because masks are per-rotation, the host passes one rotated cell set
// per grid orientation and we dispatch 4 rotations; here we test all 4 inline for simplicity.
__kernel void search_rotation(
    const int yStart, const int ySpan,
    const int originX, const int originZ,          // anchor of work-item (0,0)
    const int spanX, const int spanZ,              // tile dimensions in anchors
    __constant int4* cells0, const int cellCount0, // rot 0 cells (dx, dz, mask16)
    __constant int4* cells1, const int cellCount1, // rot 1
    __constant int4* cells2, const int cellCount2, // rot 2
    __constant int4* cells3, const int cellCount3, // rot 3
    const int legacy,                              // 0 = nextInt, 1 = legacy formula
    __global int* matchCount,
    __global int* matches,                          // (x, z, rot, y) quads
    const int matchCap
) {
    int gx = get_global_id(0);
    int gz = get_global_id(1);
    if (gx >= spanX || gz >= spanZ) return;
    int ax = originX + gx;
    int az = originZ + gz;

    for (int yy = 0; yy < ySpan; yy++) {
        int posY = yStart + yy;
        for (int rot = 0; rot < 4; rot++) {
            __constant int4* cells = rot == 0 ? cells0 : rot == 1 ? cells1 : rot == 2 ? cells2 : cells3;
            int cellCount = rot == 0 ? cellCount0 : rot == 1 ? cellCount1 : rot == 2 ? cellCount2 : cellCount3;
            bool ok = true;
            for (int i = 0; i < cellCount && ok; i++) {
                int4 cell = cells[i];
                int mask = cell.z & 0xFFFF;
                if (mask == 0xFFFF) continue; // unknown cell accepts everything
                int index = model_index_at(ax + cell.x, posY, az + cell.y, legacy != 0);
                if ((mask & (1 << index)) == 0) ok = false;
            }
            if (ok) {
                int slot = atomic_inc(matchCount);
                if (slot < matchCap) {
                    matches[slot * 4] = ax;
                    matches[slot * 4 + 1] = az;
                    matches[slot * 4 + 2] = rot;
                    matches[slot * 4 + 3] = posY;
                }
            }
        }
    }
}
