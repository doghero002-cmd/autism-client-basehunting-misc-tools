package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Region Map HUD.
 *
 * Draws a small DonutSMP RTP-region grid as an overlay: the 6 region servers laid out in a
 * 3x2 map (EU on top, NA/Asia/Oceania below), with a marker at the player's position mapped
 * onto the grid and the current region highlighted. A lightweight port of the Zelith
 * "RegionMap" module as a screen-space HUD element instead of a world-space render.
 */
public final class RegionMapHud implements HudElementProvider {
    private static final int CELL = 26;
    private static final int COLS = 3;
    private static final int ROWS = 2;
    private static final int PAD = 3;

    // Region layout (matches DonutSMP's RTP region geography, simplified).
    private static final String[][] REGIONS = {
        { "EU Central", "EU West", "" },
        { "NA East", "NA West", "Asia" }
    };
    private static final int[][] COLORS = {
        { 0xFF9CCC65, 0xFF00A65A, 0xFF000000 },
        { 0xFF4FA9DD, 0xFF2F6BB0, 0xFFF6C445 }
    };

    // World-space extent mapped onto the grid (rough DonutSMP region borders, centered on 0,0).
    private static final double WORLD_RADIUS = 300000.0;

    @Override public String id() { return SeedcrackerAddon.ID + ":region-map"; }
    @Override public String label() { return "Region Map"; }
    @Override public String description() { return "Overlay map of the DonutSMP RTP regions."; }

    @Override public int width() { return COLS * CELL + PAD * 2; }
    @Override public int height() { return ROWS * CELL + PAD * 2; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        Minecraft mc = Minecraft.getInstance();

        // Cells.
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                String name = REGIONS[r][c];
                int cx = x + PAD + c * CELL;
                int cy = y + PAD + r * CELL;
                int color = COLORS[r][c];
                int bg = name.isEmpty() ? 0xFF141418 : ((color & 0x00FFFFFF) | 0x66000000);
                ctx.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, bg);
                ctx.fill(cx, cy, cx + CELL - 1, cy + 1, 0xFF3A3A46);
                if (!name.isEmpty() && font != null) {
                    String abbr = abbreviate(name);
                    int tw = font.width(abbr);
                    ctx.text(font, abbr, cx + (CELL - 1 - tw) / 2, cy + (CELL - 8) / 2, 0xFFFFFFFF);
                }
            }
        }

        // Player marker mapped from world coords onto the grid.
        if (mc.player != null) {
            double nx = clamp(mc.player.getX() / WORLD_RADIUS, -1.0, 1.0); // -1..1 west..east
            double nz = clamp(mc.player.getZ() / WORLD_RADIUS, -1.0, 1.0); // -1..1 north..south
            int px = x + PAD + (int) ((nx + 1.0) * 0.5 * (COLS * CELL - 1));
            int py = y + PAD + (int) ((nz + 1.0) * 0.5 * (ROWS * CELL - 1));
            ctx.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
            ctx.fill(px, py, px + 1, py + 1, 0xFFFF3B3B);
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
            default -> name;
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 36; }
}
