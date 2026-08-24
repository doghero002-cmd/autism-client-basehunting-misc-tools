package com.autism.seedcracker.hud;

import java.util.Locale;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.hud.HudElementProvider;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * HUD element that shows the cracked world seed once SeedCrackerX has reduced the
 * candidate set to a single seed. While data is still being collected it shows live
 * cracking progress: bits of structure data gathered (of the 32 needed) and how many
 * candidate world seeds remain.
 */
public final class SeedHud implements HudElementProvider {
    private static final int MIN_WIDTH = 110;
    private static final int TEXT_PAD = 3;
    private static final int COLOR_FOUND = 0xFF55FF55;   // green
    private static final int COLOR_SEARCHING = 0xFFFFAA00; // amber

    @Override public String id() { return SeedcrackerAddon.ID + ":seed"; }
    @Override public String label() { return "SeedCracker Seed"; }
    @Override public String description() { return "Shows the cracked world seed and cracking progress."; }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        String text = text();
        int textWidth = font != null ? font.width(text) : text.length() * 6;
        return Math.max(MIN_WIDTH, textWidth + TEXT_PAD * 2);
    }

    @Override public int height() { return 10; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        ctx.text(font, text(), x + TEXT_PAD, y, color());
    }

    private static String text() {
        DataStorage data = data();
        if (data == null) {
            return "Seed: inactive";
        }

        // Cracked: a single world seed remains.
        Set<Long> seeds = data.getTimeMachine().worldSeeds;
        if (seeds != null && seeds.size() == 1) {
            return "Seed: " + seeds.iterator().next();
        }

        // In progress: show structure bits gathered out of the target, plus remaining candidates.
        double bits = data.getBaseBits();
        double wanted = data.getWantedBits();
        int pct = wanted > 0 ? (int) Math.min(100, Math.round(bits / wanted * 100.0)) : 0;
        String progress = String.format(Locale.ROOT, "%d%% (%.1f/%.0f bits)", pct, bits, wanted);

        int candidates = seeds == null ? 0 : seeds.size();
        if (candidates > 1) {
            return "Seed: " + progress + ", " + candidates + " left";
        }
        return "Seed: " + progress;
    }

    private static int color() {
        DataStorage data = data();
        if (data != null) {
            Set<Long> seeds = data.getTimeMachine().worldSeeds;
            if (seeds != null && seeds.size() == 1) {
                return COLOR_FOUND;
            }
        }
        return COLOR_SEARCHING;
    }

    private static DataStorage data() {
        try {
            SeedCracker sc = SeedCracker.get();
            return sc == null ? null : sc.getDataStorage();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public boolean defaultEnabled() { return true; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 4; }
}
