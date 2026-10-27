// Region data adapted from cody-raves/Spectre d5a7dd3; GPL-3.0-only. See SPECTRE-NOTICES.txt.
package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;

/** Spectre's Donut region layout, shared by the module and the legacy HUD adapter. */
public final class RegionMapHud implements HudElementProvider {
    private static final int PAD = 3;
    private static final int GAP = 1;

    // Grid configuration (CodeEngine defaults).
    private static final int GRID = 9;                   // cells per side
    private static final int CELL = 18;                  // px per cell
    private static final int REGION_CELL_BLOCKS = 50000; // blocks per region cell

    /** Spectre's cluster order, with ABGR colours converted to Minecraft ARGB. */
    public enum Cluster {
        EU_CENTRAL("EU Central", 0xFF9FCE63),
        EU_WEST("EU West", 0xFF00A663),
        NA_EAST("NA East", 0xFF4FADEA),
        NA_WEST("NA West", 0xFF2F6EBA),
        ASIA("Asia", 0xFFF5C242),
        OCEANIA("Oceania", 0xFFFC8803),
        NONE("", 0xFF141418);

        public final String label;
        public final int argb;
        Cluster(String label, int argb) { this.label = label; this.argb = argb; }
    }

    // Spectre DonutRegionMapData: [region ID, cluster index], row-major.
    private static final int[][] REGION_LAYOUT = {
        {82, 5}, {100, 3}, {101, 3}, {102, 3}, {103, 2}, {104, 2}, {105, 2}, {106, 2}, {91, 2},
        {83, 5}, {44, 3}, {75, 3}, {42, 3}, {41, 2}, {40, 2}, {39, 2}, {38, 2}, {92, 2},
        {84, 5}, {45, 3}, {14, 3}, {13, 3}, {12, 2}, {11, 2}, {10, 2}, {37, 2}, {93, 2},
        {85, 5}, {46, 5}, {74, 5}, {3, 3}, {2, 2}, {1, 2}, {25, 2}, {36, 2}, {94, 2},
        {86, 4}, {47, 4}, {72, 4}, {71, 4}, {5, 2}, {4, 2}, {24, 2}, {35, 2}, {95, 2},
        {87, 4}, {51, 1}, {17, 1}, {9, 0}, {8, 0}, {7, 0}, {23, 0}, {34, 0}, {96, 2},
        {88, 4}, {54, 1}, {18, 1}, {61, 0}, {62, 0}, {21, 0}, {22, 0}, {33, 0}, {97, 0},
        {89, 0}, {26, 1}, {27, 0}, {28, 0}, {29, 0}, {30, 0}, {59, 0}, {32, 0}, {98, 0},
        {90, 0}, {107, 1}, {108, 1}, {109, 1}, {110, 1}, {111, 1}, {112, 1}, {113, 1}, {99, 0}
    };

    /** Cluster-index -> Cluster, matching REGION_LAYOUT's clusterIndex (0-based into the 6 real clusters). */
    private static final Cluster[] LAYOUT_CLUSTERS = {
        Cluster.EU_CENTRAL, Cluster.EU_WEST, Cluster.NA_EAST, Cluster.NA_WEST, Cluster.ASIA, Cluster.OCEANIA
    };

    /** The real DonutSMP region ID for a 1-based cell, or -1 if out of range. */
    public static int regionIdOf(int cell) {
        if (cell < 1 || cell > REGION_LAYOUT.length) return -1;
        return REGION_LAYOUT[cell - 1][0];
    }

    public static Cluster layoutClusterOf(int cell) {
        if (cell < 1 || cell > REGION_LAYOUT.length) return Cluster.NONE;
        return LAYOUT_CLUSTERS[REGION_LAYOUT[cell - 1][1]];
    }

