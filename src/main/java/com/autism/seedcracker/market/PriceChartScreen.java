package com.autism.seedcracker.market;

import java.util.List;
import java.util.Locale;

import com.autism.seedcracker.modules.PriceCheckModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Price history chart. Left: searchable list of tracked items (sample counts). Right: unit-price
 * line chart over time with min/median/max stat strip. Data comes from {@link PriceTracker}
 * (built passively by the Price Check module while you browse /ah).
 */
public final class PriceChartScreen extends com.autism.seedcracker.gui.AddonScreen {

    private static final int ACCENT = 0xFF3BD7FF;

    private final Screen parent;
    private EditBox searchField;
    private String selected;
    private int listScroll = 0;

    public PriceChartScreen(Screen parent, String initialItem) {
        super(Component.literal("Price History"));
        this.parent = parent;
        this.selected = initialItem;
    }

    @Override
    protected void init() {
        super.init();
        this.searchField = new EditBox(this.font, 14, 36, 180, 18, Component.literal("Search"));
        this.searchField.setHint(Component.literal("filter items..."));
        this.searchField.setMaxLength(48);
        this.addRenderableWidget(this.searchField);
        this.addRenderableWidget(button(Component.literal("Close"), b -> onClose())
            .bounds(screenWidth() - 74, 10, 60, 20).build());
    }

    private List<java.util.Map.Entry<String, Integer>> filteredItems() {
        String q = searchField == null ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        List<java.util.Map.Entry<String, Integer>> all = PriceTracker.knownItems();
        if (q.isEmpty()) return all;
        return all.stream().filter(e -> e.getKey().contains(q)).toList();
    }

    private int[] listBounds() {
        return new int[]{14, 60, 180, screenHeight() - 74};
    }

