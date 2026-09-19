package com.autism.seedcracker.hud;

import autismclient.api.hud.HudElementProvider;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Session Stats HUD.
 *
 * A small overlay showing this session's base-hunting stats: bases flagged (from the Base
 * Tracker), the longest streak of old chunks seen, distance travelled, and RTPs taken (large
 * teleports). Tracks values live as you hunt so you can see the session at a glance.
 */
public final class SessionStatsHud implements HudElementProvider {
    private static final int PAD = 4;
    private static final int LINE = 10;
    private static final int BG = 0x90101018;
    private static final int TITLE_COLOR = 0xFF55FFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static long sessionStartMs = 0L;
    private static int basesFound = 0;
    private static int rtpsTaken = 0;
    private static double distanceTravelled = 0.0;
    private static double lastX = Double.NaN, lastZ = Double.NaN;
    private static long lastTickMs = 0L;
    private static final long TICK_MS = 50L; // one game tick

    public SessionStatsHud() {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis();
    }

    /** Called by finders when a base is flagged. */
    public static void onBaseFound() { basesFound++; }
    /** Called when a large teleport (RTP) is detected. */
    public static void onRtp() { rtpsTaken++; }

    public static void tick(Minecraft mc) {
        if (mc.player == null) return;
        // Rate-limit to one accumulation per game tick. render() runs at frame rate (often 100+
        // fps), so calling this unthrottled from render over-counted distance many-fold.
        long now = System.currentTimeMillis();
        if (now - lastTickMs < TICK_MS) return;
        lastTickMs = now;
        double x = mc.player.getX(), z = mc.player.getZ();
        if (!Double.isNaN(lastX)) {
            double d = Math.hypot(x - lastX, z - lastZ);
            // Ignore teleports (RTP/portal jumps) so they don't inflate the travelled distance.
            if (d < 100.0) distanceTravelled += d;
        }
        lastX = x; lastZ = z;
    }

    @Override public String id() { return SeedcrackerAddon.ID + ":session-stats"; }
    @Override public String label() { return "Session Stats"; }
    @Override public String description() { return "Session base-hunting stats (bases, RTPs, distance)."; }

    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 100; }

    @Override public int width() { return 120; }
    @Override public int height() { return PAD * 2 + LINE * 5; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (font == null) return;
        tick(Minecraft.getInstance());
        int w = width(), h = height();
        ctx.fill(x, y, x + w, y + h, BG);
        ctx.text(font, "Session Stats", x + PAD, y + 2, TITLE_COLOR);

        long mins = Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60000L);
        String[] lines = {
            "Time: " + mins + "m",
            "Bases found: " + basesFound,
            "RTPs: " + rtpsTaken,
            "Distance: " + formatDist(distanceTravelled)
        };
        for (int i = 0; i < lines.length; i++) {
            ctx.text(font, lines[i], x + PAD, y + 2 + LINE * (i + 1), TEXT_COLOR);
        }
    }

    private static String formatDist(double blocks) {
        if (blocks >= 1000) return String.format(java.util.Locale.ROOT, "%.1fkm", blocks / 1000.0);
        return (int) blocks + "m";
    }
}
