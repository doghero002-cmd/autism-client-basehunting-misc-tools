package com.autism.seedcracker.compat;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Obfuscation-proof HUD panel renderer. The client's gui.vanillaui.direct.DirectHudPanelRenderer
 * was renamed in later (obfuscated) builds; BalanceTags and FlagDetector used it to draw their
 * telemetry panels. This is a self-contained equivalent: a titled panel with body rows, drawn with
 * plain fill + text (the same primitives the rest of the addon's HUD code already uses). Row holds
 * a line + colour; render() draws the header strip, the panel background, and each row.
 */
public final class HudPanel {
    private HudPanel() {}

    private static final int PAD = 4;
    private static final int LINE_H = 10;

    /** One panel row (text + ARGB colour). Matches DirectHudPanelRenderer.Row. */
    public record Row(String text, int color) {
        public static Row body(String text, int color) {
            return new Row(text, color);
        }
    }

    /** Render a titled panel at (x,y) with the given width, title, body rows, and accent colour. */
    public static void render(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                              String title, List<Row> rows, int accent) {
        int height = PAD * 2 + (rows.size() + 1) * LINE_H;
        // Panel background + header.
        g.fill(x, y, x + width, y + height, 0xAA101418);
        g.fill(x, y, x + width, y + LINE_H + PAD, 0xFF0C0E11);
        g.text(font, title, x + PAD, y + PAD, accent, false);
        int ly = y + PAD + LINE_H + 2;
        for (Row r : rows) {
            g.text(font, r.text(), x + PAD, ly, r.color(), false);
            ly += LINE_H;
        }
    }
}
