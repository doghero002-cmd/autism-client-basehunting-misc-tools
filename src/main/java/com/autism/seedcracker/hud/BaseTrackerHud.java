package com.autism.seedcracker.hud;

import java.util.List;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.BaseTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Base Tracker HUD.
 *
 * An on-screen list of the nearest flagged bases reported by the finder modules (via the shared
 * {@link BaseTracker} registry), sorted nearest-first. Each row shows the finder's name, the
 * base's coordinates, the distance to it, and a confidence bar - so you can see at a glance which
 * flagged chunk is worth checking first.
 */
public final class BaseTrackerHud implements HudElementProvider {
    private static final int PAD = 4;
    private static final int LINE = 10;
    private static final int MAX_ROWS = 6;
    private static final int BG = 0x90101018;
    private static final int TITLE_COLOR = 0xFF55FFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int DIM_COLOR = 0xFFAAAAAA;

    @Override public String id() { return SeedcrackerAddon.ID + ":base-tracker"; }
    @Override public String label() { return "Base Tracker"; }
    @Override public String description() { return "Nearest flagged bases with distance + confidence."; }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 60; }

    @Override
    public int width() {
        return 150;
    }

    @Override
    public int height() {
        return PAD * 2 + LINE * (MAX_ROWS + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (font == null || mc.player == null) return;

        List<BaseTracker.Entry> bases = BaseTracker.nearest(mc.player.getX(), mc.player.getZ(), MAX_ROWS);
        int w = width();
        int h = height();
        ctx.fill(x, y, x + w, y + h, BG);
        ctx.text(font, "Base Tracker", x + PAD, y + 2, TITLE_COLOR);

        if (bases.isEmpty()) {
            ctx.text(font, "No bases flagged", x + PAD, y + 2 + LINE, DIM_COLOR);
            return;
        }

        int row = 1;
        for (BaseTracker.Entry e : bases) {
            double dist = Math.hypot(e.blockX() - mc.player.getX(), e.blockZ() - mc.player.getZ());
            String line = String.format("%s §7%d,%d §f%.0fm", e.source(), e.blockX(), e.blockZ(), dist);
            int ly = y + 2 + LINE * row;
            ctx.text(font, line, x + PAD, ly, TEXT_COLOR);
            // Confidence bar on the right edge.
            int barW = 30;
            int filled = Math.round((Math.max(0, Math.min(100, e.confidence())) / 100f) * barW);
            int bx = x + w - PAD - barW;
            ctx.fill(bx, ly + 1, bx + barW, ly + 5, 0xFF333340);
            ctx.fill(bx, ly + 1, bx + filled, ly + 5, confidenceColor(e.confidence()));
            row++;
        }
    }

    private static int confidenceColor(int c) {
        if (c >= 70) return 0xFF55FF55; // high = green
        if (c >= 40) return 0xFFFFFF55; // mid = yellow
        return 0xFFFF5555;              // low = red
    }
}
