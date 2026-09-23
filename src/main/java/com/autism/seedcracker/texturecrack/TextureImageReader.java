package com.autism.seedcracker.texturecrack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Automated rotation-grid reader: template-matches a screenshot region against the game's own
 * block textures (all 4 rotations) and produces the observation grid + per-cell confidence
 * weights for the solver.
 *
 * The region is described by its 4 corner pixels (TL TR BR BL) so angled floor shots work - a
 * projective (perspective-correct) homography maps each grid cell onto the image. Every cell is
 * sampled at several sub-texel alignment offsets (hand-placed corners are never pixel-perfect)
 * and the best-scoring alignment wins. Patches get a linear brightness plane removed (kills the
 * directional-light / AO slope that survives mean subtraction on angled shots) and are then
 * zero-mean unit-norm so the dot product against each rotated reference is the NCC.
 *
 * A cell whose best score is weak, or whose best-vs-second margin is thin, is emitted as UNKNOWN
 * rather than guessed: the solver tolerates unknowns for free while wrong cells burn tolerance.
 * The reference's own rotation self-similarity is measured up front so near-isotropic textures
 * (sand) warn that their signal is inherently weak.
 */
public final class TextureImageReader {

    public record Cell(int rotation, double score, double margin) {}
    public record Result(int[][] grid, float[][] weights, Cell[][] cells, int known,
                         int rows, int cols, double refSelfSimilarity) {}

    private static final Map<String, String> BLOCK_TEXTURES = Map.ofEntries(
        Map.entry("dirt", "textures/block/dirt.png"),
        Map.entry("coarse_dirt", "textures/block/coarse_dirt.png"),
        Map.entry("rooted_dirt", "textures/block/rooted_dirt.png"),
        Map.entry("netherrack", "textures/block/netherrack.png"),
        Map.entry("sand", "textures/block/sand.png"),
        Map.entry("red_sand", "textures/block/red_sand.png"),
        Map.entry("gravel", "textures/block/gravel.png"),
        Map.entry("grass", "textures/block/grass_block_top.png"),
        Map.entry("grass_block", "textures/block/grass_block_top.png"),
        Map.entry("mycelium", "textures/block/mycelium_top.png"),
        Map.entry("podzol", "textures/block/podzol_top.png"),
        Map.entry("end_stone", "textures/block/end_stone.png"),
        Map.entry("mud", "textures/block/mud.png"),
        Map.entry("soul_sand", "textures/block/soul_sand.png"),
        Map.entry("soul_soil", "textures/block/soul_soil.png"),
        Map.entry("bedrock", "textures/block/bedrock.png"));

    /** Gates below which a cell is emitted as unknown instead of a guess. */
    private static final double MIN_SCORE = 0.20;
    private static final double MIN_MARGIN = 0.03;

    /** Sub-texel alignment offsets tested per cell (fractions of one cell). */
    private static final double[] ALIGN = {-0.09, -0.045, 0.0, 0.045, 0.09};

    private TextureImageReader() {}

    public static String textureFor(String block) {
        String key = block.toLowerCase(Locale.ROOT);
        if (key.contains(":")) key = key.substring(key.indexOf(':') + 1);
        String mapped = BLOCK_TEXTURES.get(key);
        return mapped != null ? mapped : "textures/block/" + key + ".png";
    }

    /**
     * @param image   screenshot file
     * @param block   block name (dirt/netherrack/sand/...) or bare texture name
     * @param rows    grid rows (TL->BL direction)
     * @param cols    grid cols (TL->TR direction)
     * @param corners TL,TR,BR,BL pixel coords (8 values) or null = whole image
     */
    public static Result read(File image, String block, int rows, int cols, double[] corners) throws Exception {
        BufferedImage img = ImageIO.read(image);
        if (img == null) throw new IllegalArgumentException("unreadable image: " + image.getName());
        return read(img, block, rows, cols, corners);
    }

