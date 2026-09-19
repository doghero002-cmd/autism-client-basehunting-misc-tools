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
    private final BoolSetting showHeat = add(new BoolSetting("show-heat", "Base heatmap", true)
        .description("Colour-code regions by how many bases you've found there (green -> red).")
        .group("Heatmap"));
    private final BoolSetting autoHeatmap = add(new BoolSetting("auto-heatmap", "Auto-update heatmap", true)
        .description("ON = finds update the heatmap automatically. OFF = finds queue up and only update when you confirm (via /heatconfirm).")
        .group("Heatmap"));
    private final autismclient.api.module.StringSetting globalHeatUrl = add(new autismclient.api.module.StringSetting(
            "global-heat-url", "Global heatmap URL", "")
        .description("Optional: a URL (e.g. a GitHub raw JSON) with a shared base-find heatmap, fetched + merged into the overlay.")
        .group("Heatmap"));
    private final BoolSetting useCustomImage = add(new BoolSetting("use-custom-image", "Custom map image", false)
        .description("Override the built-in region map with your own PNG (set the path below).")
        .group("Image"));
    private final autismclient.api.module.StringSetting imagePath = add(new autismclient.api.module.StringSetting(
            "image-path", "Image path", "regionmap.png")
        .description("Path to a PNG (relative to the autism client folder, or absolute) used instead of the built-in map.")
        .group("Image")
        .visibleWhen(() -> useCustomImage.get()));

    /** Built-in region-map texture (correct DonutSMP layout, baked into the mod). */
    private static final net.minecraft.resources.Identifier DEFAULT_MAP =
        net.minecraft.resources.Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "textures/gui/region_map.png");

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
        layerRegistered = false; // allow re-registration on next enable
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
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
        Font font = mc.font;
        int ox = x.get();
        int oy = y.get();
        int sz = size.get();
        long half = mapHalf();
        int cs = sz / GRID;

        // Canvas background (dark) + subtle border.
        ctx.fill(ox - 2, oy - 2, ox + sz + 2, oy + sz + 2, 0xE60B0D12);
        ctx.fill(ox, oy, ox + sz, oy + sz, 0xFF14161C);

        // Map background: built-in region_map.png by default, or a custom PNG when overridden.
        net.minecraft.resources.Identifier mapTex = resolveMapTexture(mc);
        if (mapTex != null) {
            try {
                ctx.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    mapTex, ox, oy, 0f, 0f, sz, sz, sz, sz);
            } catch (Throwable ignored) {}
        }

        // Base-find heatmap overlay (green -> yellow -> red by find density), on top of the cells.
        if (showHeat.get()) {
            com.autism.seedcracker.finder.BaseHeatTracker.refreshGlobal(globalHeatUrl.get(), cellBlocks.get());
            for (int cell = 1; cell <= GRID * GRID; cell++) {
                float heat = com.autism.seedcracker.finder.BaseHeatTracker.heat(cell);
                if (heat <= 0f) continue;
                int col = (cell - 1) % GRID;
                int row = (cell - 1) / GRID;
                int cx0 = ox + col * cs;
                int cy0 = oy + row * cs;
                int cx1 = (col == GRID - 1) ? ox + sz : ox + (col + 1) * cs;
                int cy1 = (row == GRID - 1) ? oy + sz : oy + (row + 1) * cs;
                ctx.fill(cx0, cy0, cx1, cy1, heatColor(heat));
                if (font != null && cs >= 14) {
                    int finds = com.autism.seedcracker.finder.BaseHeatTracker.finds(cell);
                    if (finds > 0) {
                        String n = String.valueOf(finds);
                        int tw = font.width(n);
                        ctx.text(font, n, cx0 + (cx1 - cx0 - tw) / 2, cy0 + 1, 0xFFFFFFFF);
                    }
                }
            }
        }

        // Crisp 1px separators between cells (clean grid look).
        for (int i = 0; i <= GRID; i++) {
            int gx = ox + i * cs;
            int gy = oy + i * cs;
            ctx.fill(gx, oy, gx + 1, oy + sz, 0x33000000);
            ctx.fill(ox, gy, ox + sz, gy + 1, 0x33000000);
        }
        // Outer border.
        ctx.fill(ox, oy, ox + sz, oy + 1, 0xFF3A3A46);
        ctx.fill(ox, oy + sz - 1, ox + sz, oy + sz, 0xFF3A3A46);
        ctx.fill(ox, oy, ox + 1, oy + sz, 0xFF3A3A46);
        ctx.fill(ox + sz - 1, oy, ox + sz, oy + sz, 0xFF3A3A46);

        // RTP dots (small bright pixels, colour-coded by cluster).
        if (showDots.get()) {
            for (Dot d : dots) {
                int dx = ox + (int) (d.fx * sz);
                int dy = oy + (int) (d.fz * sz);
                int color = d.cluster.argb | 0xFF000000;
                ctx.fill(dx - 1, dy - 1, dx + 1, dy + 1, 0xAA000000);
                ctx.fill(dx, dy, dx + 1, dy + 1, color);
            }
        }

        // Player marker: smooth sub-cell position with a directional indicator (water style).
        if (mc.player != null && mc.level != null) {
            boolean netherNow = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
            double wx = netherNow ? mc.player.getX() * 8.0 : mc.player.getX();
            double wz = netherNow ? mc.player.getZ() * 8.0 : mc.player.getZ();
            int cell = RegionMapHud.cellIndexOf((int) Math.floor(wx), (int) Math.floor(wz), GRID, cellBlocks.get());
            if (cell >= 1) {
                int col = (cell - 1) % GRID;
                int row = (cell - 1) / GRID;
                double[] sub = RegionMapHud.cellPositionOf(wx, wz, cellBlocks.get());
                int px = ox + col * cs + (int) (sub[0] * cs);
                int py = oy + row * cs + (int) (sub[1] * cs);
                // soft glow then the directional arrow
                ctx.fill(px - 3, py - 3, px + 4, py + 4, 0x40FF4D4D);
                drawPlayerArrow(ctx, px, py, mc.player.getYRot());
            }
        }

        // Dimension label + current cell (top-left, like the reference).
        if (font != null) {
            String dim = dimension.get() == Dimension.OVERWORLD ? "Overworld" : "Nether";
            ctx.text(font, dim, ox, oy - 10, 0xFFC8C8D8);
            if (mc.player != null && mc.level != null) {
                boolean netherNow = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
                double wx = netherNow ? mc.player.getX() * 8.0 : mc.player.getX();
                double wz = netherNow ? mc.player.getZ() * 8.0 : mc.player.getZ();
                int cell = RegionMapHud.cellIndexOf((int) Math.floor(wx), (int) Math.floor(wz), GRID, cellBlocks.get());
                String cellTxt = "region " + cell;
                int cw = font.width(cellTxt);
                ctx.text(font, cellTxt, ox + sz - cw, oy - 10, 0xFF8A93A6);
            }
        }

        // Axis labels (small, subtle).
        if (showAxes.get() && font != null) {
            ctx.text(font, "+X", ox + sz / 2 - 4, oy + sz + 3, 0xFFAAAAAA);
        }

        // Legend (centred, swatch + abbreviation). Swatches use the same colours as the baked map.
        if (showLegend.get() && font != null) {
            int totalW = 0;
            for (RegionMapHud.Cluster c : RegionMapHud.Cluster.values()) {
                if (c == RegionMapHud.Cluster.NONE) continue;
                totalW += 6 + 2 + font.width(abbreviate(c.label)) + 8;
            }
            int lx = ox + Math.max(0, (sz - totalW) / 2);
            int ly = oy + sz + (showAxes.get() ? 14 : 5);
            for (RegionMapHud.Cluster c : RegionMapHud.Cluster.values()) {
                if (c == RegionMapHud.Cluster.NONE) continue;
                ctx.fill(lx, ly + 1, lx + 6, ly + 7, legendColor(c));
                ctx.text(font, abbreviate(c.label), lx + 8, ly, 0xFFFFFFFF);
                lx += 8 + font.width(abbreviate(c.label)) + 8;
            }
        }

        // Hover coords tooltip.
        if (showHover.get() && font != null) {
            int[] mouse = mousePos(mc);
            int mx = mouse[0], my = mouse[1];
            if (mx >= ox && mx < ox + sz && my >= oy && my < oy + sz) {
                long wx = (long) (((double) (mx - ox) / sz) * 2.0 * half - half);
                long wz = (long) (((double) (my - oy) / sz) * 2.0 * half - half);
                int cell = RegionMapHud.cellIndexOf((int) wx, (int) wz, GRID, cellBlocks.get());
                String coords = "cell " + cell + "  X:" + wx + "  Z:" + wz;
                int tw = font.width(coords);
                int tx = Math.min(mx + 8, ox + sz - tw);
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
        int color = 0xFFFF4D4D;
        int outline = 0xFF000000;
        int[][] shape = ARROW_SHAPES[dir];
        for (int[] pt : shape) {
            int ax = px + pt[0], ay = py + pt[1];
            ctx.fill(ax - 1, ay - 1, ax + 1, ay + 1, outline);
            ctx.fill(ax, ay, ax + 1, ay + 1, color);
        }
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

    /** Cluster colour matching the baked region_map.png (so the legend swatches match the map). */
    private static int legendColor(RegionMapHud.Cluster c) {
        return switch (c) {
            case EU_CENTRAL -> 0xFFE04F4F;
            case EU_WEST    -> 0xFFEAB52A;
            case NA_EAST    -> 0xFFE85A1E;
            case NA_WEST    -> 0xFF7A3CD6;
            case ASIA       -> 0xFF2C7AD4;
            case OCEANIA    -> 0xFF2EA44F;
            default         -> 0xFF555555;
        };
    }

    /**
     * The cluster owning a 1-81 cell in the baked map layout (matches region_map.png). Used to
     * colour RTP dots so they sit on the right region.
     */
    private static RegionMapHud.Cluster clusterOfCell(int cell) {
        if (cell < 1 || cell > 81) return RegionMapHud.Cluster.NONE;
        return switch (CELL_CLUSTER[cell - 1]) {
            case 0 -> RegionMapHud.Cluster.EU_CENTRAL;
            case 1 -> RegionMapHud.Cluster.EU_WEST;
            case 2 -> RegionMapHud.Cluster.NA_EAST;
            case 3 -> RegionMapHud.Cluster.NA_WEST;
            case 4 -> RegionMapHud.Cluster.ASIA;
            case 5 -> RegionMapHud.Cluster.OCEANIA;
            default -> RegionMapHud.Cluster.NONE;
        };
    }

    // 81 cells (row-major), cluster index per cell matching region_map.png:
    // 0=EU Central(red) 1=EU West(yellow) 2=NA East(orange) 3=NA West(purple) 4=Asia(blue) 5=Oceania(green)
    private static final int[] CELL_CLUSTER = {
        0,1,1,1,2,2,2,2,2,
        0,1,1,1,2,2,2,2,2,
        0,1,1,1,2,2,2,2,2,
        0,0,0,1,2,2,2,2,2,
        3,3,3,3,2,2,2,2,2,
        3,5,5,4,4,4,4,4,2,
        3,5,5,4,4,4,4,4,2,
        4,5,4,4,4,4,4,4,4,
        4,5,5,5,5,5,5,4,4
    };

    // ---- map image ----
    private net.minecraft.resources.Identifier customImageId = null;
    private String loadedImagePath = null;
    private net.minecraft.client.renderer.texture.DynamicTexture customImage = null;

    /**
     * The map texture to draw: the custom PNG when the user enabled + supplied a valid one,
     * otherwise the built-in region_map.png baked into the mod.
     */
    private net.minecraft.resources.Identifier resolveMapTexture(Minecraft mc) {
        if (useCustomImage.get()) {
            String path = imagePath.get() == null ? "" : imagePath.get().trim();
            if (!path.isEmpty()) {
                if (!path.equals(loadedImagePath)) loadCustomImage(mc, path);
                if (customImageId != null) return customImageId;
            }
        }
        return DEFAULT_MAP;
    }

    private void loadCustomImage(Minecraft mc, String path) {
        loadedImagePath = path;
        customImageId = null;
        if (customImage != null) { try { customImage.close(); } catch (Throwable ignored) {} customImage = null; }
        try {
            java.nio.file.Path file = java.nio.file.Path.of(path);
            if (!file.isAbsolute()) {
                file = autismclient.AutismClientAddon.FOLDER.toPath().resolve(path);
            }
            if (!java.nio.file.Files.exists(file)) { loadedImagePath = null; return; }
            com.mojang.blaze3d.platform.NativeImage img;
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                img = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            customImage = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "regionmap_custom", img);
            customImageId = net.minecraft.resources.Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "regionmap_custom");
            mc.getTextureManager().register(customImageId, customImage);
        } catch (Throwable t) {
            customImage = null;
            customImageId = null;
        }
    }
}
