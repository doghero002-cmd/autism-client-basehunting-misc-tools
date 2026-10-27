// SPDX-License-Identifier: GPL-3.0-only
// Native HUD adaptation of Spectre SuspectPanel, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.spectre.chunks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import com.autism.seedcracker.compat.HudPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

public final class SuspectPanel {
    private SuspectPanel() {}
    private record Slot(SuspectObjects.Icon icon, int count, SuspectObjects.Category category) {}

    public static void draw(GuiGraphicsExtractor g, SuspectObjects.Focus focus,
                            ToIntFunction<SuspectObjects.Category> colors, boolean limited) {
        var font = Minecraft.getInstance().font;
        var counts = new EnumMap<SuspectObjects.Category, Map<SuspectObjects.Icon, Integer>>(SuspectObjects.Category.class);
        for (var marker : focus.nearby()) marker.icons().forEach((icon, count) ->
            counts.computeIfAbsent(marker.category(), ignored -> new HashMap<>()).merge(icon, count, Integer::sum));
        var slots = new ArrayList<Slot>();
        counts.forEach((category, icons) -> icons.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().label()))
            .forEach(e -> slots.add(new Slot(e.getKey(), e.getValue(), category))));
        int width = 254, shown = Math.min(28, slots.size()), rows = (shown + 6) / 7;
        var details = new ArrayList<HudPanel.Row>();
        details.add(HudPanel.Row.body(font.plainSubstrByWidth(focus.target().label(), width - 8), colors.applyAsInt(focus.target().category())));
        if (!focus.target().detail().isBlank()) for (String detail : focus.target().detail().split("\n"))
            details.add(HudPanel.Row.body(font.plainSubstrByWidth(detail, width - 8), 0xFFD8E5EE));
        details.add(HudPanel.Row.body(String.format(Locale.ROOT, "%.0fm / nearby observed objects", focus.distance()), 0xFFADBCC9));
        int top = 12 + (details.size() + 1) * 10;
        int height = top + rows * 31 + ((counts.size() + 1) / 2) * 14 + 28;
        float scale = Math.min(1.0f, Math.min((g.guiWidth() - 16f) / width, (g.guiHeight() - 16f) / height));
        if (scale <= 0) return;
        float x = Math.max(8, Math.min(g.guiWidth() / 2f + 16, g.guiWidth() - width * scale - 8));
        float y = Math.max(8, Math.min(g.guiHeight() / 2f - height * scale / 2, g.guiHeight() - height * scale - 8));
        var pose = g.pose(); pose.pushMatrix(); pose.translate(x, y); pose.scale(scale, scale);
        try {
            int accent = colors.applyAsInt(focus.target().category()) | 0xFF000000;
            g.fill(0, 0, width, height, 0xEC101418);
            HudPanel.render(g, font, 0, 0, width, "Dogs / Observations", details, accent);
            for (int i = 0; i < shown; i++) {
                Slot slot = slots.get(i); int sx = 11 + (i % 7) * 33, sy = top + (i / 7) * 31;
                g.fill(sx, sy, sx + 30, sy + 28, 0xFF202830);
                g.fill(sx + 3, sy + 26, sx + 27, sy + 28, colors.applyAsInt(slot.category()) | 0xFF000000);
                if (slot.icon().texture() != null) g.blit(slot.icon().texture(), sx + 6, sy + 2, sx + 22, sy + 18, 0, 1, 0, 1);
                else g.item(new ItemStack(slot.icon().item()), sx + 6, sy + 2);
                String count = Integer.toString(slot.count());
                g.text(font, count, sx + 28 - font.width(count), sy + 16, 0xFFFFFFFF, true);
            }
            int n = 0;
            for (var entry : counts.entrySet()) {
                int lx = 10 + (n % 2) * 121, ly = top + rows * 31 + (n++ / 2) * 14;
                int count = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
                g.fill(lx, ly + 2, lx + 4, ly + 8, colors.applyAsInt(entry.getKey()) | 0xFF000000);
                g.text(font, font.plainSubstrByWidth(count + " " + entry.getKey().label, 109), lx + 7, ly, 0xFFE3EBF2, false);
            }
            String footer = limited ? "Partial detail; move closer" : slots.size() > shown ? "+" + (slots.size() - shown) + " item types" : "Blocks, not container contents";
            g.text(font, footer, 8, height - 23, 0xFFB7C7D4, false);
            g.text(font, "Clues are not proof of a base", 8, height - 12, 0xFFB7C7D4, false);
        } finally { pose.popMatrix(); }
    }
}
