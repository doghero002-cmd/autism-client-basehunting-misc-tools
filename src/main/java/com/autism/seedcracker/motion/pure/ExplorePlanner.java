package com.autism.seedcracker.motion.pure;

import java.util.HashSet;
import java.util.Set;

/**
 * Picks where to walk next to load unseen ground (pure, unit tested). Rings outward from the
 * origin in chunk steps of {@code spacing}; a target counts as explored once any chunk within
 * {@code viewChunks} of it has been seen (render distance means walking there loads it all).
 */
public final class ExplorePlanner {

    private final int originCx, originCz, spacing, viewChunks;
    private final Set<Long> seen = new HashSet<>();
    private final Set<Long> abandoned = new HashSet<>();
    private int ringDone;

    public ExplorePlanner(int originCx, int originCz, int spacing, int viewChunks) {
        this.originCx = originCx;
        this.originCz = originCz;
        this.spacing = Math.max(1, spacing);
        this.viewChunks = Math.max(0, viewChunks);
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    public void markSeen(int cx, int cz) {
        seen.add(key(cx, cz));
    }

    /** Couldn't get there (no path): skip it for the rest of this run. */
    public void abandon(int cx, int cz) {
        abandoned.add(key(cx, cz));
    }

    public int seenCount() {
        return seen.size();
    }

    private boolean explored(int cx, int cz) {
        // The centre being loaded means the player's render distance already covered this area.
        if (seen.contains(key(cx, cz))) return true;
        return viewChunks == 0 ? false : seen.contains(key(cx + viewChunks, cz)) && seen.contains(key(cx - viewChunks, cz))
            && seen.contains(key(cx, cz + viewChunks)) && seen.contains(key(cx, cz - viewChunks));
    }

    /** Next chunk {cx, cz} to visit, nearest to (fromCx, fromCz) on the innermost unfinished ring; null when {@code maxRings} is exhausted. */
    public int[] next(int fromCx, int fromCz, int maxRings) {
        for (int ring = ringDone; ring <= maxRings; ring++) {
            int[] best = null;
            long bestD = Long.MAX_VALUE;
            int r = ring * spacing;
            for (int dx = -r; dx <= r; dx += spacing) {
                for (int dz = -r; dz <= r; dz += spacing) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = originCx + dx, cz = originCz + dz;
                    if (explored(cx, cz) || abandoned.contains(key(cx, cz))) continue;
                    long d = (long) (cx - fromCx) * (cx - fromCx) + (long) (cz - fromCz) * (cz - fromCz);
                    if (d < bestD) {
                        bestD = d;
                        best = new int[]{cx, cz};
                    }
                }
            }
            if (best != null) return best;
            ringDone = ring + 1;
        }
        return null;
    }
}
