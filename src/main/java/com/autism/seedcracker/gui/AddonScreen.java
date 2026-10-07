package com.autism.seedcracker.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Base for the addon's screens, built only on vanilla Screen + GuiGraphicsExtractor so it loads on
 * every client build (the client's own screen classes are obfuscated/removed in newer releases).
 * Subclasses draw in {@link #renderContent} and handle input in the on* hooks; returning false
 * falls through to the widgets.
 */
public abstract class AddonScreen extends Screen {

    private String toastText;
    private int toastColor;
    private long toastUntil;

    protected AddonScreen(Component title) {
        super(title);
    }

    /** Draw the screen's own content; widgets are drawn on top afterwards. Coordinates are virtual. */
    protected abstract void renderContent(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta);

    protected boolean onClick(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    protected boolean onDrag(MouseButtonEvent event, double dx, double dy) {
        return false;
    }

    protected boolean onRelease(MouseButtonEvent event) {
        return false;
    }

    protected boolean onScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Opaque themed backdrop instead of vanilla's blur/dirt (keeps every screen readable over the world).
        ctx.fill(0, 0, this.width, this.height, Palette.BG);
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        renderContent(ctx, mouseX, mouseY, delta);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        drawToast(ctx);
    }

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return onClick(event, doubleClick) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return onDrag(event, dx, dy) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        return onRelease(event) || super.mouseReleased(event);
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return onScroll(mouseX, mouseY, scrollX, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected int screenWidth() {
        return this.width;
    }

    protected int screenHeight() {
        return this.height;
    }

    /** Kept for subclasses written against the old themed API; colours are fixed now. */
    protected static int theme(int base, Object ignoredChannel) {
        return base;
    }

    protected void panel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        drawPanel(ctx, x, y, w, h, Palette.PANEL_BG);
    }

    protected void drawPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int fill) {
        ctx.fill(x, y, x + w, y + h, fill);
        ctx.outline(x, y, w, h, Palette.BORDER);
    }

    /** Short status line at the bottom-centre that fades after a few seconds. */
    protected void toast(String text, int color) {
        if (text == null || text.isBlank()) return;
        toastText = text;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    private void drawToast(GuiGraphicsExtractor ctx) {
        if (toastText == null || System.currentTimeMillis() > toastUntil) return;
        int w = this.font.width(toastText) + 12;
        int x = (this.width - w) / 2, y = this.height - 46;
        ctx.fill(x, y, x + w, y + 14, 0xE0101014);
        ctx.outline(x, y, w, 14, Palette.BORDER);
        ctx.text(this.font, toastText, x + 6, y + 3, toastColor, false);
    }

    protected static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Drop-in for {@code Button.builder(...)} that produces the addon's flat styled button. */
    protected static StyledBuilder button(Component label, Button.OnPress onPress) {
        return new StyledBuilder(label, onPress);
    }

    public static final class StyledBuilder {
        private final Component label;
        private final Button.OnPress onPress;
        private int x, y, w = 150, h = 20;
        private Palette.Tone tone = Palette.Tone.NORMAL;
        private Tooltip tooltip;

        private StyledBuilder(Component label, Button.OnPress onPress) {
            this.label = label;
            this.onPress = onPress;
        }

        public StyledBuilder bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public StyledBuilder tone(Palette.Tone tone) {
            this.tone = tone;
            return this;
        }

        public StyledBuilder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Button build() {
            StyledButton b = new StyledButton(x, y, w, h, label, tone, onPress);
            if (tooltip != null) b.setTooltip(tooltip);
            return b;
        }
    }
}