    public static int cellAt(double worldX, double worldZ, int cellBlocks) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ) || cellBlocks < 1) return -1;
        double half = GRID * (double) cellBlocks / 2;
        int col = (int) Math.floor((worldX + half) / cellBlocks);
        int row = (int) Math.floor((worldZ + half) / cellBlocks);
        return col >= 0 && row >= 0 && col < GRID && row < GRID ? row * GRID + col + 1 : -1;
    }

    public static int effectiveGap(int size, int gap) {
        return Math.max(0, Math.min(gap, (size - GRID) / (GRID - 1)));
    }

    public static double cellStart(int cell, int size, int gap) {
        gap = effectiveGap(size, gap);
        return cell * ((size - (GRID - 1) * gap) / (double) GRID + gap);
    }

    public static double cellWidth(int size, int gap) {
        return (size - (GRID - 1) * effectiveGap(size, gap)) / (double) GRID;
    }

    public static double mapPixel(double fraction, int size, int gap) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction >= 1) return Double.NaN;
        double cell = fraction * GRID;
        int index = (int) Math.floor(cell);
        return cellStart(index, size, gap) + (cell - index) * cellWidth(size, gap);
    }

    public static double mapFraction(double pixel, int size, int gap) {
        if (!Double.isFinite(pixel) || pixel < 0 || pixel >= size) return Double.NaN;
        double width = cellWidth(size, gap);
        int cell = (int) Math.floor(pixel / (width + effectiveGap(size, gap)));
        double inside = pixel - cellStart(cell, size, gap);
        return inside < width ? (cell + inside / width) / GRID : Double.NaN;
    }

    /**
     * Sub-cell player position within a cell: [0..1, 0..1] position of the player inside their
     * current cell (for smooth, non-snapping player marker placement). Water Client
     * worldToCellPosition, using the 50k-block / 225k-offset region math.
     */
    public static double[] cellPositionOf(double worldX, double worldZ, int cellBlocks) {
        double offset = ((long) 9 * cellBlocks) / 2.0;
        double cx = (worldX + offset) / cellBlocks;
        double cz = (worldZ + offset) / cellBlocks;
        return new double[] {cx - Math.floor(cx), cz - Math.floor(cz)};
    }

    /** CodeEngine's cluster fallback array (regionMapServerClusterArray). */
    private static final Cluster[] FALLBACK = {
        Cluster.NA_WEST, Cluster.NA_EAST, Cluster.OCEANIA,
        Cluster.EU_WEST, Cluster.EU_CENTRAL, Cluster.ASIA
    };

    @Override public String id() { return SeedcrackerAddon.ID + ":region-map"; }
    @Override public String label() { return "Region Map"; }
    @Override public String description() { return "DonutSMP server-region overlay coloured by cluster."; }

    @Override public int width() { return GRID * CELL + (GRID - 1) * GAP + PAD * 2; }
    @Override public int height() { return GRID * CELL + (GRID - 1) * GAP + PAD * 2 + 12; }

    /**
     * CodeEngine cell-index math (RegionMap.intOf): maps a world X/Z to a 1-based cell number in
     * the grid, centred on 0,0, clamped to the grid edges.
     */
    public static int cellIndexOf(int blockX, int blockZ, int grid, int cellBlocks) {
        if (grid < 1) grid = 1;
        if (cellBlocks < 1) cellBlocks = 1;
        long half = (long) grid * cellBlocks / 2L;
        long ax = blockX + half;
        long az = blockZ + half;
        int cx = (int) Math.floorDiv(ax, (long) cellBlocks);
        int cz = (int) Math.floorDiv(az, (long) cellBlocks);
        if (cx < 0) cx = 0; else if (cx >= grid) cx = grid - 1;
        if (cz < 0) cz = 0; else if (cz >= grid) cz = grid - 1;
        return cz * grid + cx + 1;
    }

    /** CodeEngine cluster lookup (RegionMap.regionMapServerClusterOf): byte-map then fallback. */
    public static Cluster clusterOf(int cell, int grid) {
        if (cell < 1 || grid < 1) return Cluster.NA_EAST;
        int max = grid * grid;
        if (cell > max) cell = max;
        if (grid == GRID) return layoutClusterOf(cell);
        int col = (cell - 1) % grid;
        int row = (cell - 1) / grid;
        int fx = Math.min(2, col * 3 / Math.max(1, grid));
        int fy = Math.min(1, row * 2 / Math.max(1, grid));
        return FALLBACK[fy * 3 + fx];
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        int playerCell = -1;
        if (mc.player != null) {
            try {
                BlockPos p = mc.player.blockPosition();
                playerCell = cellIndexOf(p.getX(), p.getZ(), GRID, REGION_CELL_BLOCKS);
            } catch (Throwable ignored) {}
        }

        int gridW = GRID * CELL + (GRID - 1) * GAP;

        // Header.
        if (font != null) {
            ctx.text(font, "REGION MAP", x + PAD, y + 1, 0xFFC8C8D8);
            if (playerCell >= 1) {
                String cellTxt = "cell " + playerCell;
                int cw = font.width(cellTxt);
                ctx.text(font, cellTxt, x + PAD + gridW - cw, y + 1, 0xFF8A93A6);
            }
        }
        int top = y + 10 + PAD;

        // Cells.
        for (int cell = 1; cell <= GRID * GRID; cell++) {
            Cluster cluster = clusterOf(cell, GRID);
            int col = (cell - 1) % GRID;
            int row = (cell - 1) / GRID;
            int cx = x + PAD + col * (CELL + GAP);
            int cy = top + row * (CELL + GAP);
            int bg = (cluster.argb & 0x00FFFFFF) | 0x66000000;
            ctx.fill(cx, cy, cx + CELL, cy + CELL, bg);
            boolean isPlayer = cell == playerCell;
            int border = isPlayer ? 0xFFFFFFFF : 0xFF3A3A46;
            ctx.fill(cx, cy, cx + CELL, cy + 1, border);
            ctx.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, border);
            ctx.fill(cx, cy, cx + 1, cy + CELL, border);
            ctx.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, border);
            if (isPlayer) {
                ctx.fill(cx + CELL / 2 - 1, cy + CELL / 2 - 1, cx + CELL / 2 + 2, cy + CELL / 2 + 2, 0xFFFF3B3B);
            }
            if (font != null && cluster != Cluster.NONE && CELL >= 12) {
                String abbr = abbreviate(cluster.label);
                int tw = font.width(abbr);
                ctx.text(font, abbr, cx + (CELL - tw) / 2, cy + (CELL - 8) / 2, 0xFFFFFFFF);
            }
        }
    }

    private static String abbreviate(String name) {
        return switch (name) {
            case "EU Central" -> "EU-C";
            case "EU West" -> "EU-W";
            case "NA East" -> "NA-E";
            case "NA West" -> "NA-W";
            case "Asia" -> "AS";
            case "Oceania" -> "OC";
            default -> "";
        };
    }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 36; }
}