    public static Result read(BufferedImage img, String block, int rows, int cols, double[] corners) throws Exception {
        // Mirrored templates only when the block's variant set can actually show them
        // (halves the compare work for plain rot4 blocks like dirt).
        boolean withMirrors;
        try {
            withMirrors = BlockVariantSet.load(block).hasMirrors();
        } catch (Throwable t) {
            withMirrors = false;
        }

        float[] refRaw = loadReference(block);
        int templateCount = withMirrors ? 8 : 4;
        // Template index = transform code: 0-3 rotations, |4 = mirrored.
        float[][] refs = new float[templateCount][];
        float[] rot = refRaw;
        refs[0] = normalize(deplane(refRaw.clone()));
        for (int i = 1; i < 4; i++) {
            rot = rotate90(rot);
            refs[i] = normalize(deplane(rot.clone()));
        }
        if (withMirrors) {
            float[] mir = mirrorX(refRaw);
            refs[BlockVariantSet.T_MIRROR] = normalize(deplane(mir.clone()));
            rot = mir;
            for (int i = 1; i < 4; i++) {
                rot = rotate90(rot);
                refs[BlockVariantSet.T_MIRROR + i] = normalize(deplane(rot.clone()));
            }
        }
        // Self-similarity: how much the texture even changes when rotated (isotropy warning).
        double selfSim = Math.max(dot(refs[0], refs[1]), Math.max(dot(refs[0], refs[2]), dot(refs[0], refs[3])));

        double[] quad = corners != null ? corners
            : new double[]{0, 0, img.getWidth() - 1, 0, img.getWidth() - 1, img.getHeight() - 1, 0, img.getHeight() - 1};
        double[] h = homography(quad);

        int[][] grid = new int[rows][cols];
        float[][] weights = new float[rows][cols];
        Cell[][] cells = new Cell[rows][cols];
        int known = 0;
        float[] sample = new float[256];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double best = -2, second = -2;
                int bestRot = -1;
                // Alignment search: hand-placed corners are off by a texel or so; a misaligned
                // sample blurs all 4 rotations together, so try small offsets and keep the best.
                for (double au : ALIGN) {
                    for (double av : ALIGN) {
                        sampleCell(img, h, c, r, cols, rows, au, av, sample);
                        float[] norm = normalize(deplane(sample));
                        for (int k = 0; k < refs.length; k++) {
                            if (refs[k] == null) continue;
                            double s = dot(norm, refs[k]);
                            if (s > best) {
                                if (k != bestRot) second = best;
                                best = s;
                                bestRot = k;
                            } else if (k != bestRot && s > second) {
                                second = s;
                            }
                        }
                    }
                }
                double margin = best - second;
                boolean ok = best >= MIN_SCORE && margin >= MIN_MARGIN;
                grid[r][c] = ok ? bestRot : -1;
                weights[r][c] = ok ? (float) Math.min(1.0, margin * 10.0) : 0f;
                if (ok) known++;
                cells[r][c] = new Cell(bestRot, best, margin);
            }
        }
        return new Result(grid, weights, cells, known, rows, cols, selfSim);
    }

    /** Reference texture from the ACTIVE resources (vanilla unless a pack overrides it), 16x16 grey. */
    private static float[] loadReference(String block) throws Exception {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", textureFor(block));
        var res = Minecraft.getInstance().getResourceManager().getResource(id);
        if (res.isEmpty()) throw new IllegalArgumentException("no texture " + id + " - unsupported block?");
        try (InputStream in = res.get().open()) {
            BufferedImage tex = ImageIO.read(in);
            if (tex == null) throw new IllegalArgumentException("unreadable texture " + id);
            return greyResize16(tex);
        }
    }

    /** Box-average any size down to 16x16 grey. Animated strips stack frames vertically: use the top frame. */
    private static float[] greyResize16(BufferedImage tex) {
        int size = Math.min(tex.getWidth(), tex.getHeight());
        float[] out = new float[256];
        double step = size / 16.0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int x0 = (int) (x * step), x1 = Math.max(x0 + 1, (int) ((x + 1) * step));
                int y0 = (int) (y * step), y1 = Math.max(y0 + 1, (int) ((y + 1) * step));
                double sum = 0;
                int n = 0;
                for (int yy = y0; yy < y1 && yy < size; yy++) {
                    for (int xx = x0; xx < x1 && xx < size; xx++) {
                        sum += grey(tex.getRGB(xx, yy));
                        n++;
                    }
                }
                out[y * 16 + x] = n > 0 ? (float) (sum / n) : 0f;
            }
        }
        return out;
    }

    /** Perspective-correct 16x16 sample of grid cell (c,r) at alignment offset (au,av), 2x2 subsamples. */
    private static void sampleCell(BufferedImage img, double[] h, int c, int r, int cols, int rows,
                                   double au, double av, float[] out) {
        int w = img.getWidth(), hgt = img.getHeight();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double sum = 0;
                for (int s = 0; s < 4; s++) {
                    double u = (c + au + (x + 0.25 + 0.5 * (s & 1)) / 16.0) / cols;
                    double v = (r + av + (y + 0.25 + 0.5 * (s >> 1)) / 16.0) / rows;
                    double den = h[6] * u + h[7] * v + h[8];
                    int px = (int) Math.round((h[0] * u + h[1] * v + h[2]) / den);
                    int py = (int) Math.round((h[3] * u + h[4] * v + h[5]) / den);
                    if (px < 0) px = 0; else if (px >= w) px = w - 1;
                    if (py < 0) py = 0; else if (py >= hgt) py = hgt - 1;
                    sum += grey(img.getRGB(px, py));
                }
                out[y * 16 + x] = (float) (sum / 4.0);
            }
        }
    }

    /** Heckbert unit-square -> quad projective map. Quad = TL,TR,BR,BL pixel coords. */
    static double[] homography(double[] q) {
        double x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3], x2 = q[4], y2 = q[5], x3 = q[6], y3 = q[7];
        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        double a13, a23;
        if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) {
            a13 = 0; a23 = 0; // affine quad
        } else {
            double den = dx1 * dy2 - dx2 * dy1;
            a13 = (dx3 * dy2 - dx2 * dy3) / den;
            a23 = (dx1 * dy3 - dx3 * dy1) / den;
        }
        return new double[]{
            x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
            y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
            a13, a23, 1.0
        };
    }

    /** Maps unit-square (u,v) through the homography to image pixels. */
    static double[] project(double[] h, double u, double v) {
        double den = h[6] * u + h[7] * v + h[8];
        return new double[]{(h[0] * u + h[1] * v + h[2]) / den, (h[3] * u + h[4] * v + h[5]) / den};
    }

    private static double grey(int argb) {
        return 0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF);
    }

    /** 90 degree CW rotation of a 16x16 patch. */
    private static float[] rotate90(float[] in) {
        float[] out = new float[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out[y * 16 + x] = in[(15 - x) * 16 + y];
            }
        }
        return out;
    }

    /** Horizontal mirror of a 16x16 patch (the "_mirrored" model variants, e.g. stone/bedrock). */
    private static float[] mirrorX(float[] in) {
        float[] out = new float[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out[y * 16 + x] = in[y * 16 + (15 - x)];
            }
        }
        return out;
    }

    /** Subtracts the least-squares brightness plane (directional light / AO slope). In-place. */
    private static float[] deplane(float[] p) {
        // x,y in -7.5..7.5; slopes = sum(v*x)/sum(x^2) with sum(x^2) = 16 * sum over one row.
        double sx = 0, sy = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double v = p[y * 16 + x];
                sx += v * (x - 7.5);
                sy += v * (y - 7.5);
            }
        }
        double denom = 16.0 * (2.0 * (0.5 * 0.5 + 1.5 * 1.5 + 2.5 * 2.5 + 3.5 * 3.5 + 4.5 * 4.5 + 5.5 * 5.5 + 6.5 * 6.5 + 7.5 * 7.5));
        double gx = sx / denom, gy = sy / denom;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                p[y * 16 + x] -= (float) (gx * (x - 7.5) + gy * (y - 7.5));
            }
        }
        return p;
    }

    /** Zero-mean unit-norm so dot() = NCC (brightness/contrast invariant). */
    private static float[] normalize(float[] in) {
        double mean = 0;
        for (float v : in) mean += v;
        mean /= in.length;
        double var = 0;
        for (float v : in) var += (v - mean) * (v - mean);
        double inv = var > 1e-9 ? 1.0 / Math.sqrt(var) : 0;
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) out[i] = (float) ((in[i] - mean) * inv);
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
