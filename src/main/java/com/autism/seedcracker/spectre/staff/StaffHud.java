// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/gui/HudRenderer.java (staff and anti-vanish rows).
package com.autism.seedcracker.spectre.staff;

import java.util.ArrayList;
import java.util.List;
import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.HudPanel;
import com.autism.seedcracker.modules.SpectatorDetectorModule;
import com.autism.seedcracker.modules.StaffListModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class StaffHud implements HudElementProvider {
    private static final String TITLE = "Staff / visibility clues";

    @Override public String id() { return SeedcrackerAddon.ID + ":staff-signals"; }
    @Override public String label() { return "Staff / Anti Vanish"; }
    @Override public String description() { return "Roster, visibility, distance, region-clue age and local sensor evidence."; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 8; }
    @Override public int defaultY() { return 60; }

    private static List<HudPanel.Row> rows() {
        List<HudPanel.Row> rows = new ArrayList<>();
        var staff = StaffListModule.hudRows();
        for (var row : staff.rows()) {
            String presence = switch (row.presence()) {
                case ONLINE -> "listed";
                case SPECTATOR -> "spectator";
                case UNLISTED -> "off TAB";
            };
            String location = row.nearby() ? " - " + row.distanceLabel()
                : row.sameServer() ? " - region clue " + row.sameServerAge() : "";
            int color = row.alertLevel() > 0F ? 0xFFFF7F55 : row.nearby() ? 0xFFFFC857 : 0xFFD6DFE8;
            rows.add(HudPanel.Row.body(row.name() + " (" + row.rank() + ") " + presence + location, color));
        }
        if (staff.more() > 0) rows.add(HudPanel.Row.body("+" + staff.more() + " other staff", 0xFFAAAAAA));
        for (var entry : SpectatorDetectorModule.hudEntries()) {
            String reason = entry.reason() == null ? "" : entry.reason();
            String value = SpectatorDetectorModule.hudValue(entry);
            rows.add(HudPanel.Row.body(SpectatorDetectorModule.hudTag(entry) + " " + value
                + ("CRITICAL".equals(entry.name()) ? " - regional activity clue" : " - " + reason), 0xFFFFC857));
        }
        return rows;
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int width = font == null ? 240 : font.width(TITLE) + 8;
        if (font != null) for (var row : rows()) width = Math.max(width, font.width(row.text()) + 8);
        return width;
    }

    @Override public int height() { return 8 + (rows().size() + 1) * 10; }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int x, int y, float alpha) {
        if (Minecraft.getInstance().level == null || alpha <= 0F) return;
        List<HudPanel.Row> rows = rows();
        if (!rows.isEmpty()) HudPanel.render(g, font, x, y, width(), TITLE, rows, 0xFFFFC857);
    }
}
