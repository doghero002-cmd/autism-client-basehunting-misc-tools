package com.autism.seedcracker.gui;

/** The addon's screen colours (ARGB). Matches the client's dark theme without linking against its obfuscated UI classes. */
public final class Palette {
    private Palette() {}

    public static final int BG = 0xFF0E0E10;
    public static final int PANEL_BG = 0xE818181B;
    public static final int PANEL_BG_SOFT = 0xB8141417;
    public static final int BORDER = 0xFF6E3A3E;
    public static final int BORDER_ACTIVE = 0xFF3FE87E;
    public static final int TEXT = 0xFFF2F2F2;
    public static final int MUTED = 0xFF9A9A9A;
    public static final int SUCCESS = 0xFF35D873;
    public static final int ERROR = 0xFFFF5B5B;
    public static final int WARN = 0xFFFFC857;
    public static final int ROW_HOVER = 0x22FFFFFF;

    public enum Tone { NORMAL, PRIMARY, SECONDARY, SUCCESS, DANGER }

    static int toneFill(Tone t, boolean hovered, boolean active) {
        if (!active) return 0xFF1C1C21;
        int base = switch (t) {
            case PRIMARY -> 0xFF1F5A3A;
            case SUCCESS -> 0xFF1E6B40;
            case DANGER -> 0xFF6B1E25;
            case SECONDARY -> 0xFF22232A;
            case NORMAL -> 0xFF2A2B33;
        };
        return hovered ? lighten(base, 0.18f) : base;
    }

    static int toneBorder(Tone t, boolean hovered) {
        if (hovered) return BORDER_ACTIVE;
        return switch (t) {
            case PRIMARY, SUCCESS -> 0xFF2F8F57;
            case DANGER -> 0xFFA8323C;
            default -> 0xFF3A3B45;
        };
    }

    static int lighten(int argb, float f) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = Math.min(255, Math.round(r + (255 - r) * f));
        g = Math.min(255, Math.round(g + (255 - g) * f));
        b = Math.min(255, Math.round(b + (255 - b) * f));
        return a << 24 | r << 16 | g << 8 | b;
    }
}
