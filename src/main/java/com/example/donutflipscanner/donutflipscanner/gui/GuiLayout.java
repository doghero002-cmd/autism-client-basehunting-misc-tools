package com.example.donutflipscanner.gui;

/** A spacious logical canvas scaled down only when the Minecraft GUI is smaller. */
public record GuiLayout(int screenX, int screenY, float scale) {
    public static final int LOGICAL_WIDTH = 574;
    public static final int LOGICAL_HEIGHT = 350;
    public static final int HEADER_HEIGHT = 38;
    public static final int STATUS_BAR_HEIGHT = 20;
    public static final int SIDEBAR_WIDTH = 104;
    public static final int SCREEN_MARGIN = 8;

    public static GuiLayout fit(int screenWidth, int screenHeight) {
        int availableWidth = Math.max(1, screenWidth - SCREEN_MARGIN * 2);
        int availableHeight = Math.max(1, screenHeight - SCREEN_MARGIN * 2);
        float scale = Math.min(1.0F, Math.min(
                availableWidth / (float) LOGICAL_WIDTH,
                availableHeight / (float) LOGICAL_HEIGHT
        ));
        int renderedWidth = Math.round(LOGICAL_WIDTH * scale);
        int renderedHeight = Math.round(LOGICAL_HEIGHT * scale);
        return new GuiLayout(
                (screenWidth - renderedWidth) / 2,
                (screenHeight - renderedHeight) / 2,
                scale
        );
    }

    public int toLogicalX(double mouseX) {
        return (int) Math.floor((mouseX - screenX) / scale);
    }

    public int toLogicalY(double mouseY) {
        return (int) Math.floor((mouseY - screenY) / scale);
    }
}
