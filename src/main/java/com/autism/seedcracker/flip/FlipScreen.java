package com.autism.seedcracker.flip;

import java.util.List;
import java.util.Locale;

import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import com.autism.seedcracker.flip.core.PaperLedger;
import com.autism.seedcracker.gui.AddonScreen;
import com.autism.seedcracker.gui.Palette;
import com.autism.seedcracker.gui.Palette.Tone;
import com.autism.seedcracker.gui.StyledButton;
import com.autism.seedcracker.market.AhGui;
import com.autism.seedcracker.modules.AHFlipperModule;
import com.autism.seedcracker.modules.PriceCheckModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** AH Flipper panel: ranked flips with the "why", plus the paper-trading ledger. */
public final class FlipScreen extends AddonScreen {

    private enum Tab { FLIPS, PAPER }

    private static final int ROW_H = 12;
    private static final int PAD = 10;

    private final Screen parent;
    private Tab tab = Tab.FLIPS;
    private int selected = -1;
    private int scroll;

    public FlipScreen(Screen parent) {
        super(Component.literal("AH Flipper"));
        this.parent = parent;
    }

    private static FlipEngine engine() {
        AHFlipperModule m = AHFlipperModule.INSTANCE;
        return m == null ? null : m.engine();
    }

    private int panelX() { return PAD; }
    private int panelY() { return 34; }
    private int panelW() { return screenWidth() - PAD * 2; }
    private int panelH() { return screenHeight() - panelY() - 36; }
    private int listW() { return Math.max(220, panelW() * 3 / 5); }

    @Override
    protected void init() {
        super.init();
        int y = 8;
        addRenderableWidget(new StyledButton(PAD, y, 70, 18, Component.literal("Flips"), Tone.PRIMARY,
            b -> { tab = Tab.FLIPS; scroll = 0; }));
        addRenderableWidget(new StyledButton(PAD + 76, y, 70, 18, Component.literal("Paper"), Tone.SECONDARY,
            b -> { tab = Tab.PAPER; scroll = 0; }));
        addRenderableWidget(new StyledButton(screenWidth() - PAD - 60, y, 60, 18, Component.literal("Close"),
            Tone.SECONDARY, b -> onClose()));

        int by = screenHeight() - 28;
        addRenderableWidget(new StyledButton(PAD, by, 110, 18, Component.literal("Open /ah for item"),
            Tone.SUCCESS, b -> searchSelected()));
        addRenderableWidget(new StyledButton(PAD + 116, by, 110, 18, Component.literal("Paper-trade it"),
            Tone.NORMAL, b -> paperSelected()));
    }

    private List<Opportunity> flips() {
        FlipEngine e = engine();
        return e == null ? List.of() : e.rank(System.currentTimeMillis());
    }

    private Opportunity selectedFlip() {
        List<Opportunity> f = flips();
        return selected >= 0 && selected < f.size() ? f.get(selected) : null;
    }

    private void searchSelected() {
        Opportunity o = selectedFlip();
        Minecraft mc = Minecraft.getInstance();
        if (o == null || mc.getConnection() == null) { toast("Select a flip first", Palette.WARN); return; }
        mc.gui.setScreen(null);
        mc.getConnection().sendCommand("ah " + AhGui.searchName(o.listing().itemKey()));
    }

    private void paperSelected() {
        Opportunity o = selectedFlip();
        FlipEngine e = engine();
        if (o == null || e == null) { toast("Select a flip first", Palette.WARN); return; }
        boolean ok = e.paper(o, System.currentTimeMillis());
        toast(ok ? "Paper position opened" : "Already held or slots full", ok ? Palette.SUCCESS : Palette.WARN);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float delta) {
        FlipEngine e = engine();
        text(g, "AH Flipper", PAD + 160, 13, Palette.TEXT);
        if (e == null) {
            drawPanel(g, panelX(), panelY(), panelW(), panelH(), Palette.PANEL_BG);
            text(g, "Enable the AH Flipper module first.", panelX() + 8, panelY() + 8, Palette.WARN);
            return;
        }
        String status = String.format(Locale.ROOT, "%d sales / %d markets  |  +%d inferred, +%d API this session%s",
            e.knownSales(), e.knownMarkets(), e.inferredThisSession(), e.apiSalesThisSession(),
            e.benched().isEmpty() ? "" : "  |  benched: " + e.benched().size());
        text(g, status, PAD + 250, 13, Palette.MUTED);
        if (tab == Tab.FLIPS) renderFlips(g, e, mx, my);
        else renderPaper(g, e);
    }

