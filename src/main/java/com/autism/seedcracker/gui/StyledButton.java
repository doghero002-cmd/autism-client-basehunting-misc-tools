package com.autism.seedcracker.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat dark button drawn with vanilla GuiGraphicsExtractor calls only (works on every client build). */
public class StyledButton extends Button {

    private final Palette.Tone tone;

    public StyledButton(int x, int y, int w, int h, Component label, Palette.Tone tone, OnPress onPress) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.tone = tone == null ? Palette.Tone.NORMAL : tone;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = active && isHoveredOrFocused();
        g.fill(x, y, x + w, y + h, Palette.toneFill(tone, hot, active));
        g.outline(x, y, w, h, Palette.toneBorder(tone, hot));
        var font = Minecraft.getInstance().font;
        String s = getMessage().getString();
        int max = w - 6;
        if (font.width(s) > max) s = font.plainSubstrByWidth(s, Math.max(0, max - font.width(".."))) + "..";
        int color = active ? Palette.TEXT : Palette.MUTED;
        g.text(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, color, false);
    }
}
