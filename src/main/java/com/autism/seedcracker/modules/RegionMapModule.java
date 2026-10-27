// Adapted from Spectre's Region Map; GPL-3.0-only. See SPECTRE-NOTICES.txt.
package com.autism.seedcracker.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.hud.RegionMapHud;
import com.autism.seedcracker.util.pure.RegionMapImageState;
import com.mojang.blaze3d.platform.NativeImage;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
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

    private final IntSetting x = add(new IntSetting("x", "X", 6, 0, 4000, 1)
        .description("Horizontal screen position.").group("Render"));
    private final IntSetting y = add(new IntSetting("y", "Y", 40, 0, 4000, 1)
        .description("Vertical screen position.").group("Render"));
    private final IntSetting size = add(new IntSetting("size", "Size", 180, 80, 600, 4)
        .description("Canvas size (square, in pixels).").group("Render"));
    private final IntSetting cellGap = add(new IntSetting("cell-gap", "Cell gap", 2, 0, 10, 1).group("Render"));
    private final IntSetting playerScale = add(new IntSetting("player-scale", "Player scale (%)", 100, 50, 300, 10).group("Render"));
    private final IntSetting textScale = add(new IntSetting("text-scale", "Region text scale (%)", 100, 10, 200, 10).group("Render"));
    private final IntSetting opacity = add(new IntSetting("opacity", "Opacity (%)", 100, 10, 100, 5).group("Render"));
    private final BoolSetting shadow = add(new BoolSetting("shadow", "Shadow", true).group("Render"));
    private final BoolSetting showRegionIds = add(new BoolSetting("show-region-ids", "Region IDs", true).group("Render"));
    private final BoolSetting showCoordinates = add(new BoolSetting("show-coordinates", "Coordinates", true).group("Render"));
    private final autismclient.api.module.ColorSetting playerColor = add(new autismclient.api.module.ColorSetting(
        "player-indicator", "Player indicator", 0xFFFF4D4D).group("Render"));
    private final BoolSetting showDots = add(new BoolSetting("show-dots", "RTP dots", true)
        .description("Plot your teleport landings as colour-coded dots.").group("RTP Log"));
    private final BoolSetting showHeat = add(new BoolSetting("show-heat", "Base heatmap", true)
        .description("Colour-code regions by how many bases you've found there (green -> red).")
        .group("Heatmap"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal grid size, overlay labels and RTP-log tuning.").group("Render"));
    private final IntSetting cellBlocks = add(new IntSetting("cell-blocks", "Blocks per region", 50000, 500, 100000, 500)
        .description("World blocks covered by one region cell (9 cells span the map).")
        .group("Grid").visibleWhen(showAdvanced::get));
    private final BoolSetting showHover = add(new BoolSetting("show-hover", "Hover coords", true)
        .description("Show world coordinates under the cursor.").group("Render").visibleWhen(showAdvanced::get));
    private final BoolSetting showAxes = add(new BoolSetting("show-axes", "Axis labels", true)
        .description("Show +X/+Z/-X/-Z labels around the canvas.").group("Render").visibleWhen(showAdvanced::get));
    private final BoolSetting showLegend = add(new BoolSetting("show-legend", "Legend", true)
        .description("Show the cluster colour legend.").group("Render").visibleWhen(showAdvanced::get));
    private final IntSetting teleportThreshold = add(new IntSetting("teleport-threshold", "Teleport threshold", 1000, 100, 100000, 100)
        .description("Minimum horizontal distance (blocks) that counts as a teleport to log.")
        .group("RTP Log").visibleWhen(showAdvanced::get));
    private final IntSetting maxDots = add(new IntSetting("max-dots", "Max dots", 2000, 100, 10000, 100)
        .description("Maximum number of RTP dots kept (oldest are dropped).")
        .group("RTP Log").visibleWhen(showAdvanced::get));
    private final BoolSetting autoHeatmap = add(new BoolSetting("auto-heatmap", "Auto-update heatmap", true)
        .description("ON = finds update the heatmap automatically. OFF = finds queue up and only update when you confirm (via /heatconfirm).")
        .group("Heatmap"));
    private final autismclient.api.module.StringSetting globalHeatUrl = add(new autismclient.api.module.StringSetting(
            "global-heat-url", "Global heatmap URL", "")
        .description("Optional: a URL (e.g. a GitHub raw JSON) with a shared base-find heatmap, fetched + merged into the overlay.")
        .group("Heatmap"));
    private final BoolSetting useCustomImage = add(new BoolSetting("use-custom-image", "Custom map image", false)
        .description("Override the built-in map with your PNG. Toggle off/on to reload; unreadable files use the built-in map.")
        .group("Image"));
    private final autismclient.api.module.StringSetting imagePath = add(new autismclient.api.module.StringSetting(
            "image-path", "Image path", "regionmap.png")
        .description("Path to a PNG (relative to the autism client folder, or absolute) used instead of the built-in map.")
        .group("Image")
        .visibleWhen(() -> useCustomImage.get()));

    /** The bundled asset uses the addon's namespace, independent of the host client's pack. */
    private static final Identifier DEFAULT_MAP =
        Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, RegionMapImageState.BUILTIN_TEXTURE);
    private static final Identifier CUSTOM_MAP =
        Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "regionmap_custom");
    private static final org.slf4j.Logger MAP_LOG = org.slf4j.LoggerFactory.getLogger("region-map");

    private static RegionMapModule instance;

    /** True if finds should update the heatmap automatically (vs queue for manual confirm). */
    public static boolean isAutoHeatmap() {
        RegionMapModule m = instance;
        return m == null || m.autoHeatmap.get();
    }

    /** The map's blocks-per-cell setting, so heat finds are bucketed into the correct cell. */
    public static int cellBlocks() {
        RegionMapModule m = instance;
        return m == null ? 50000 : m.cellBlocks.get();
    }

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDim;

    private static final int GRID = 9;

    /** A logged landing point, in the map's coordinate space (Overworld-equivalent blocks). */
    private record Dot(float fx, float fz, RegionMapHud.Cluster cluster) {}
    private final List<Dot> dots = new ArrayList<>();

    public RegionMapModule() {
        super(SeedcrackerAddon.ID + ":region-map", "Region Map",
            "Interactive DonutSMP region map with colour-coded RTP dots, hover coords and dimensions.");
    }

    @Override
    public void onEnable() {
        instance = this;
        resetMapImage();
        registerLayer();
        lastX = Double.NaN;
        lastZ = Double.NaN;
    }

    @Override
    public void onDisable() {
        instance = null;
        resetMapImage();
        HudElementRegistry.removeElement(LAYER_ID);
        layerRegistered = false; // allow re-registration on next enable
    }

    @Override
    public void onGameLeft() {
        // Dots are per-world intel; carrying them into the next server plots garbage.
        dots.clear();
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastDim = null;
        resetMapImage();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private static boolean layerRegistered = false;

    private void registerLayer() {
        if (layerRegistered) return;
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, LAYER_ID,
            (GuiGraphicsExtractor ctx, DeltaTracker delta) -> {
                RegionMapModule m = instance;
                if (m != null && m.isEnabled()) m.render(ctx);
            });
        layerRegistered = true;
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
        // A dimension change is a huge raw-coordinate jump but NOT a teleport - reset tracking
        // so portal travel doesn't log a bogus RTP dot.
        if (lastDim != mc.level.dimension()) {
            lastDim = mc.level.dimension();
            lastX = Double.NaN;
            lastZ = Double.NaN;
        }
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
                if (fx >= 0 && fx < 1 && fz >= 0 && fz < 1) {
                    int cell = RegionMapHud.cellAt(wx, wz, cellBlocks.get());
                    dots.add(new Dot(fx, fz, clusterOfCell(cell)));
                    while (dots.size() > maxDots.get()) dots.remove(0);
                }
            }
        }
        lastX = px;
        lastZ = pz;
    }

    private void render(GuiGraphicsExtractor ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Font font = mc.font;
        int ox = x.get();
        int oy = y.get();
        int sz = size.get();
        long half = mapHalf();
        int cs = sz / GRID;

        if (shadow.get()) ctx.fill(ox, oy, ox + sz + 4, oy + sz + 4, tint(0x70000000));
        ctx.fill(ox - 2, oy - 2, ox + sz + 2, oy + sz + 2, tint(0xE60B0D12));
        ctx.fill(ox, oy, ox + sz, oy + sz, tint(0xFF14161C));

        Identifier mapTex = resolveMapTexture(mc);
        boolean custom = !mapTex.equals(DEFAULT_MAP) && blitMap(ctx, mapTex, ox, oy, sz);
        if (!custom && !mapTex.equals(DEFAULT_MAP)) releaseCustomImage(mc);
        int gap = custom ? 0 : RegionMapHud.effectiveGap(sz, cellGap.get());
        double cellWidth = RegionMapHud.cellWidth(sz, gap);
        if (!custom) {
            for (int cell = 1; cell <= GRID * GRID; cell++) {
                double left = RegionMapHud.cellStart((cell - 1) % GRID, sz, gap);
                double top = RegionMapHud.cellStart((cell - 1) / GRID, sz, gap);
                ctx.fill(ox + (int) Math.round(left), oy + (int) Math.round(top),
                    ox + (int) Math.round(left + cellWidth), oy + (int) Math.round(top + cellWidth),
                    tint(RegionMapHud.layoutClusterOf(cell).argb));
            }
        }

        // Base-find heatmap overlay (green -> yellow -> red by find density), on top of the cells.
        if (showHeat.get()) {
            com.autism.seedcracker.finder.BaseHeatTracker.refreshGlobal(globalHeatUrl.get(), cellBlocks.get());
            for (int cell = 1; cell <= GRID * GRID; cell++) {
                float heat = com.autism.seedcracker.finder.BaseHeatTracker.heat(cell);
                if (heat <= 0f) continue;
                int col = (cell - 1) % GRID;
                int row = (cell - 1) / GRID;
                double left = RegionMapHud.cellStart(col, sz, gap);
                double top = RegionMapHud.cellStart(row, sz, gap);
                int cx0 = ox + (int) Math.round(left);
                int cy0 = oy + (int) Math.round(top);
                int cx1 = ox + (int) Math.round(left + cellWidth);
                int cy1 = oy + (int) Math.round(top + cellWidth);
                ctx.fill(cx0, cy0, cx1, cy1, tint(heatColor(heat)));
                if (font != null && cs >= 14 && !showRegionIds.get()) {
                    int finds = com.autism.seedcracker.finder.BaseHeatTracker.finds(cell);
                    if (finds > 0) {
                        String n = String.valueOf(finds);
                        int tw = font.width(n);
                        ctx.text(font, n, cx0 + (cx1 - cx0 - tw) / 2, cy0 + 1, 0xFFFFFFFF);
                    }
                }
            }
        }

        if (showRegionIds.get() && font != null) {
            for (int cell = 1; cell <= GRID * GRID; cell++) {
                String label = Integer.toString(RegionMapHud.regionIdOf(cell));
                float scale = Math.min(textScale.get() / 100f, (float) (cellWidth - 2) / Math.max(1, font.width(label)));
                if (scale <= 0) continue;
                float left = (float) (ox + RegionMapHud.cellStart((cell - 1) % GRID, sz, gap));
                float top = (float) (oy + RegionMapHud.cellStart((cell - 1) / GRID, sz, gap));
                ctx.pose().pushMatrix();
                try {
                    ctx.pose().translate(left + ((float) cellWidth - font.width(label) * scale) / 2,
                        top + ((float) cellWidth - font.lineHeight * scale) / 2);
                    ctx.pose().scale(scale, scale);
                    ctx.text(font, label, 0, 0, tint(0xFFFFFFFF), shadow.get());
                } finally {
                    ctx.pose().popMatrix();
                }
            }
        }
        // Outer border.
        ctx.fill(ox, oy, ox + sz, oy + 1, tint(0xFF3A3A46));
        ctx.fill(ox, oy + sz - 1, ox + sz, oy + sz, tint(0xFF3A3A46));
        ctx.fill(ox, oy, ox + 1, oy + sz, tint(0xFF3A3A46));
        ctx.fill(ox + sz - 1, oy, ox + sz, oy + sz, tint(0xFF3A3A46));

        // RTP dots (small bright pixels, colour-coded by cluster).
        if (showDots.get()) {
            for (Dot d : dots) {
                double dotX = RegionMapHud.mapPixel(d.fx, sz, gap);
                double dotZ = RegionMapHud.mapPixel(d.fz, sz, gap);
                if (!Double.isFinite(dotX) || !Double.isFinite(dotZ)) continue;
                int dx = ox + (int) dotX;
                int dy = oy + (int) dotZ;
                ctx.fill(dx - 1, dy - 1, dx + 1, dy + 1, tint(0xAA000000));
                ctx.fill(dx, dy, dx + 1, dy + 1, tint(d.cluster.argb));
            }
        }

        // Player marker: smooth sub-cell position with a directional indicator (water style).
        if (mc.player != null && mc.level != null) {
            boolean netherNow = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
            double wx = netherNow ? mc.player.getX() * 8.0 : mc.player.getX();
            double wz = netherNow ? mc.player.getZ() * 8.0 : mc.player.getZ();
            int cell = RegionMapHud.cellAt(wx, wz, cellBlocks.get());
            if (cell >= 1) {
                int px = ox + (int) RegionMapHud.mapPixel((wx + half) / (2 * half), sz, gap);
                int py = oy + (int) RegionMapHud.mapPixel((wz + half) / (2 * half), sz, gap);
                ctx.fill(px - 3, py - 3, px + 4, py + 4, tint(0x40000000 | (playerColor.get() & 0xFFFFFF)));
                drawPlayerArrow(ctx, px, py, mc.player.getYRot());
            }
        }

        // Dimension label + current cell (top-left, like the reference).
        // Label reflects the dimension you're actually in (the old setting was decorative).
        if (font != null) {
            boolean inNether = mc.level != null && mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
            String dim = inNether ? "Nether" : "Overworld";
            ctx.text(font, dim, ox, oy - 10, 0xFFC8C8D8);
            if (mc.player != null && mc.level != null) {
                boolean netherNow = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
                double wx = netherNow ? mc.player.getX() * 8.0 : mc.player.getX();
                double wz = netherNow ? mc.player.getZ() * 8.0 : mc.player.getZ();
                int cell = RegionMapHud.cellAt(wx, wz, cellBlocks.get());
                String cellTxt = cell < 0 ? "Outside map" : RegionMapHud.regionIdOf(cell) + " · "
                    + abbreviate(RegionMapHud.layoutClusterOf(cell).label);
                int cw = font.width(cellTxt);
                ctx.text(font, cellTxt, ox + sz - cw, oy - 10, tint(0xFF8A93A6));
            }
        }

        int infoY = oy + sz + 4;
        if (font != null && showAxes.get()) {
            ctx.text(font, "-Z ↑    +X →    +Z ↓", ox, infoY, tint(0xFFAAAAAA));
            infoY += 12;
        }
        if (font != null && showCoordinates.get() && mc.player != null) {
            ctx.text(font, "X: " + (int) Math.floor(mc.player.getX()) + "  Z: " + (int) Math.floor(mc.player.getZ()),
                ox, infoY, tint(0xFFFFFFFF));
            infoY += 12;
        }
        if (showLegend.get() && font != null) {
            int index = 0;
            for (RegionMapHud.Cluster c : RegionMapHud.Cluster.values()) {
                if (c == RegionMapHud.Cluster.NONE) continue;
                int lx = ox + (index % 3) * Math.max(sz / 3, 48);
                int ly = infoY + (index / 3) * 12;
                ctx.fill(lx, ly + 1, lx + 6, ly + 7, tint(c.argb));
                ctx.text(font, abbreviate(c.label), lx + 8, ly, tint(0xFFFFFFFF));
                index++;
            }
        }

        // Hover coords tooltip.
        if (showHover.get() && font != null) {
            int[] mouse = mousePos(mc);
            int mx = mouse[0], my = mouse[1];
            if (mx >= ox && mx < ox + sz && my >= oy && my < oy + sz) {
                double fx = RegionMapHud.mapFraction(mx - ox, sz, gap);
                double fz = RegionMapHud.mapFraction(my - oy, sz, gap);
                if (!Double.isFinite(fx) || !Double.isFinite(fz)) return;
                long wx = (long) Math.floor(fx * 2.0 * half - half);
                long wz = (long) Math.floor(fz * 2.0 * half - half);
                int cell = RegionMapHud.cellAt(wx, wz, cellBlocks.get());
                String coords = "region " + RegionMapHud.regionIdOf(cell) + "  X:" + wx + "  Z:" + wz;
                int tw = font.width(coords);
                int tx = Math.max(2, Math.min(mx + 8, mc.getWindow().getGuiScaledWidth() - tw - 2));
                int ty = Math.max(my - 12, oy);
                ctx.fill(tx - 2, ty - 2, tx + tw + 2, ty + 10, 0xC0101018);
                ctx.text(font, coords, tx, ty, 0xFFFFFFFF);
            }
        }
    }

    /**
     * Draws a small directional arrow at (px, py) pointing in the player's facing direction.
     * Uses one of 8 precomputed arrow shapes (N, NE, E, SE, S, SW, W, NW) based on yaw.
     */
    private void drawPlayerArrow(GuiGraphicsExtractor ctx, int px, int py, float yaw) {
        // Minecraft yaw: 0=South, 90=West, 180=North, 270=East. Convert to 0..360.
        float y = ((yaw % 360f) + 360f) % 360f;
        // Direction index 0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW.
        int dir = (Math.round((y + 180f) / 45f)) % 8;
        int color = tint(playerColor.get());
        int outline = tint(0xFF000000);
        int[][] shape = ARROW_SHAPES[dir];
        ctx.pose().pushMatrix();
        try {
            ctx.pose().translate((float) px, (float) py);
            float scale = playerScale.get() / 100f;
            ctx.pose().scale(scale, scale);
            for (int[] pt : shape) {
                int ax = pt[0], ay = pt[1];
                ctx.fill(ax - 1, ay - 1, ax + 1, ay + 1, outline);
                ctx.fill(ax, ay, ax + 1, ay + 1, color);
            }
        } finally {
            ctx.pose().popMatrix();
        }
    }

    private int tint(int color) {
        return ((color >>> 24) * opacity.get() / 100 << 24) | (color & 0xFFFFFF);
    }

    // 8 precomputed 5x5 arrow shapes, tip pointing the given direction. 0=N..7=NW.
    private static final int[][][] ARROW_SHAPES = {
        // N (tip up)
        {{0,-3},{-1,-2},{0,-2},{1,-2},{-2,-1},{-1,-1},{0,-1},{1,-1},{2,-1},{0,0},{0,1},{0,2}},
        // NE
        {{3,-3},{2,-2},{3,-2},{1,-1},{2,-1},{3,-1},{0,0},{1,0},{2,0},{-1,1},{0,1},{-2,2},{-1,2}},
        // E (tip right)
        {{3,0},{2,-1},{2,0},{2,1},{1,-2},{1,-1},{1,0},{1,1},{1,2},{0,0},{-1,0},{-2,0}},
        // SE
        {{3,3},{2,2},{3,2},{1,1},{2,1},{3,1},{0,0},{1,0},{2,0},{-1,-1},{0,-1},{-2,-2},{-1,-2}},
        // S (tip down)
        {{0,3},{-1,2},{0,2},{1,2},{-2,1},{-1,1},{0,1},{1,1},{2,1},{0,0},{0,-1},{0,-2}},
        // SW
        {{-3,3},{-3,2},{-2,2},{-3,1},{-2,1},{-1,1},{-2,0},{-1,0},{0,0},{0,-1},{1,-1},{1,-2},{2,-2}},
        // W (tip left)
        {{-3,0},{-2,-1},{-2,0},{-2,1},{-1,-2},{-1,-1},{-1,0},{-1,1},{-1,2},{0,0},{1,0},{2,0}},
        // NW
        {{-3,-3},{-3,-2},{-2,-2},{-3,-1},{-2,-1},{-1,-1},{-2,0},{-1,0},{0,0},{0,1},{1,1},{1,2},{2,2}},
    };

    private static int[] mousePos(Minecraft mc) {
        // mouseHandler coords are window pixels; convert to GUI-scaled screen coords like vanilla
        // screens do (guiScaledWidth/Height, not raw guiScale, so Auto-scale windows line up).
        double sx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getWidth();
        double sy = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight();
        return new int[] { (int) sx, (int) sy };
    }

    /** Heat colour: 0=transparent-green, 0.5=yellow, 1.0=red (alpha ramps with heat). */
    private static int heatColor(float heat) {
        heat = Math.max(0f, Math.min(1f, heat));
        int r, g;
        if (heat < 0.5f) {
            float t = heat * 2f;
            r = (int) (255 * t);
            g = 200;
        } else {
            float t = (heat - 0.5f) * 2f;
            r = 255;
            g = (int) (200 * (1f - t));
        }
        int alpha = 0x30 + (int) (0x60 * heat); // more opaque as it gets hotter
        return (alpha << 24) | (r << 16) | (g << 8);
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

    private static RegionMapHud.Cluster clusterOfCell(int cell) {
        return RegionMapHud.layoutClusterOf(cell);
    }

    // ---- map image ----
    private final RegionMapImageState mapImageState = new RegionMapImageState();
    private Identifier customImageId;

    private boolean blitMap(GuiGraphicsExtractor ctx, Identifier mapTex, int ox, int oy, int sz) {
        try {
            ctx.blit(mapTex, ox, oy, ox + sz, oy + sz, 0f, 1f, 0f, 1f);
            return true;
        } catch (RuntimeException error) {
            MAP_LOG.warn("Could not draw Region Map texture {}", mapTex, error);
            return false;
        }
    }

    /** Custom-image failures remain cached until an explicit selection or lifecycle change. */
    private Identifier resolveMapTexture(Minecraft mc) {
        if (mapImageState.select(useCustomImage.get(), imagePath.get())) {
            releaseCustomImage(mc);
            if (!mapImageState.path().isEmpty()) loadCustomImage(mc, mapImageState.path());
        }
        return customImageId == null ? DEFAULT_MAP : customImageId;
    }

    private void loadCustomImage(Minecraft mc, String path) {
        NativeImage image = null;
        DynamicTexture texture = null;
        try {
            Path file = Path.of(path);
            if (!file.isAbsolute()) file = autismclient.AutismClientAddon.FOLDER.toPath().resolve(file);
            try (var in = Files.newInputStream(file)) {
                image = NativeImage.read(in);
            }
            texture = new DynamicTexture(() -> "regionmap_custom", image);
            image = null; // DynamicTexture now owns the decoded image.
            mc.getTextureManager().register(CUSTOM_MAP, texture);
            customImageId = CUSTOM_MAP;
            texture = null; // TextureManager now owns the registered texture.
        } catch (IOException | RuntimeException error) {
            MAP_LOG.warn("Could not load custom Region Map '{}'; using the built-in map. Toggle Custom map image off/on to retry.", path, error);
        } finally {
            if (texture != null) texture.close();
            if (image != null) image.close();
        }
    }

    private void releaseCustomImage(Minecraft mc) {
        if (customImageId != null) {
            mc.getTextureManager().release(customImageId);
            customImageId = null;
        }
    }

    private void resetMapImage() {
        releaseCustomImage(Minecraft.getInstance());
        mapImageState.reset();
    }
}
