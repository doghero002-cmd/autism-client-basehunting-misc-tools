package gpucrack;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone ground-truth check (run directly, no JUnit on the classpath): plant a rotation
 * grid at a known position, confirm {@link RotationCpuReference#matchesAt} finds it there and
 * ONLY there in a small sweep, across all 4 grid orientations and both formulas.
 */
public final class RotationSelfTest {

    private RotationSelfTest() {}

    public static void main(String[] args) {
        boolean legacy = args.length > 0 && args[0].equals("--legacy");
        int y = 64;
        int failures = 0;

        for (int gridRot = 0; gridRot < 4; gridRot++) {
            // Plant: pick a world anchor, compute the TRUE variants around it, then build the
            // observed grid the way a screenshotter standing at orientation gridRot would read it.
            int anchorX = 1000, anchorZ = 2000;
            int cols = 5, rows = 5;
            int[][] grid = new int[cols][rows];
            List<RotationPattern.Cell> cells = new ArrayList<>();
            for (int gc = 0; gc < cols; gc++) {
                for (int gr = 0; gr < rows; gr++) {
                    // Map grid cell -> world offset under this orientation (same as kernel rotate).
                    int[] d = RotationCpuReference.rotate(gc, gr, gridRot, cols, rows);
                    int worldVariant = RotationCpuReference.variantAt(anchorX + d[0], y, anchorZ + d[1], legacy);
                    // The stored rotation is worldVariant MINUS the orientation (kernel adds it back).
                    int stored = (worldVariant - gridRot) & 3;
                    grid[gc][gr] = stored;
                    cells.add(new RotationPattern.Cell(gc, gr, stored));
                }
            }
            RotationPattern pattern = new RotationPattern(cols, rows, List.copyOf(cells), grid);

            // The planted anchor must match under gridRot.
            if (!RotationCpuReference.matchesAt(pattern, y, legacy, anchorX, anchorZ, gridRot)) {
                System.out.printf("FAIL: planted anchor did not match (gridRot=%d legacy=%b)%n", gridRot, legacy);
                failures++;
            }

            // Sweep a small area: no OTHER (x,z,rot) should match a full 5x5 grid.
            int hits = 0;
            for (int x = anchorX - 8; x <= anchorX + 8; x++) {
                for (int z = anchorZ - 8; z <= anchorZ + 8; z++) {
                    for (int rot = 0; rot < 4; rot++) {
                        if (x == anchorX && z == anchorZ && rot == gridRot) continue;
                        if (RotationCpuReference.matchesAt(pattern, y, legacy, x, z, rot)) hits++;
                    }
                }
            }
            if (hits > 0) {
                System.out.printf("FAIL: %d unexpected match(es) near planted anchor (gridRot=%d legacy=%b)%n",
                    hits, gridRot, legacy);
                failures++;
            }
            System.out.printf("gridRot=%d legacy=%b -> planted OK, %d stray match(es)%n", gridRot, legacy, hits);
        }

        System.out.println(failures == 0 ? "ROTATION SELF-TEST PASS" : "ROTATION SELF-TEST FAIL (" + failures + ")");
        if (failures > 0) System.exit(1);
    }
}
