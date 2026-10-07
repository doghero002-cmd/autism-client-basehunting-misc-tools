package textureui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-grid detector: finds where one block ends and the next begins in a screenshot and
 * proposes the four corners of the block region, so the user doesn't have to place them by hand.
 *
 * Approach (fast, dependency-free): compute a gradient-magnitude edge map, then for each of a
 * spread of candidate horizontal angles accumulate "edge energy" along parallel lines sweeping
 * the image - block boundaries form long straight seams, so angles with strong, evenly-spaced
 * line responses win. The two dominant line families give the grid's perspective; their extreme
 * intersections bound the region. Falls back to a centred quad when no confident grid emerges.
 */
public final class GridDetector {

    private GridDetector() {}

    /** The four region corners in image pixels, TL TR BR BL order. */
    public record Quad(double[] pts) {
        public double x(int i) { return pts[i * 2]; }
        public double y(int i) { return pts[i * 2 + 1]; }
    }

    /**
     * Detects the block-region corners. {@code topDown} hints the camera looks at a floor
     * (grid converges upward) vs a wall (roughly rectangular); either way we return a quad.
     */
    public static Quad detect(BufferedImage img, boolean topDown) {
        int w = img.getWidth(), h = img.getHeight();
        // Work on a downscaled luminance image for speed; scale corners back up at the end.
        int sw = Math.min(w, 480);
        int sh = Math.max(1, h * sw / w);
        double scale = (double) w / sw;
        float[][] lum = downscaleLum(img, sw, sh);
        float[][] grad = sobel(lum, sw, sh);

        // Two dominant seam directions via a coarse angle sweep.
        double ang1 = bestAngle(grad, sw, sh, 0, 90, topDown ? 0 : 1);
        double ang2 = bestAngle(grad, sw, sh, ang1 + 60, ang1 + 120, topDown ? 1 : 0);
        if (ang2 < ang1) { double t = ang1; ang1 = ang2; ang2 = t; }

        // Line positions along each family's normal with the strongest edge response.
        int[] fam1 = dominantLines(grad, sw, sh, ang1);
        int[] fam2 = dominantLines(grad, sw, sh, ang2);

        Quad q = intersectFamilies(fam1, ang1, fam2, ang2, sw, sh);
        if (q == null) {
            // Fallback: centred 70% quad (slight top-down trapezoid).
            double mx = w * 0.15, myTop = topDown ? h * 0.05 : h * 0.15, myBot = h * 0.15;
            return new Quad(new double[]{
                mx, myTop, w - mx, myTop, w - mx, h - myBot, mx, h - myBot});
        }
        // Scale back to full image coordinates.
        double[] p = q.pts();
        for (int i = 0; i < p.length; i++) p[i] *= scale;
        // Clamp into the image.
        for (int i = 0; i < 4; i++) {
            p[i * 2] = Math.max(0, Math.min(w - 1, p[i * 2]));
            p[i * 2 + 1] = Math.max(0, Math.min(h - 1, p[i * 2 + 1]));
        }
        return new Quad(p);
    }

