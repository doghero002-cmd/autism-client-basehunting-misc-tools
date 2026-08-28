package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.fake.FakeBalance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fake Scoreboard HUD.
 *
 * A sidebar-style overlay showing a fake DonutSMP profile (money, shards, kills, deaths,
 * playtime) with an editable title. The money line tracks the shared fake balance that
 * Fake Pay / Fake Payments add to. Ported from the Zelith "FakeScoreboard" module as a
 * screen-space HUD element rather than a scoreboard-sidebar spoof (safer, no mixin).
 */
public final class FakeScoreboardHud implements HudElementProvider {
    private static final int PAD = 4;
    private static final int LINE = 10;
    private static final int BG = 0x90101018;
    private static final int TITLE_COLOR = 0xFF55FFFF;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFF55FF55;

    // Editable display values (money tracks the shared FakeBalance).
    public static String title = "Zelith";
    public static String shards = "780";
    public static String kills = "230";
    public static String deaths = "160";
    public static String playtime = "21d 5h";

    private static String[] lines() {
        return new String[] {
            "Money: " + FakeBalance.format(FakeBalance.get()),
            "Shards: " + shards,
            "Kills: " + kills,
            "Deaths: " + deaths,
            "Playtime: " + playtime
        };
    }

    @Override public String id() { return SeedcrackerAddon.ID + ":fake-scoreboard"; }
    @Override public String label() { return "Fake Scoreboard"; }
    @Override public String description() { return "Sidebar overlay with a fake DonutSMP profile."; }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int max = font != null ? font.width(title) : title.length() * 6;
        if (font != null) {
            for (String l : lines()) max = Math.max(max, font.width(l));
        }
        return max + PAD * 2;
    }

    @Override public int height() { return LINE * (lines().length + 1) + PAD; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        int w = width();
        int h = height();
        ctx.fill(x, y, x + w, y + h, BG);
        if (font == null) return;
        ctx.text(font, title, x + PAD, y + 2, TITLE_COLOR);
        String[] lines = lines();
        for (int i = 0; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                ctx.text(font, lines[i].substring(0, colon + 1), x + PAD, y + 2 + LINE * (i + 1), LABEL_COLOR);
                int labelW = font.width(lines[i].substring(0, colon + 1) + " ");
                ctx.text(font, lines[i].substring(colon + 1).trim(), x + PAD + labelW, y + 2 + LINE * (i + 1), VALUE_COLOR);
            } else {
                ctx.text(font, lines[i], x + PAD, y + 2 + LINE * (i + 1), LABEL_COLOR);
            }
        }
    }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_RIGHT"; }
    @Override public int defaultX() { return -4; }
    @Override public int defaultY() { return 40; }
}
