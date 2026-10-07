package com.autism.seedcracker.motion.pure;

import java.util.List;

import com.autism.seedcracker.motion.pure.GridPathfinder.Step;

/**
 * Treats a path as a polyline through its node centres (pure, unit tested). The follower steers at a
 * point sliding along that line ahead of the player instead of at node centres, so the route is a
 * smooth curve rather than a hop from block to block.
 */
public final class PathGeometry {
    private PathGeometry() {}

    /** Closest point on the path from segment {@code fromSeg}: {@code seg} is the index of the node the segment ends at. */
    public record Projection(int seg, double t, double x, double z, double distSq) {}

    /**
     * Closest point to (px,pz) on the segments ending at nodes {@code fromSeg}..{@code toSeg}. Only
     * segments whose two nodes are within {@code maxDy} of {@code py} count, so a lower or higher
     * stretch of path never steals the projection.
     */
    public static Projection project(List<Step> path, int fromSeg, int toSeg, double px, double py, double pz, double maxDy) {
        Projection best = null;
        int from = Math.max(1, fromSeg), to = Math.min(path.size() - 1, toSeg);
        for (int i = from; i <= to; i++) {
            Step a = path.get(i - 1), b = path.get(i);
            if (Math.abs(py - b.y()) > maxDy && Math.abs(py - a.y()) > maxDy) continue;
            double ax = a.x() + 0.5, az = a.z() + 0.5, bx = b.x() + 0.5, bz = b.z() + 0.5;
            double vx = bx - ax, vz = bz - az, len2 = vx * vx + vz * vz;
            double t = len2 < 1e-9 ? 1.0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
            double cx = ax + vx * t, cz = az + vz * t;
            double d = (px - cx) * (px - cx) + (pz - cz) * (pz - cz);
            // Later segments win ties: at a shared node the player is starting the next one.
            if (best == null || d <= best.distSq + 1e-9) best = new Projection(i, t, cx, cz, d);
        }
        return best;
    }

    /**
     * The point {@code ahead} blocks further along the path from a projection, stopping early at
     * {@code stopAt} (a node where the move changes and the follower must actually arrive).
     * Returns {x, z, segIndexReached}.
     */
    public static double[] carrot(List<Step> path, Projection from, double ahead, int stopAt) {
        double remaining = ahead;
        double x = from.x(), z = from.z();
        int seg = from.seg();
        while (seg < path.size()) {
            Step b = path.get(seg);
            double bx = b.x() + 0.5, bz = b.z() + 0.5;
            double len = Math.hypot(bx - x, bz - z);
            if (len >= remaining || seg >= stopAt) {
                if (len < 1e-9 || seg >= stopAt && len < remaining) return new double[]{bx, bz, seg};
                double k = remaining / len;
                return new double[]{x + (bx - x) * k, z + (bz - z) * k, seg};
            }
            remaining -= len;
            x = bx;
            z = bz;
            seg++;
        }
        Step last = path.get(path.size() - 1);
        return new double[]{last.x() + 0.5, last.z() + 0.5, path.size() - 1};
    }
}