    private int[] chartBounds() {
        int x = 206;
        return new int[]{x, 60, screenWidth() - x - 14, screenHeight() - 74};
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.centeredText(this.font, Component.literal("AH Price History"), screenWidth() / 2, 14, 0xFFFFFFFF);

        // Item list.
        int[] lb = listBounds();
        panel(ctx, lb[0], lb[1], lb[2], lb[3]);
        List<java.util.Map.Entry<String, Integer>> items = filteredItems();
        int rows = Math.max(1, (lb[3] - 8) / 12);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, items.size() - rows)));
        int y = lb[1] + 4;
        for (int i = listScroll; i < Math.min(items.size(), listScroll + rows); i++) {
            var e = items.get(i);
            boolean sel = e.getKey().equals(selected);
            boolean hover = mouseX >= lb[0] && mouseX < lb[0] + lb[2] && mouseY >= y && mouseY < y + 12;
            if (sel || hover) ctx.fill(lb[0] + 1, y, lb[0] + lb[2] - 1, y + 12, sel ? 0x663BD7FF : 0x22FFFFFF);
            ctx.text(this.font, Component.literal(e.getKey() + " §8(" + e.getValue() + ")"),
                lb[0] + 5, y + 2, sel ? ACCENT : 0xFFDDDDDD, false);
            y += 12;
        }
        if (items.isEmpty()) {
            ctx.text(this.font, Component.literal("No data yet - browse /ah with"), lb[0] + 5, lb[1] + 6, 0xFF909090, false);
            ctx.text(this.font, Component.literal("Price Check enabled."), lb[0] + 5, lb[1] + 18, 0xFF909090, false);
        }

        // Chart.
        int[] cb = chartBounds();
        panel(ctx, cb[0], cb[1], cb[2], cb[3]);
        if (selected != null) renderChart(ctx, cb[0], cb[1], cb[2], cb[3]);
        else ctx.centeredText(this.font, Component.literal("Select an item"), cb[0] + cb[2] / 2, cb[1] + cb[3] / 2, 0xFF909090);
    }

    private void renderChart(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        List<PriceTracker.Sample> samples = PriceTracker.samples(selected);
        PriceTracker.Stats stats = PriceTracker.stats(selected);
        if (samples.isEmpty() || stats == null) {
            ctx.centeredText(this.font, Component.literal("No samples for " + selected), x + w / 2, y + h / 2, 0xFF909090);
            return;
        }
        // Stat strip (all prices are PER UNIT: a stack's price is divided by its count when recorded).
        ctx.text(this.font, Component.literal("§f" + selected + " §8(per item)  §7min §f" + PriceCheckModule.compact(stats.min())
                + "  §7median §f" + PriceCheckModule.compact(stats.median())
                + "  §7(24h §f" + PriceCheckModule.compact(stats.recentMedian())
                + "§7)  max §f" + PriceCheckModule.compact(stats.max())
                + "  §7n=" + stats.samples()),
            x + 8, y + 6, 0xFFFFFFFF, false);

        // Best-time-to-buy strip along the bottom: one bar per hour of day.
        int stripH = 46;
        renderHourStrip(ctx, x + 8, y + h - stripH - 4, w - 16, stripH, stats.median());

        int plotX = x + 8, plotY = y + 22, plotW = w - 16, plotH = h - 34 - stripH - 6;
        if (plotW < 40 || plotH < 30) return;

        double min = stats.min(), max = stats.max();
        if (max <= min) max = min + 1;

        // Median guide line.
        int medY = plotY + plotH - (int) ((stats.recentMedian() - min) / (max - min) * plotH);
        ctx.fill(plotX, medY, plotX + plotW, medY + 1, 0x8030FF80);
        ctx.text(this.font, Component.literal(PriceCheckModule.compact(stats.recentMedian())),
            plotX + plotW - 44, Math.max(plotY, medY - 10), 0xFF30FF80, false);

        // Price dots + connecting segments over sample index (time-ordered).
        int n = samples.size();
        int prevPx = -1, prevPy = -1;
        for (int i = 0; i < n; i++) {
            PriceTracker.Sample s = samples.get(i);
            int px = plotX + (n == 1 ? plotW / 2 : (int) ((long) i * plotW / (n - 1)));
            int py = plotY + plotH - (int) ((s.unitPrice() - min) / (max - min) * plotH);
            if (prevPx >= 0) drawSegment(ctx, prevPx, prevPy, px, py, 0xFF3BD7FF);
            ctx.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
            prevPx = px;
            prevPy = py;
        }

        // Axis labels.
        ctx.text(this.font, Component.literal(PriceCheckModule.compact(max)), plotX, plotY - 2, 0xFF909090, false);
        ctx.text(this.font, Component.literal(PriceCheckModule.compact(min)), plotX, plotY + plotH - 8, 0xFF909090, false);
    }

    /**
     * 24 bars, one per local hour: bar height = that hour's median price relative to the overall
     * median. Green bars are cheaper-than-usual hours; the cheapest qualifying hour is labelled.
     */
    private void renderHourStrip(GuiGraphicsExtractor ctx, int x, int y, int w, int h, double overallMedian) {
        var hours = PriceTracker.byHour(selected);
        int best = com.autism.seedcracker.util.pure.DealTiming.cheapestHour(hours, 3);
        String label = best < 0
            ? "§7Best time to buy: §8need 3+ samples in an hour"
            : String.format(Locale.ROOT, "§7Best time to buy: §a%02d:00-%02d:00 §7(%.0f%% under median)",
                best, (best + 1) % 24,
                100 * com.autism.seedcracker.util.pure.DealTiming.hourDiscount(hours[best], overallMedian));
        ctx.text(this.font, Component.literal(label), x, y, 0xFFFFFFFF, false);

        double maxMed = 0;
        for (var hs : hours) if (!Double.isNaN(hs.median())) maxMed = Math.max(maxMed, hs.median());
        if (maxMed <= 0) return;
        int barTop = y + 11, barH = h - 21, slot = Math.max(1, w / 24);
        for (int i = 0; i < 24; i++) {
            int bx = x + i * slot;
            var hs = hours[i];
            if (!Double.isNaN(hs.median())) {
                int bh = Math.max(1, (int) (hs.median() / maxMed * barH));
                int color = i == best ? 0xFF30FF80
                    : hs.median() < overallMedian ? 0xFF2E9E5E : 0xFF7A4A4A;
                ctx.fill(bx + 1, barTop + barH - bh, bx + slot - 1, barTop + barH, color);
            } else {
                ctx.fill(bx + 1, barTop + barH - 1, bx + slot - 1, barTop + barH, 0xFF33333F);
            }
            if (i % 6 == 0) {
                ctx.text(this.font, Component.literal(String.format(Locale.ROOT, "%02d", i)),
                    bx, barTop + barH + 1, 0xFF808080, false);
            }
        }
    }

    /** Stepped line segment (fill-based; no direct line primitive on this ctx). */
    private static void drawSegment(GuiGraphicsExtractor ctx, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) { ctx.fill(x0, y0, x0 + 1, y0 + 1, color); return; }
        for (int i = 0; i <= steps; i++) {
            int px = x0 + (x1 - x0) * i / steps;
            int py = y0 + (y1 - y0) * i / steps;
            ctx.fill(px, py, px + 1, py + 1, color);
        }
    }

    @Override
    protected boolean onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && event.buttonInfo() != null && event.buttonInfo().button() == 0) {
            int[] lb = listBounds();
            double mx = event.x(), my = event.y();
            if (mx >= lb[0] && mx < lb[0] + lb[2] && my >= lb[1] + 4 && my < lb[1] + lb[3]) {
                int idx = listScroll + (int) ((my - lb[1] - 4) / 12);
                List<java.util.Map.Entry<String, Integer>> items = filteredItems();
                if (idx >= 0 && idx < items.size()) {
                    selected = items.get(idx).getKey();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean onScroll(double mx, double my, double hx, double vy) {
        int[] lb = listBounds();
        if (mx >= lb[0] && mx < lb[0] + lb[2] && my >= lb[1] && my < lb[1] + lb[3]) {
            listScroll -= (int) Math.signum(vy);
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        PriceTracker.save(true);
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
