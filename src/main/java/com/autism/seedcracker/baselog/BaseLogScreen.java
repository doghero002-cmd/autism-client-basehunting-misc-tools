package com.autism.seedcracker.baselog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Base Log Browser. Lists every coordinate logged to bases.txt by the RTP stash finder and the
 * Relog Loader. Click an entry to copy its coords; per-row buttons delete an entry. Top bar has
 * a search filter and a sort toggle (newest / nearest). Delete Selected removes the clicked entry.
 */
public final class BaseLogScreen extends Screen {
    private final Screen parent;

    private List<BaseLog.Entry> all = new ArrayList<>();
    private List<BaseLog.Entry> view = new ArrayList<>();

    private EditBox searchField;
    private Button sortButton;

    private boolean sortNearest = false;
    private int selected = -1;     // index into view
    private int scroll = 0;        // first visible row index

    private int listX;
    private int listY;
    private int listW;
    private int rowH = 22;
    private int rowsVisible;

    public BaseLogScreen(Screen parent) {
        super(Component.literal("Base Log Browser"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.all = BaseLog.readAll();

        int margin = 20;
        this.listX = margin;
        this.listY = 84;
        this.listW = this.width - margin * 2 - 96; // leave room for the side button column
        this.rowsVisible = Math.max(1, (this.height - this.listY - 30) / this.rowH);

        int sideX = this.listX + this.listW + 8;
        int sideW = this.width - sideX - margin;

        this.searchField = new EditBox(this.font, this.listX, 40, 220, 20, Component.literal("Search"));
        this.searchField.setMaxLength(64);
        this.searchField.setHint(Component.literal("filter (coord / dim / block)"));
        this.searchField.setResponder(s -> refilter());
        this.addRenderableWidget(this.searchField);

        this.sortButton = Button.builder(Component.literal(sortLabel()), b -> {
            sortNearest = !sortNearest;
            b.setMessage(Component.literal(sortLabel()));
            refilter();
        }).bounds(this.listX + 228, 40, 130, 20).build();
        this.addRenderableWidget(this.sortButton);

        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            this.all = BaseLog.readAll();
            refilter();
        }).bounds(this.listX + 362, 40, 70, 20).build());

        // Side action column.
        int by = this.listY;
        this.addRenderableWidget(Button.builder(Component.literal("Copy Coords"), b -> copySelected())
            .bounds(sideX, by, sideW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Delete Selected"), b -> deleteSelected())
            .bounds(sideX, by + 26, sideW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(sideX, this.height - 44, sideW, 20).build());

        refilter();
    }

    private String sortLabel() {
        return sortNearest ? "Sort: Nearest" : "Sort: Newest";
    }

    private void refilter() {
        String q = this.searchField == null ? "" : this.searchField.getValue().trim().toLowerCase();
        List<BaseLog.Entry> out = new ArrayList<>();
        for (BaseLog.Entry e : this.all) {
            if (!q.isEmpty()) {
                String hay = (e.coord() + " " + e.dimension + " " + e.blockId + " " + e.timestamp).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            out.add(e);
        }
        if (sortNearest) {
            double[] p = BaseLog.playerPos();
            if (p != null) {
                out.sort(Comparator.comparingDouble(e -> e.distanceTo(p[0], p[1])));
            }
        } else {
            // Newest first: file is append-only, so reverse file order.
            java.util.Collections.reverse(out);
        }
        this.view = out;
        if (this.selected >= this.view.size()) this.selected = -1;
        clampScroll();
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, this.view.size() - this.rowsVisible);
        if (this.scroll > maxScroll) this.scroll = maxScroll;
        if (this.scroll < 0) this.scroll = 0;
    }

    private void copySelected() {
        if (this.selected < 0 || this.selected >= this.view.size()) return;
        BaseLog.Entry e = this.view.get(this.selected);
        Minecraft.getInstance().keyboardHandler.setClipboard(e.coord());
        sendMessage("§aCopied coords: §f" + e.coord());
    }

    private void deleteSelected() {
        if (this.selected < 0 || this.selected >= this.view.size()) return;
        BaseLog.Entry e = this.view.get(this.selected);
        int removed = BaseLog.remove(java.util.List.of(e));
        this.all = BaseLog.readAll();
        this.selected = -1;
        refilter();
        sendMessage(removed > 0 ? "§aDeleted base: §f" + e.coord() : "§cNothing deleted.");
    }

    private void deleteEntry(BaseLog.Entry e) {
        BaseLog.remove(java.util.List.of(e));
        this.all = BaseLog.readAll();
        this.selected = -1;
        refilter();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(this.font, Component.literal("Base Log Browser"), this.width / 2, 12, 0xFFFFFFFF);
        ctx.text(this.font, Component.literal(this.view.size() + " base(s)"), this.listX, 24, 0xFFA0A0A0, false);

        double[] p = BaseLog.playerPos();

        // Header row
        int hy = this.listY - 14;
        ctx.text(this.font, Component.literal("Coords"), this.listX + 6, hy, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Dim"), this.listX + 150, hy, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Block"), this.listX + 230, hy, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Dist"), this.listX + 330, hy, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Logged"), this.listX + 410, hy, 0xFF808080, false);

