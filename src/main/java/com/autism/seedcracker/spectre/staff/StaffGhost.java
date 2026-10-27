// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/gui/StaffGhost.java
package com.autism.seedcracker.spectre.staff;

import java.util.UUID;
import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Timed native portrait, title and caution; never replaces a server title or fetches a skin itself. */
public final class StaffGhost implements HudElementProvider {
    private static String owner;
    private static UUID watcher;
    private static String name;
    private static int others;
    private static String evidence;
    private static Object level;
    private static Object connection;
    private static long started;

    public static void watching(String source, UUID id, String playerName, int otherPlayers, String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null || playerName == null) return;
        owner = source;
        watcher = id;
        name = playerName;
        others = Math.max(0, otherPlayers);
        evidence = reason;
        level = mc.level;
        connection = mc.getConnection();
        started = System.nanoTime();
    }

    public static void clear(String source) {
        if (source == null || source.equals(owner)) {
            owner = null;
            watcher = null;
            name = null;
            evidence = null;
            level = null;
            connection = null;
            started = 0;
        }
    }

    public static boolean hasWork() {
        if (owner == null) return false;
        Minecraft mc = Minecraft.getInstance();
        var module = ModuleLookup.get(owner);
        if (mc.level != level || mc.getConnection() != connection || module == null || !module.isEnabled() || elapsed() >= 8.5F) {
            clear(null);
            return false;
        }
        return true;
    }

    private static float elapsed() { return (System.nanoTime() - started) / 1_000_000_000F; }

    @Override public String id() { return SeedcrackerAddon.ID + ":staff-alert"; }
    @Override public String label() { return "Staff Activity Alert"; }
    @Override public String description() { return "Timed staff portrait and evidence warning, not a claim that somebody is watching."; }
    @Override public int width() { return 300; }
    @Override public int height() { return 54; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 8; }
    @Override public int defaultY() { return 200; }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int x, int y, float alpha) {
        if (!hasWork()) return;
        float t = elapsed();
        float fade = Math.min(1F, Math.min(t / 0.25F, (8.5F - t) / 0.75F)) * Math.clamp(alpha, 0F, 1F);
        int opacity = Math.round(255F * fade);
        g.fill(x, y, x + width(), y + height(), Math.round(190F * fade) << 24 | 0x201014);
        int left = x + 5;
        String shown = NameFilter.apply(name);
        if (t < 1.5F && watcher != null && shown.equals(name)) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(watcher);
            if (info != null) {
                PlayerFaceExtractor.extractRenderState(g, info.getSkin(), left, y + 5, 32, opacity << 24 | 0xFFFFFF);
                left += 38;
            }
        }
        String title = t >= 4.5F ? "Presence clues are not proof of watching" : shown + (others > 0 ? " +" + others : "");
        g.text(font, font.plainSubstrByWidth(title, width() - (left - x) - 5), left, y + 6, opacity << 24 | 0xFFB454, false);
        String detail = t >= 4.5F ? "Use the staff / visibility HUD for context." : evidence;
        g.text(font, font.plainSubstrByWidth(detail == null ? "Staff activity signal" : detail, width() - (left - x) - 5),
            left, y + 21, opacity << 24 | 0xFFFFFF, false);
        g.text(font, "Local observation - not guaranteed vanish detection", x + 5, y + 39, opacity << 24 | 0xC8C8C8, false);
    }
}
