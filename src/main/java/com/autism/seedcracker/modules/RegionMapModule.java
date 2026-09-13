package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.hud.RegionMapHud;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Region Map (module).
 *
 * A DonutSMP-style interactive region map drawn as a screen overlay: a coordinate-mapped
 * canvas where the background is tinted per server cluster, your logged RTP/teleport landings
 * are plotted as colour-coded dots (one colour per cluster), your current position is marked,
 * and hovering shows the world coordinates under the cursor. Axis labels (+X/+Z/-X/-Z) and a
 * colour legend are drawn around the canvas. A dimension switcher lets you view the Overworld
 * or Nether map (Nether coords are mapped 1:8 onto the Overworld grid).
 *
 * Toggleable module under Dogs Misc Tools (shows in module settings).
 */
public final class RegionMapModule extends Module {

    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "region_map");

    public enum Dimension { OVERWORLD, NETHER }

    private final EnumSetting<Dimension> dimension = add(new EnumSetting<>(
            "dimension", "Dimension", Dimension.OVERWORLD, Dimension.values())
        .description("Which dimension's map to show. Nether coords are mapped 1:8 to the Overworld grid.")
        .group("Grid"));
    private final IntSetting x = add(new IntSetting("x", "X", 6, 0, 4000, 1)
        .description("Horizontal screen position.").group("Render"));
    private final IntSetting y = add(new IntSetting("y", "Y", 40, 0, 4000, 1)
        .description("Vertical screen position.").group("Render"));
    private final IntSetting size = add(new IntSetting("size", "Size", 180, 80, 600, 4)
        .description("Canvas size (square, in pixels).").group("Render"));
    private final IntSetting cellBlocks = add(new IntSetting("cell-blocks", "Blocks per region", 50000, 500, 100000, 500)
        .description("World blocks covered by one region cell (9 cells span the map).")
        .group("Grid"));
    private final BoolSetting showDots = add(new BoolSetting("show-dots", "RTP dots", true)
        .description("Plot your teleport landings as colour-coded dots.").group("RTP Log"));
    private final BoolSetting showHover = add(new BoolSetting("show-hover", "Hover coords", true)
        .description("Show world coordinates under the cursor.").group("Render"));
    private final BoolSetting showAxes = add(new BoolSetting("show-axes", "Axis labels", true)
        .description("Show +X/+Z/-X/-Z labels around the canvas.").group("Render"));
    private final BoolSetting showLegend = add(new BoolSetting("show-legend", "Legend", true)
        .description("Show the cluster colour legend.").group("Render"));
    private final IntSetting teleportThreshold = add(new IntSetting("teleport-threshold", "Teleport threshold", 1000, 100, 100000, 100)
        .description("Minimum horizontal distance (blocks) that counts as a teleport to log.")
        .group("RTP Log"));
    private final IntSetting maxDots = add(new IntSetting("max-dots", "Max dots", 2000, 100, 10000, 100)
        .description("Maximum number of RTP dots kept (oldest are dropped).")
        .group("RTP Log"));

    private static RegionMapModule instance;

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

    private static final int GRID = 9;

    /** A logged landing point, in the map's coordinate space (Overworld-equivalent blocks). */
    private record Dot(float fx, float fz, RegionMapHud.Cluster cluster) {}
    private final List<Dot> dots = new ArrayList<>();

    public RegionMapModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":region-map", "Region Map", category,
            "Interactive DonutSMP region map with colour-coded RTP dots, hover coords and dimensions.");
    }

    @Override
    public void onEnable() {
        instance = this;
        registerLayer();
        lastX = Double.NaN;
        lastZ = Double.NaN;
    }

    @Override
    public void onDisable() {
        instance = null;
        HudElementRegistry.removeElement(LAYER_ID);
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    private static boolean layerRegistered = false;

    private void registerLayer() {
        if (layerRegistered) return;
        layerRegistered = true;
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, LAYER_ID,
            (GuiGraphicsExtractor ctx, DeltaTracker delta) -> {
                RegionMapModule m = instance;
                if (m != null && m.isEnabled()) m.render(ctx);
            });
    }

    /** World half-extent of the whole map in Overworld blocks. */
    private long mapHalf() {
        return (long) GRID * cellBlocks.get() / 2L;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!showDots.get()) { lastX = mc.player.getX(); lastZ = mc.player.getZ(); return; }

        // Convert the player's position into Overworld-equivalent coords for the shared map.
        boolean nether = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        double wx = nether ? px * 8.0 : px;
        double wz = nether ? pz * 8.0 : pz;

        if (!Double.isNaN(lastX)) {
            double dx = px - lastX;
            double dz = pz - lastZ;
            double thresh = teleportThreshold.get();
            if (dx * dx + dz * dz >= thresh * thresh) {
                long half = mapHalf();
                float fx = (float) ((wx + half) / (2.0 * half)); // 0..1 across the canvas
                float fz = (float) ((wz + half) / (2.0 * half));
                if (fx >= 0 && fx <= 1 && fz >= 0 && fz <= 1) {
                    int cell = RegionMapHud.cellIndexOf((int) Math.floor(wx), (int) Math.floor(wz), GRID, cellBlocks.get());
                    dots.add(new Dot(fx, fz, RegionMapHud.clusterOf(cell, GRID)));
                    while (dots.size() > maxDots.get()) dots.remove(0);
                }
            }
        }
        lastX = px;
        lastZ = pz;
    }

    private void render(GuiGraphicsExtractor ctx) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int ox = x.get();
        int oy = y.get();
        int sz = size.get();
        long half = mapHalf();

        // Canvas background (dark) + border.
        ctx.fill(ox - 2, oy - 2, ox + sz + 2, oy + sz + 2, 0xE60B0D12);
        ctx.fill(ox, oy, ox + sz, oy + sz, 0xFF14161C);

        // Per-cluster background tint (cell by cell).
        for (int cell = 1; cell <= GRID * GRID; cell++) {
            RegionMapHud.Cluster cluster = RegionMapHud.clusterOf(cell, GRID);
            if (cluster == RegionMapHud.Cluster.NONE) continue;
            int col = (cell - 1) % GRID;
            int row = (cell - 1) / GRID;
            int cx0 = ox + col * sz / GRID;
            int cy0 = oy + row * sz / GRID;
            int cx1 = ox + (col + 1) * sz / GRID;
            int cy1 = oy + (row + 1) * sz / GRID;
            int tint = (cluster.argb & 0x00FFFFFF) | 0x2A000000;
            ctx.fill(cx0, cy0, cx1, cy1, tint);
        }

        // Faint grid lines.
        for (int i = 0; i <= GRID; i++) {
            int gx = ox + i * sz / GRID;
            int gy = oy + i * sz / GRID;
            ctx.fill(gx, oy, gx + 1, oy + sz, 0x22FFFFFF);
            ctx.fill(ox, gy, ox + sz, gy + 1, 0x22FFFFFF);
        }

        // Centre crosshair lines (0,0).
        int centre = ox + sz / 2;
        int centreY = oy + sz / 2;
        ctx.fill(centre, oy, centre + 1, oy + sz, 0x33FFFFFF);
        ctx.fill(ox, centreY, ox + sz, centreY + 1, 0x33FFFFFF);

        // RTP dots (colour-coded by cluster).
        if (showDots.get()) {
            for (Dot d : dots) {
                int dx = ox + (int) (d.fx * sz);
                int dy = oy + (int) (d.fz * sz);
                int color = d.cluster.argb | 0xFF000000;
                ctx.fill(dx - 1, dy - 1, dx + 1, dy + 1, 0xAA000000);
                ctx.fill(dx, dy, dx + 1, dy + 1, color);
            }
        }

        // Player marker (yellow +, converted to the displayed dimension space).
        if (mc.player != null && mc.level != null) {
            boolean netherNow = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
            double wx = netherNow ? mc.player.getX() * 8.0 : mc.player.getX();
            double wz = netherNow ? mc.player.getZ() * 8.0 : mc.player.getZ();
            float fx = (float) ((wx + half) / (2.0 * half));
            float fz = (float) ((wz + half) / (2.0 * half));
            if (fx >= 0 && fx <= 1 && fz >= 0 && fz <= 1) {
                int px = ox + (int) (fx * sz);
                int py = oy + (int) (fz * sz);
                ctx.fill(px - 3, py, px + 4, py + 1, 0xFFFFFF00);
                ctx.fill(px, py - 3, px + 1, py + 4, 0xFFFFFF00);
            }
        }

        // Axis labels.
        if (showAxes.get() && font != null) {
            ctx.text(font, "+X", ox + sz / 2 - 4, oy - 10, 0xFFAAAAAA);
            ctx.text(font, "-X", ox + sz / 2 - 4, oy + sz + 3, 0xFFAAAAAA);
            ctx.text(font, "-Z", ox - 14, oy + sz / 2 - 4, 0xFFAAAAAA);
            ctx.text(font, "+Z", ox + sz + 4, oy + sz / 2 - 4, 0xFFAAAAAA);
        }

        // Dimension label.
        if (font != null) {
            String dim = dimension.get() == Dimension.OVERWORLD ? "Overworld" : "Nether";
            ctx.text(font, dim, ox, oy - 10, 0xFFC8C8D8);
        }

        // Legend.
        if (showLegend.get() && font != null) {
            int ly = oy + sz + (showAxes.get() ? 14 : 4);
            int lx = ox;
            for (RegionMapHud.Cluster c : RegionMapHud.Cluster.values()) {
                if (c == RegionMapHud.Cluster.NONE) continue;
                ctx.fill(lx, ly, lx + 6, ly + 6, c.argb | 0xFF000000);
                ctx.text(font, abbreviate(c.label), lx + 8, ly - 1, 0xFFFFFFFF);
                lx += 8 + font.width(abbreviate(c.label)) + 8;
            }
        }

        // Hover coords.
        if (showHover.get() && font != null) {
            int[] mouse = mousePos(mc);
            int mx = mouse[0], my = mouse[1];
            if (mx >= ox && mx < ox + sz && my >= oy && my < oy + sz) {
                long wx = (long) (((double) (mx - ox) / sz) * 2.0 * half - half);
                long wz = (long) (((double) (my - oy) / sz) * 2.0 * half - half);
                String coords = "X:" + wx + "  Z:" + wz;
                int tw = font.width(coords);
                int tx = Math.min(mx + 8, ox + sz - tw);
                int ty = Math.max(my - 12, oy);
                ctx.fill(tx - 2, ty - 2, tx + tw + 2, ty + 10, 0xC0101018);
                ctx.text(font, coords, tx, ty, 0xFFFFFFFF);
            }
        }
    }

    private static int[] mousePos(Minecraft mc) {
        double sx = mc.mouseHandler.xpos();
        double sy = mc.mouseHandler.ypos();
        double scale = mc.getWindow().getGuiScale();
        return new int[] { (int) (sx / scale), (int) (sy / scale) };
    }

    private static String abbreviate(String name) {
        return switch (name) {
            case "EU Central" -> "EU-C";
            case "EU West" -> "EU-W";
            case "NA East" -> "NA-E";
            case "NA West" -> "NA-W";
            case "Asia" -> "AS";
            case "Oceania" -> "OC";
            default -> "";
        };
    }
}
