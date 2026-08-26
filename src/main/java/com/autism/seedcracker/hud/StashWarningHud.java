package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.rtp.DonutRTPStashFinderModule;
import com.autism.seedcracker.rtp.RelogLoaderModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Persistent on-screen anti-cheat warning shown while the Donut RTP Stash Finder or the
 * Relog Loader module is active. Renders nothing when both are off.
 */
public final class StashWarningHud implements HudElementProvider {
    private static final int PAD = 4;
    private static final String TEXT = "⚠ ANTI-CHEAT RISK - automated movement active";

    @Override public String id() { return SeedcrackerAddon.ID + ":stash-warning"; }
    @Override public String label() { return "Stash AC Warning"; }
    @Override public String description() { return "On-screen anti-cheat warning while a stash module is active."; }

    private static boolean active() {
        return DonutRTPStashFinderModule.ACTIVE || RelogLoaderModule.ACTIVE;
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        return (font != null ? font.width(TEXT) : TEXT.length() * 6) + PAD * 2;
    }

    @Override public int height() { return 12; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!active()) return;
        // Flashing red/amber backdrop.
        boolean flash = (System.currentTimeMillis() / 500L) % 2 == 0;
        int bg = flash ? 0xCC5B1010 : 0xCC3A2A00;
        int fg = flash ? 0xFFFF5B5B : 0xFFFFC857;
        ctx.fill(x, y, x + width(), y + height(), bg);
        ctx.text(font, TEXT, x + PAD, y + 2, fg);
    }

    @Override public boolean defaultEnabled() { return true; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 20; }
}