        for (int i = 0; i < this.rowsVisible; i++) {
            int idx = this.scroll + i;
            if (idx >= this.view.size()) break;
            BaseLog.Entry e = this.view.get(idx);
            int ry = this.listY + i * this.rowH;

            boolean hovered = mouseX >= this.listX && mouseX < this.listX + this.listW
                && mouseY >= ry && mouseY < ry + this.rowH;
            int bg = idx == this.selected ? 0xFF35486B : (hovered ? 0xFF26262F : 0xFF1A1A24);
            ctx.fill(this.listX, ry, this.listX + this.listW, ry + this.rowH - 2, bg);

            int textColor = idx == this.selected ? 0xFFFFFFFF : 0xFFDDDDDD;
            ctx.text(this.font, Component.literal(e.coord()), this.listX + 6, ry + 6, textColor, false);
            ctx.text(this.font, Component.literal(BaseLog.shortDim(e.dimension)), this.listX + 150, ry + 6, 0xFF9AD1FF, false);
            ctx.text(this.font, Component.literal(BaseLog.shortBlock(e.blockId)), this.listX + 230, ry + 6, 0xFFB9F6CA, false);
            String dist = p != null ? BaseLog.formatDistance(e.distanceTo(p[0], p[1])) : "-";
            ctx.text(this.font, Component.literal(dist), this.listX + 330, ry + 6, 0xFFFFE082, false);
            ctx.text(this.font, Component.literal(e.timestamp), this.listX + 410, ry + 6, 0xFF909090, false);

            // Per-row delete "x" on the far right of the row.
            int delX = this.listX + this.listW - 18;
            boolean delHover = mouseX >= delX - 2 && mouseX < delX + 12 && mouseY >= ry && mouseY < ry + this.rowH;
            ctx.text(this.font, Component.literal("x"), delX, ry + 6, delHover ? 0xFFFF5555 : 0xFF885555, false);
        }

        // Scrollbar
        if (this.view.size() > this.rowsVisible) {
            int trackX = this.listX + this.listW + 2;
            int trackH = this.rowsVisible * this.rowH;
            ctx.fill(trackX, this.listY, trackX + 3, this.listY + trackH, 0xFF2A2A33);
            float frac = (float) this.scroll / (float) (this.view.size() - this.rowsVisible);
            int thumbH = Math.max(16, trackH * this.rowsVisible / this.view.size());
            int thumbY = this.listY + (int) ((trackH - thumbH) * frac);
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF6A6A80);
        }

        if (this.view.isEmpty()) {
            ctx.centeredText(this.font, Component.literal("No bases logged yet."),
                this.listX + this.listW / 2, this.listY + 20, 0xFF808080);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && event.buttonInfo() != null && event.buttonInfo().button() == 0) {
            double mx = event.x();
            double my = event.y();
            if (mx >= this.listX && mx < this.listX + this.listW && my >= this.listY) {
                int row = (int) ((my - this.listY) / this.rowH);
                int idx = this.scroll + row;
                if (row >= 0 && row < this.rowsVisible && idx < this.view.size()) {
                    // Per-row delete zone (far right of the row).
                    if (mx >= this.listX + this.listW - 20) {
                        deleteEntry(this.view.get(idx));
                    } else {
                        this.selected = idx;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (this.view.size() > this.rowsVisible) {
            this.scroll -= (int) vAmount;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void sendMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