    private static float[][] downscaleLum(BufferedImage img, int sw, int sh) {
        float[][] out = new float[sh][sw];
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < sh; y++) {
            int sy = Math.min(h - 1, y * h / sh);
            for (int x = 0; x < sw; x++) {
                int sx = Math.min(w - 1, x * w / sw);
                int rgb = img.getRGB(sx, sy);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                out[y][x] = 0.299f * r + 0.587f * g + 0.114f * b;
            }
        }
        return out;
    }

    private static float[][] sobel(float[][] lum, int w, int h) {
        float[][] g = new float[h][w];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float gx = (lum[y - 1][x + 1] + 2 * lum[y][x + 1] + lum[y + 1][x + 1])
                         - (lum[y - 1][x - 1] + 2 * lum[y][x - 1] + lum[y + 1][x - 1]);
                float gy = (lum[y + 1][x - 1] + 2 * lum[y + 1][x] + lum[y + 1][x + 1])
                         - (lum[y - 1][x - 1] + 2 * lum[y - 1][x] + lum[y - 1][x + 1]);
                g[y][x] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        return g;
    }

    /**
     * Finds the line direction whose parallel seams carry the most evenly-spaced edge energy.
     * Sweeps angles in [loDeg, hiDeg); for each, projects the gradient onto the normal and
     * scores peak regularity (autocorrelation at block-like spacings).
     */
    private static double bestAngle(float[][] grad, int w, int h, double loDeg, double hiDeg, int prefer) {
        double best = loDeg, bestScore = -1;
        for (double a = loDeg; a < hiDeg; a += 5) {
            double score = angleScore(grad, w, h, Math.toRadians(a));
            if (score > bestScore) { bestScore = score; best = a; }
        }
        return best;
    }

    /** Edge energy projected along a direction; rewards many strong, spaced seams. */
    private static double angleScore(float[][] grad, int w, int h, double ang) {
        double nx = Math.cos(ang), ny = Math.sin(ang);
        int diag = (int) Math.hypot(w, h);
        double[] proj = new double[diag * 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int d = (int) (x * nx + y * ny) + diag;
                if (d >= 0 && d < proj.length) proj[d] += grad[y][x];
            }
        }
        // Regularity: count local maxima separated by block-ish gaps; more = better grid.
        int peaks = 0;
        double peakSum = 0;
        int lastPeak = -100;
        for (int i = 1; i < proj.length - 1; i++) {
            if (proj[i] > 0 && proj[i] >= proj[i - 1] && proj[i] >= proj[i + 1]) {
                if (i - lastPeak >= 4) {
                    peaks++;
                    peakSum += proj[i];
                    lastPeak = i;
                }
            }
        }
        return peaks >= 3 ? peakSum : 0;
    }

    /** The two outer line positions (in normal-space) bounding the seam family. */
    private static int[] dominantLines(float[][] grad, int w, int h, double angDeg) {
        double ang = Math.toRadians(angDeg);
        double nx = Math.cos(ang), ny = Math.sin(ang);
        int diag = (int) Math.hypot(w, h);
        double[] proj = new double[diag * 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int d = (int) (x * nx + y * ny) + diag;
                if (d >= 0 && d < proj.length) proj[d] += grad[y][x];
            }
        }
        // Find first/last index with a meaningful response = the region's outer seams.
        double max = 0;
        for (double v : proj) max = Math.max(max, v);
        double thresh = max * 0.25;
        int lo = -1, hi = -1;
        for (int i = 0; i < proj.length; i++) {
            if (proj[i] >= thresh) { if (lo < 0) lo = i; hi = i; }
        }
        return new int[]{lo - diag, hi - diag}; // back to signed normal coords
    }

    /** Intersect the outer seams of two families into a quad (TL TR BR BL in small-image px). */
    private static Quad intersectFamilies(int[] fam1, double ang1Deg, int[] fam2, double ang2Deg, int w, int h) {
        if (fam1[0] >= fam1[1] || fam2[0] >= fam2[1]) return null;
        double a1 = Math.toRadians(ang1Deg), a2 = Math.toRadians(ang2Deg);
        double cx = w / 2.0, cy = h / 2.0;
        // Each family normal line: x*cosA + y*sinA = d. Two lines per family -> 4 intersections.
        double[] xs = new double[4], ys = new double[4];
        int i = 0;
        for (int d1 : fam1) {
            for (int d2 : fam2) {
                double det = Math.cos(a1) * Math.sin(a2) - Math.cos(a2) * Math.sin(a1);
                if (Math.abs(det) < 1e-6) return null;
                double x = (d1 * Math.sin(a2) - d2 * Math.sin(a1)) / det;
                double y = (Math.cos(a1) * d2 - Math.cos(a2) * d1) / det;
                xs[i] = x + cx;
                ys[i] = y + cy;
                i++;
            }
        }
        // Order the 4 points into TL TR BR BL by angle around the centroid.
        double mx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4, my = (ys[0] + ys[1] + ys[2] + ys[3]) / 4;
        Integer[] idx = {0, 1, 2, 3};
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(
            Math.atan2(ys[p] - my, xs[p] - mx), Math.atan2(ys[q] - my, xs[q] - mx)));
        // idx is CCW starting somewhere; map to TL,TR,BR,BL (screen coords, y down).
        double[] pts = new double[8];
        // TL = min x+y, BR = max x+y, TR = max x-y, BL = min x-y.
        int tl = 0, tr = 0, br = 0, bl = 0;
        for (int k = 1; k < 4; k++) {
            if (xs[k] + ys[k] < xs[tl] + ys[tl]) tl = k;
            if (xs[k] + ys[k] > xs[br] + ys[br]) br = k;
            if (xs[k] - ys[k] > xs[tr] - ys[tr]) tr = k;
            if (xs[k] - ys[k] < xs[bl] - ys[bl]) bl = k;
        }
        pts[0] = xs[tl]; pts[1] = ys[tl];
        pts[2] = xs[tr]; pts[3] = ys[tr];
        pts[4] = xs[br]; pts[5] = ys[br];
        pts[6] = xs[bl]; pts[7] = ys[bl];
        return new Quad(pts);
    }
}
