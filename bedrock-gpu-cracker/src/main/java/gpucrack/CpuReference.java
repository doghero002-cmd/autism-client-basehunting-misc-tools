package gpucrack;

/**
 * CPU reference evaluation: the ground truth the GPU is checked against. The startup self-test
 * compares a full kernel tile to this, and every GPU-reported match is re-verified here before
 * it is shown - GPU int/float quirks can never produce a silently wrong answer.
 */
public final class CpuReference {

    private CpuReference() {}

    /** One-draw bedrock check: at(x,y,z).nextFloat() < threshold, fully inlined. */
    public static boolean isBedrock(long facLo, long facHi, int x, int y, int z, float threshold) {
        long lo = SeedChain.posSeed(x, y, z) ^ facLo;
        long hi = facHi;
        if ((lo | hi) == 0L) { lo = -7046029254386353131L; hi = 7640891576956012809L; }
        long n = Long.rotateLeft(lo + hi, 17) + lo;
        return (float) (n >>> 40) * 5.9604645E-8F < threshold;
    }

    /** True when every marked cell of every layer matches at anchor (ax, az) under rotation rot. */
    public static boolean matchesAt(PatternFile pattern, long facLo, long facHi, int ax, int az, int rot) {
        for (PatternFile.Layer layer : pattern.layers()) {
            float th = SeedChain.layerThreshold(layer.y(), pattern.roof());
            for (PatternFile.Cell cell : layer.cells()) {
                int[] d = rotate(cell.dx(), cell.dz(), rot, pattern.cols(), pattern.rows());
                boolean bedrock = isBedrock(facLo, facHi, ax + d[0], layer.y(), az + d[1], th);
                if (bedrock != cell.bedrock()) return false;
            }
        }
        return true;
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

    /** Rotated footprint (width, height) of the pattern under rotation rot. */
    public static int[] footprint(int cols, int rows, int rot) {
        return (rot & 1) == 0 ? new int[]{cols, rows} : new int[]{rows, cols};
    }
}