    private void renderFlips(GuiGraphicsExtractor g, FlipEngine e, int mx, int my) {
        int x = panelX(), y = panelY(), w = listW(), h = panelH();
        int muted = Palette.MUTED;
        int body = Palette.TEXT;
        drawPanel(g, x, y, w, h, Palette.PANEL_BG);
        text(g, "Item", x + 6, y + 5, muted);
        text(g, "Buy", x + w - 190, y + 5, muted);
        text(g, "Sell", x + w - 140, y + 5, muted);
        text(g, "Profit", x + w - 90, y + 5, muted);
        text(g, "Conf", x + w - 36, y + 5, muted);

        List<Opportunity> f = flips();
        int rows = Math.max(1, (h - 22) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, f.size() - rows)));
        int ry = y + 18;
        for (int i = scroll; i < Math.min(f.size(), scroll + rows); i++) {
            Opportunity o = f.get(i);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW_H;
            if (i == selected || hover) {
                g.fill(x + 1, ry, x + w - 1, ry + ROW_H, i == selected ? Palette.PANEL_BG_SOFT : Palette.ROW_HOVER);
            }
            int tag = switch (o.basis()) {
                case SALES -> Palette.SUCCESS;
                case INFERRED -> Palette.WARN;
                case ASKS -> muted;
            };
            g.fill(x + 3, ry + 2, x + 6, ry + ROW_H - 2, tag);
            text(g, trim(o.listing().itemKey() + " x" + o.listing().count(), w - 210), x + 10, ry + 2, body);
            text(g, PriceCheckModule.compact(o.buyPrice()), x + w - 190, ry + 2, body);
            text(g, PriceCheckModule.compact(o.sellPrice()), x + w - 140, ry + 2, body);
            text(g, "+" + PriceCheckModule.compact(o.expectedProfit()), x + w - 90, ry + 2, Palette.SUCCESS);
            text(g, Math.round(o.confidence() * 100) + "%", x + w - 36, ry + 2, body);
            ry += ROW_H;
        }
        if (f.isEmpty()) {
            text(g, "No flips yet. Open /ah (it auto-pages) or set a watchlist;", x + 8, y + 22, muted);
            text(g, "two passes a few minutes apart start inferring sales.", x + 8, y + 34, muted);
        }

        int dx = x + w + 8, dw = panelW() - w - 8;
        drawPanel(g, dx, y, dw, h, Palette.PANEL_BG);
        Opportunity sel = selectedFlip();
        if (sel == null) {
            text(g, "Select a flip to see why it was flagged.", dx + 6, y + 6, muted);
            text(g, "Green bar = real sales, yellow = inferred,", dx + 6, y + 22, muted);
            text(g, "grey = asks only (alert, not proof).", dx + 6, y + 34, muted);
            return;
        }
        int ty = y + 6;
        text(g, sel.listing().itemKey() + " x" + sel.listing().count(), dx + 6, ty, body);
        ty += 14;
        text(g, String.format(Locale.ROOT, "Seller %s  |  ROI %.0f%%  |  fill %.0f%%", sel.listing().seller(),
            sel.roiPercent(), sel.saleProbability() * 100), dx + 6, ty, muted);
        ty += 12;
        if (sel.stats().hasPrices()) {
            text(g, String.format(Locale.ROOT, "Bands  p25 %s  quick %s  median %s  p75 %s",
                PriceCheckModule.compact(sel.stats().lowerBound()), PriceCheckModule.compact(sel.stats().quickSalePrice()),
                PriceCheckModule.compact(sel.stats().weightedMedian()), PriceCheckModule.compact(sel.stats().upperBound())),
                dx + 6, ty, muted);
            ty += 14;
        }
        for (String r : sel.reasons()) {
            for (var line : this.font.split(Component.literal("- " + r), dw - 12)) {
                g.text(this.font, line, dx + 6, ty, body, false);
                ty += 10;
                if (ty > y + h - 12) return;
            }
        }
    }

    private void renderPaper(GuiGraphicsExtractor g, FlipEngine e) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        int muted = Palette.MUTED;
        int body = Palette.TEXT;
        drawPanel(g, x, y, w, h, Palette.PANEL_BG);
        PaperLedger.Summary s = e.paperSummary();
        int pnlColor = s.realizedProfit() >= 0 ? Palette.SUCCESS : Palette.ERROR;
        text(g, String.format(Locale.ROOT, "Open %d  |  Sold %d  |  Expired %d  |  Win rate %s", s.open(), s.sold(), s.expired(),
            Double.isNaN(s.winRate()) ? "-" : Math.round(s.winRate() * 100) + "%"), x + 6, y + 6, body);
        text(g, "Realized P/L: " + (s.realizedProfit() >= 0 ? "+" : "-") + PriceCheckModule.compact(Math.abs(s.realizedProfit())),
            x + 6, y + 20, pnlColor);
        text(g, "Paper flips settle only when a LATER sale clears the target. Nothing is bought for real.", x + 6, y + 34, muted);

        List<PaperLedger.Position> ps = e.paperPositions();
        int rows = Math.max(1, (h - 62) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, ps.size() - rows)));
        int ry = y + 52;
        for (int i = ps.size() - 1 - scroll; i >= 0 && ry < y + h - ROW_H; i--) {
            PaperLedger.Position p = ps.get(i);
            int c = switch (p.status) {
                case OPEN -> body;
                case SOLD -> Palette.SUCCESS;
                case EXPIRED -> Palette.ERROR;
            };
            text(g, String.format(Locale.ROOT, "%-7s %s x%d  buy %s  target %s%s", p.status, p.itemKey, p.count,
                PriceCheckModule.compact(p.buyPrice), PriceCheckModule.compact(p.targetPrice),
                p.status == PaperLedger.Status.OPEN ? "" : "  P/L " + Math.round(p.netProfit)), x + 6, ry, c);
            ry += ROW_H;
        }
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, color, false);
    }

    private String trim(String s, int width) {
        return this.font.width(s) <= width ? s : this.font.plainSubstrByWidth(s, width - 6) + "..";
    }

    @Override
    protected boolean onClick(MouseButtonEvent v, boolean doubleClick) {
        if (tab != Tab.FLIPS || v.button() != 0) return false;
        int x = panelX(), w = listW(), top = panelY() + 18;
        if (v.x() < x || v.x() >= x + w || v.y() < top || v.y() >= panelY() + panelH()) return false;
        int idx = scroll + (int) ((v.y() - top) / ROW_H);
        if (idx < 0 || idx >= flips().size()) return false;
        selected = idx;
        return true;
    }

    @Override
    protected boolean onScroll(double mx, double my, double hx, double vy) {
        scroll -= (int) Math.signum(vy) * 3;
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
