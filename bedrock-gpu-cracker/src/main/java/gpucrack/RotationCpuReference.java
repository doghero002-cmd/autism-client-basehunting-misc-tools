package gpucrack;

/**
 * CPU ground truth for the texture-rotation kernel: the seedless variant-of-position math the
 * GPU is checked against. The startup self-test compares a full kernel tile to this, and every
 * GPU-reported match is re-verified here before it is shown.
 */
public final class RotationCpuReference {

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private RotationCpuReference() {}

    /** Vanilla Mth.getSeed - the x multiply overflows in INT exactly like vanilla. */
    public static long posSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /** Modern nextInt(count) with the rejection loop for non-power-of-two bounds. */
    public static int idxNextInt(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;
        int u = (int) (s >>> 17);
        if ((count & (count - 1)) == 0) {
            return (int) ((count * (long) u) >> 31);
        }
        int m = count - 1;
        int r = u % count;
        while (u - r + m < 0) {
            s = (s * MULT + ADD) & MASK;
            u = (int) (s >>> 17);
            r = u % count;
        }
        return r;
    }

    /** Legacy Math.abs((int) nextLong()) % count (with the MIN_VALUE quirk). */
    public static int idxLegacy(long seed, int count) {
        long s = (seed ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;
        s = (s * MULT + ADD) & MASK;
        int lo = (int) (s >>> 16);
        int abs = Math.abs(lo);
        return abs < 0 ? 0 : abs % count;
    }

    /** The texture variant a block at (x,y,z) shows (0..3 for a plain 4-rotation block). */
    public static int variantAt(int x, int y, int z, boolean legacy) {
        long seed = posSeed(x, y, z);
        return legacy ? idxLegacy(seed, 4) : idxNextInt(seed, 4);
    }

    /** The 16-way model index a block at (x,y,z) picks (the unified multi-face selector). */
    public static int modelIndexAt(int x, int y, int z, boolean legacy) {
        long seed = posSeed(x, y, z);
        return legacy ? idxLegacy(seed, 16) : idxNextInt(seed, 16);
    }

    /** Grid (dx, dz) -> world offsets under rotation rot; must mirror the kernel's mapping. */
    public static int[] rotate(int dx, int dz, int rot, int cols, int rows) {
        return switch (rot) {
            case 1 -> new int[]{rows - 1 - dz, dx};
            case 2 -> new int[]{cols - 1 - dx, rows - 1 - dz};
            case 3 -> new int[]{dz, cols - 1 - dx};
            default -> new int[]{dx, dz};
        };
    }

    /** True when every known cell of the grid matches at anchor (ax, az) under rotation rot. */
    public static boolean matchesAt(RotationPattern pattern, int y, boolean legacy, int ax, int az, int rot) {
        for (RotationPattern.Cell cell : pattern.cells()) {
            int[] d = rotate(cell.dx(), cell.dz(), rot, pattern.cols(), pattern.rows());
            int code = cell.rotation(); // cell value is the FaceModel code
            if (code < 0) continue;      // unknown cell accepts everything
            int index = modelIndexAt(ax + d[0], y, az + d[1], legacy);
            int mask = FaceModel.acceptMask(code, rot, legacy);
            if ((mask & (1 << index)) == 0) return false;
        }
        return true;
    }
}
