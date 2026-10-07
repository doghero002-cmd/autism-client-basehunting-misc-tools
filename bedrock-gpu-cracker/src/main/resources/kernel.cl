// Bedrock pattern search kernel. One work-item = one anchor (x, z); tests every rotation of the
// multi-layer pattern with early exit. Mirrors gpucrack.CpuReference exactly - the host
// self-tests a tile against the Java reference before any real search and re-verifies every
// reported match on CPU.

// Vanilla Mth.getSeed: the x multiply overflows in 32-bit on purpose.
static inline long pos_seed(int x, int y, int z) {
    long l = (long)(x * 3129871) ^ ((long)z * 116129781L) ^ (long)y;
    l = l * l * 42317861L + l * 11L;
    return l >> 16;
}

static inline long rotl64(long v, int s) {
    return (long)(((ulong)v << s) | ((ulong)v >> (64 - s)));
}

// One-draw xoroshiro128++ nextFloat() < threshold.
static inline bool is_bedrock(long facLo, long facHi, int x, int y, int z, float threshold) {
    long lo = pos_seed(x, y, z) ^ facLo;
    long hi = facHi;
    if ((lo | hi) == 0L) {
        lo = -7046029254386353131L;
        hi = 7640891576956012809L;
    }
    long n = rotl64(lo + hi, 17) + lo;
    return (float)((ulong)n >> 40) * 5.9604645e-8f < threshold;
}

// cells: one int4 per marked cell = (dx, dz, layerIndex, wantBedrock 0/1), all layers flattened.
// layerY / layerThreshold indexed by layerIndex. Rotation r maps grid offsets to world offsets
// exactly like CpuReference.rotate.
__kernel void search(
    const long facLo, const long facHi,
    const int originX, const int originZ,          // anchor of work-item (0,0)
    const int spanX, const int spanZ,              // tile dimensions in anchors
    __constant int4* cells, const int cellCount,
    __constant int* layerY,
    __constant float* layerThreshold,
    const int cols, const int rows,
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
            int li = cell.z;
            bool bedrock = is_bedrock(facLo, facHi, ax + dx, layerY[li], az + dz, layerThreshold[li]);
            if (bedrock != (cell.w != 0)) ok = false;
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
