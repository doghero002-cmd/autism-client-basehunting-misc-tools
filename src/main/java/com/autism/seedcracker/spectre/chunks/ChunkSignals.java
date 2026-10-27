// SPDX-License-Identifier: GPL-3.0-only
// Spectre SusChunkFinderModule peak selection, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.spectre.chunks;

public final class ChunkSignals {
    private ChunkSignals() {}

    /** Square-neighbourhood maxima, preserving all ties rather than inventing a single base. */
    public static int[] neighborhoodMax(int[] heat, int side, int reach) {
        if (side < 1 || side > 65 || heat.length != side * side || reach < 0 || reach > 16)
            throw new IllegalArgumentException("Invalid bounded chunk field");
        int[] horizontal = new int[heat.length], result = new int[heat.length];
        // ponytail: two bounded radius passes (65x65, reach <=16); deque windows if limits grow.
        for (int x = 0; x < side; x++) for (int z = 0; z < side; z++)
            for (int nx = Math.max(0, x - reach); nx <= Math.min(side - 1, x + reach); nx++)
                horizontal[x * side + z] = Math.max(horizontal[x * side + z], heat[nx * side + z]);
        for (int x = 0; x < side; x++) for (int z = 0; z < side; z++)
            for (int nz = Math.max(0, z - reach); nz <= Math.min(side - 1, z + reach); nz++)
                result[x * side + z] = Math.max(result[x * side + z], horizontal[x * side + nz]);
        return result;
    }
}
