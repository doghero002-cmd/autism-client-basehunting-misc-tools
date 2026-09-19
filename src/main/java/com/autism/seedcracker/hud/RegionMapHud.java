package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;

/**
 * Region Map HUD.
 *
 * DonutSMP server-region overlay coloured by cluster, ported from the CodeEngine "RegionMap"
 * module (dev.nyx.module.modules.donutsmp.RegionMap) to the AUTISM screen-space HUD renderer.
 *
 * Uses CodeEngine's real region grid: a 9x9 cell map (byte lookup table mapping each cell to a
 * server cluster), the same cell-index math ({@link #cellIndexOf}), the same cluster-lookup
 * fallback, the six server clusters with their real colours, a header showing the player's cell,
 * and a highlight on the cell the player is currently in.
 */
public final class RegionMapHud implements HudElementProvider {
    private static final int PAD = 3;
    private static final int GAP = 1;

    // Grid configuration (CodeEngine defaults).
    private static final int GRID = 9;                   // cells per side
    private static final int CELL = 18;                  // px per cell
    private static final int REGION_CELL_BLOCKS = 50000; // blocks per region cell

    /**
     * CodeEngine region byte-map: 81 entries (9x9, row-major), each an index into the cluster
     * values() array. Index 5 = "ocean / unclaimed" filler in the source.
     */
    private static final byte[] GRID_MAP = {
        3, 3, 3, 2, 2, 2, 2, 2, 5,
        3, 3, 3, 2, 2, 2, 2, 2, 5,
        3, 3, 3, 2, 2, 2, 2, 2, 5,
        5, 5, 3, 2, 2, 2, 2, 2, 4,
        4, 4, 4, 2, 2, 2, 2, 2, 4,
        1, 1, 0, 0, 0, 0, 0, 2, 4,
        1, 1, 0, 0, 0, 0, 0, 2, 0,
        1, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 1, 1, 0, 0, 0
    };

    /** Server clusters (CodeEngine values() order + labels). Colours chosen to match the source. */
    public enum Cluster {
        EU_CENTRAL("EU Central", 0xFF9FCE63),
        EU_WEST("EU West", 0xFF00A663),
        NA_EAST("NA East", 0xFF4FADE6),
        NA_WEST("NA West", 0xFF2F6EBA),
        ASIA("Asia", 0xFFF5C242),
        OCEANIA("Oceania", 0xFFFC8803),
        NONE("", 0xFF141418);

        public final String label;
        public final int argb;
        Cluster(String label, int argb) { this.label = label; this.argb = argb; }
    }

    /**
     * Water Client region layout: [regionId, clusterIndex] per cell (row-major, 9x9). The
     * regionId is the real DonutSMP region number shown in each cell; clusterIndex maps to the
     * Cluster enum order (EU Central, EU West, NA East, NA West, Asia, Oceania).
     */
    private static final int[][] REGION_LAYOUT = {
        {82, 4}, {100, 2}, {101, 2}, {102, 2}, {103, 1}, {104, 1}, {105, 1}, {106, 1}, {91, 1},
        {83, 4}, {44, 2}, {75, 2}, {42, 2}, {41, 1}, {40, 1}, {39, 1}, {38, 1}, {92, 1},
        {84, 4}, {45, 2}, {14, 2}, {13, 2}, {12, 1}, {11, 1}, {10, 1}, {37, 1}, {93, 1},
        {85, 4}, {46, 4}, {74, 4}, {3, 2}, {2, 1}, {1, 1}, {25, 1}, {36, 1}, {94, 1},
        {86, 3}, {47, 3}, {72, 3}, {71, 3}, {5, 1}, {4, 1}, {24, 1}, {35, 1}, {95, 1},
        {87, 3}, {51, 0}, {17, 0}, {9, 5}, {8, 5}, {7, 5}, {23, 5}, {34, 5}, {96, 1},
        {88, 3}, {54, 0}, {18, 0}, {61, 5}, {62, 5}, {21, 5}, {22, 5}, {33, 5}, {97, 5},
        {89, 5}, {26, 0}, {27, 5}, {28, 5}, {29, 5}, {30, 5}, {59, 5}, {32, 5}, {98, 5},
        {90, 5}, {107, 0}, {108, 0}, {109, 0}, {110, 0}, {111, 0}, {112, 0}, {113, 0}, {99, 5}
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

    /** The cluster for a 1-based cell from the water region layout (NONE for index 5 filler). */
    public static Cluster layoutClusterOf(int cell) {
        if (cell < 1 || cell > REGION_LAYOUT.length) return Cluster.NONE;
        int idx = REGION_LAYOUT[cell - 1][1];
        if (idx < 0 || idx >= LAYOUT_CLUSTERS.length) return Cluster.NONE;
        // Water layout uses index 5 as "Oceania" for its map; index 5 cells are still coloured.
        return LAYOUT_CLUSTERS[idx];
    }

    /**
     * Sub-cell player position within a cell: [0..1, 0..1] position of the player inside their
     * current cell (for smooth, non-snapping player marker placement). Water Client
     * worldToCellPosition, using the 50k-block / 225k-offset region math.
     */
    public static double[] cellPositionOf(double worldX, double worldZ, int cellBlocks) {
        double offset = ((long) 9 * cellBlocks) / 2.0;
        double cx = ((worldX + offset) % cellBlocks) / cellBlocks;
        double cz = ((worldZ + offset) % cellBlocks) / cellBlocks;
        return new double[] {
            Math.max(0.0, Math.min(1.0, cx)),
            Math.max(0.0, Math.min(1.0, cz))
        };
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
        if (grid == GRID && cell <= GRID_MAP.length) {
            int idx = GRID_MAP[cell - 1] & 0xFF;
            Cluster[] vals = Cluster.values();
            if (idx < vals.length - 1) return vals[idx]; // exclude NONE
            return Cluster.NONE;
        }
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
