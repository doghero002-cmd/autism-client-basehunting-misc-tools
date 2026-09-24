package com.autism.seedcracker.texturecrack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.commands.TextureCrackCommand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Corner-picker GUI for the texture cracker: shows the screenshot, the user zooms (scroll) /
 * pans (right-drag) and clicks the four corners of the floor region (TL - TR - BR - BL), then
 * hits "Read grid" to template-match every cell and "Solve" to crack coordinates.
 *
 * Corners are stored in IMAGE pixel space so zooming never moves them; after all four are set,
 * left-dragging near a corner nudges it. The projected cell grid is drawn live through the same
 * homography the reader samples with, so what you see is exactly what gets matched.
 */
public final class TextureCrackImageScreen extends Screen {

    private static final String[] CORNER_LABELS = {"TL", "TR", "BR", "BL"};
    private static final int[] CORNER_COLORS = {0xFF55FF55, 0xFF55FFFF, 0xFFFF9955, 0xFFFF55FF};

    private final Screen parent;
    private final File imageFile;
    private final int rows, cols;

    private BufferedImage image;
    private DynamicTexture texture;
    private Identifier textureId;
    private int imgW, imgH;

    // View transform: imageScreen = viewX + imagePx * viewScale.
    private double viewX, viewY, viewScale = 1.0;
    private boolean panning = false;
    private double panLastX, panLastY;

    /** Image-space corner coords, TL TR BR BL order; count = corners placed so far. */
    private final double[] corners = new double[8];
    private int cornerCount = 0;
    private int dragCorner = -1;

    private EditBox blockField;
    private String status = "Click the TOP-LEFT corner of the floor region";
    private TextureImageReader.Result result;
    private volatile boolean reading = false;

    public TextureCrackImageScreen(Screen parent, File imageFile, String block, int rows, int cols) {
        super(Component.literal("Texture Crack"));
        this.parent = parent;
        this.imageFile = imageFile;
        this.rows = rows;
        this.cols = cols;
        this.initialBlock = block;
    }

    private final String initialBlock;

    @Override
    protected void init() {
        super.init();
        if (image == null) loadImage();

        int panelX = this.width - 150;
        this.blockField = new EditBox(this.font, panelX, 30, 140, 18, Component.literal("Block"));
        this.blockField.setMaxLength(48);
        this.blockField.setValue(initialBlock);
        this.addRenderableWidget(this.blockField);

        this.addRenderableWidget(Button.builder(Component.literal("Read grid"), b -> readGrid())
            .bounds(panelX, 54, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Solve"), b -> solve())
            .bounds(panelX, 78, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Clear corners"), b -> {
            cornerCount = 0;
            result = null;
            status = "Click the TOP-LEFT corner of the floor region";
        }).bounds(panelX, 102, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset view"), b -> fitView())
            .bounds(panelX, 126, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Export GPU"), b -> exportForGpu())
            .bounds(panelX, 150, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("GPU search"), b -> gpuSearch())
            .bounds(panelX, 174, 140, 20).build());
        // CPU load cap for the solver (duty-cycles its scan threads like the bedrock finder).
        this.addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                panelX, 198, 140, 20, Component.empty(),
                (Math.max(10, Math.min(100, TextureCrackEngine.cpuLoadPercent)) - 10) / 90.0) {
            {
                updateMessage();
            }
            private int percent() { return 10 + (int) Math.round(this.value * 90.0); }
            @Override protected void updateMessage() {
                setMessage(Component.literal("CPU load: " + percent() + "%"));
            }
            @Override protected void applyValue() {
                TextureCrackEngine.cpuLoadPercent = percent();
            }
        });
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(panelX, 222, 140, 20).build());
    }

    /** In-game GPU crack: searches the read grid around the player (no separate jar needed). */
    private void gpuSearch() {
        int[][] grid = TextureCrackCommand.grid;
        if (grid == null) { status = "Read the grid first"; return; }
        if (!RotationGpuEngine.available()) {
            status = "No OpenCL GPU: " + com.autism.seedcracker.bedrock.BedrockGpuEngine.probeFailureReason();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { status = "Must be in a world"; return; }
        if (gpuSearching) { status = "GPU search already running"; return; }
        int cx = (int) Math.floor(mc.player.getX());
        int cz = (int) Math.floor(mc.player.getZ());
        int y = TextureCrackCommand.obsY;
        // Scan the configured +- yRange (matches the CPU solver) so unknown-height shots crack.
        int ySpan = 2 * TextureCrackCommand.yRange + 1;
        int yStart = y - TextureCrackCommand.yRange;
        boolean legacy = TextureCrackCommand.formulaMode == TextureCrackEngine.FORMULA_LEGACY;
        int radius = TextureCrackCommand.radius;
        onClose();
        sendMessage("§a[TexCrack] GPU search r=" + String.format("%,d", radius) + " around " + cx + "," + cz
            + " Y=" + yStart + (ySpan > 1 ? ".." + (yStart + ySpan - 1) : "") + "...");
        gpuSearching = true;
        new Thread(() -> {
            try {
                List<long[]> hits = new ArrayList<>();
                RotationGpuEngine.solve(grid, yStart, ySpan, cx, cz, radius, legacy,
                    m -> {
                        hits.add(new long[]{m.x(), m.z()});
                        Minecraft.getInstance().execute(() ->
                            sendMessage("  §e-> Match X: " + m.x() + " Y: " + m.y() + " Z: " + m.z() + " (rot " + m.orientation() * 90 + "°)"));
                    });
                TextureCrackCommand.lastMatches = List.copyOf(hits);
                Minecraft.getInstance().execute(() ->
                    sendMessage(hits.isEmpty()
                        ? "§c[TexCrack] GPU: no matches."
                        : "§a[TexCrack] GPU done: " + hits.size() + " match(es)."));
            } catch (Throwable t) {
                Minecraft.getInstance().execute(() ->
                    sendMessage("§c[TexCrack] GPU failed: " + t.getMessage() + " - use Solve (CPU)."));
            } finally {
                gpuSearching = false;
            }
        }, "TexCrack-GPU").start();
    }

    private static volatile boolean gpuSearching = false;

    /** Writes the read grid as a rotation-pattern file + prints the full-world GPU command. */
    private void exportForGpu() {
        int[][] grid = TextureCrackCommand.grid;
        if (grid == null) { status = "Read the grid first (or set one manually)"; return; }
        int rows = grid.length;
        int cols = 0;
        for (int[] r : grid) cols = Math.max(cols, r.length);
        int known = 0;
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                int v = c < grid[r].length ? grid[r][c] : -1;
                if (v < 0) sb.append('.');
                else { sb.append((char) ('0' + (v & 3))); known++; }
            }
            lines.add(sb.toString());
        }
        if (known == 0) { status = "Grid has no known cells"; return; }
        try {
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath().resolve("rotation-pattern.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, lines);
            sendMessage("§a[TexCrack] Grid exported (" + known + " cells) to §f" + f.getFileName());
            sendMessage("§7Run: §fjava -jar bedrock-gpu-cracker-1.1.0.jar --mode rotation --pattern \""
                + f + "\" --y " + TextureCrackCommand.obsY + " --full-world --yes");
            sendMessage("§7(or double-click the jar, pick Rotation mode, and paint/import it)");
            status = "Exported " + known + " cells";
        } catch (Exception e) {
            status = "Export failed: " + e.getMessage();
        }
    }

    private static void sendMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    private void loadImage() {
        try {
            image = ImageIO.read(imageFile);
            if (image == null) { status = "Unreadable image"; return; }
            imgW = image.getWidth();
            imgH = image.getHeight();
            com.mojang.blaze3d.platform.NativeImage nat;
            try (java.io.InputStream in = new java.io.FileInputStream(imageFile)) {
                nat = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            texture = new DynamicTexture(() -> "texcrack_image", nat);
            textureId = Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "texcrack_image");
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            fitView();
        } catch (Throwable t) {
            status = "Load failed: " + t.getMessage();
            image = null;
        }
    }

    /** Fits the whole image into the canvas area (left of the side panel). */
    private void fitView() {
        if (image == null) return;
        int availW = this.width - 160;
        int availH = this.height - 20;
        viewScale = Math.min((double) availW / imgW, (double) availH / imgH);
        viewX = (availW - imgW * viewScale) / 2.0;
        viewY = 10 + (availH - imgH * viewScale) / 2.0;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    /** Fires on ANY screen swap (Esc, disconnect, another mod opening a screen) - not just Close. */
    @Override
    public void removed() {
        super.removed();
        if (texture != null) {
            try { texture.close(); } catch (Throwable ignored) {}
            texture = null;
            textureId = null;
        }
    }

    // ---- actions ----

    private void readGrid() {
        if (image == null) { status = "No image"; return; }
        if (cornerCount < 4) { status = "Place all 4 corners first"; return; }
        if (reading) return;
        reading = true;
        status = "Matching...";
        String block = blockField.getValue().trim().isEmpty() ? "dirt" : blockField.getValue().trim();
        double[] quad = corners.clone();
        new Thread(() -> {
            try {
                TextureImageReader.Result res = TextureImageReader.read(image, block, rows, cols, quad);
                Minecraft.getInstance().execute(() -> {
                    result = res;
                    TextureCrackCommand.grid = res.grid();
                    TextureCrackCommand.weights = res.weights();
                    TextureCrackCommand.blockName = block; // solver matches this block's variant set
                    String iso = res.refSelfSimilarity() > 0.9
                        ? " §e(texture nearly isotropic - weak signal!)" : "";
                    status = res.known() + "/" + (rows * cols) + " cells read" + iso;
                    if (res.known() < 16) status += " §e- 16+ recommended";
                });
            } catch (Throwable t) {
                Minecraft.getInstance().execute(() -> status = "Read failed: " + t.getMessage());
            } finally {
                reading = false;
            }
        }, "TexCrack-Read").start();
    }

    private void solve() {
        if (TextureCrackCommand.grid == null) { status = "Read the grid first"; return; }
        onClose();
        TextureCrackCommand.solve();
    }

    // ---- coordinate transforms ----

    private double toImgX(double screenX) { return (screenX - viewX) / viewScale; }
    private double toImgY(double screenY) { return (screenY - viewY) / viewScale; }
    private int toScrX(double imgX) { return (int) Math.round(viewX + imgX * viewScale); }
    private int toScrY(double imgY) { return (int) Math.round(viewY + imgY * viewScale); }

    private boolean inCanvas(double x, double y) {
        return x < this.width - 160 && (blockField == null || !blockField.isFocused());
    }

    // ---- render ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.fill(0, 0, this.width, this.height, 0xF0101018);

        if (textureId != null && image != null) {
            int dx = toScrX(0), dy = toScrY(0);
            int dw = (int) Math.round(imgW * viewScale), dh = (int) Math.round(imgH * viewScale);
            try {
                ctx.blit(RenderPipelines.GUI_TEXTURED, textureId, dx, dy, 0f, 0f, dw, dh, dw, dh);
            } catch (Throwable ignored) {}
        }

        // Placed corners.
        for (int i = 0; i < cornerCount; i++) {
            int cx = toScrX(corners[i * 2]);
            int cy = toScrY(corners[i * 2 + 1]);
            int col = CORNER_COLORS[i];
            ctx.fill(cx - 3, cy - 1, cx + 4, cy + 2, col);
            ctx.fill(cx - 1, cy - 3, cx + 2, cy + 4, col);
            ctx.text(this.font, Component.literal(CORNER_LABELS[i]), cx + 5, cy - 4, col, false);
        }

        // Live grid preview through the homography (exactly what the reader will sample).
        if (cornerCount == 4) {
            double[] h = TextureImageReader.homography(corners);
            int gcol = 0x80FFFFFF;
            for (int r = 0; r <= rows; r++) {
                drawProjLine(ctx, h, 0, (double) r / rows, 1, (double) r / rows, gcol);
            }
            for (int c = 0; c <= cols; c++) {
                drawProjLine(ctx, h, (double) c / cols, 0, (double) c / cols, 1, gcol);
            }
            // Read result overlay: rotation digit (green) or ? (red) at each projected cell centre.
            if (result != null) {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        double[] p = TextureImageReader.project(h, (c + 0.5) / cols, (r + 0.5) / rows);
                        int sx = toScrX(p[0]), sy = toScrY(p[1]);
                        TextureImageReader.Cell cell = result.cells()[r][c];
                        boolean known = result.grid()[r][c] >= 0;
                        String txt = known
                            ? (result.grid()[r][c] & 3)
                                + ((result.grid()[r][c] & BlockVariantSet.T_MIRROR) != 0 ? "m" : "")
                            : "?";
                        int col = known ? 0xFF55FF55 : 0xFFFF5555;
                        ctx.centeredText(this.font, Component.literal(txt), sx, sy - 4, col);
                        if (known && cell.margin() < 0.06) { // thin margin: flag visually
                            ctx.centeredText(this.font, Component.literal("~"), sx + 7, sy - 4, 0xFFFFFF55);
                        }
                    }
                }
            }
        }

        // Side panel.
        int panelX = this.width - 150;
        ctx.fill(panelX - 10, 0, this.width, this.height, 0xF0181820);
        ctx.text(this.font, Component.literal("Texture Crack"), panelX, 8, 0xFFFFFFFF, false);
        ctx.text(this.font, Component.literal("Block:"), panelX, 20, 0xFFA0A0A0, false);
        ctx.text(this.font, Component.literal(rows + "x" + cols + " grid"), panelX, 176, 0xFFA0A0A0, false);
        ctx.text(this.font, Component.literal("Scroll = zoom"), panelX, 192, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Right-drag = pan"), panelX, 202, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Click corners TL-TR-BR-BL"), panelX, 212, 0xFF808080, false);
        ctx.text(this.font, Component.literal("Drag a corner to nudge"), panelX, 222, 0xFF808080, false);

        // Status line (bottom of the canvas).
        ctx.text(this.font, Component.literal(status), 8, this.height - 12, 0xFFFFFF80, false);
    }

    /** Straight screen-space segment between two projected unit-square points, stepped for perspective. */
    private void drawProjLine(GuiGraphicsExtractor ctx, double[] h, double u0, double v0, double u1, double v1, int color) {
        final int steps = 24;
        int px = 0, py = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double[] p = TextureImageReader.project(h, u0 + (u1 - u0) * t, v0 + (v1 - v0) * t);
            int sx = toScrX(p[0]), sy = toScrY(p[1]);
            if (i > 0) {
                // 1px stepped line: fill the bounding sliver between consecutive points.
                int x0 = Math.min(px, sx), x1 = Math.max(px, sx) + 1;
                int y0 = Math.min(py, sy), y1 = Math.max(py, sy) + 1;
                if (x1 - x0 > y1 - y0) { y1 = y0 + 1; } else { x1 = x0 + 1; }
                ctx.fill(x0, y0, x1, y1, color);
            }
            px = sx;
            py = sy;
        }
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && event.buttonInfo() != null && inCanvas(event.x(), event.y())) {
            int button = event.buttonInfo().button();
            if (button == 1) { // right = pan
                panning = true;
                panLastX = event.x();
                panLastY = event.y();
                return true;
            }
            if (button == 0 && image != null) {
                double ix = toImgX(event.x()), iy = toImgY(event.y());
                if (cornerCount == 4) {
                    // Grab the nearest corner within reach to nudge it.
                    int best = -1;
                    double bestD = 15.0 / Math.max(0.05, viewScale); // 15 screen px in image units
                    for (int i = 0; i < 4; i++) {
                        double d = Math.hypot(corners[i * 2] - ix, corners[i * 2 + 1] - iy);
                        if (d < bestD) { bestD = d; best = i; }
                    }
                    if (best >= 0) {
                        dragCorner = best;
                        return true;
                    }
                } else {
                    corners[cornerCount * 2] = clamp(ix, 0, imgW - 1);
                    corners[cornerCount * 2 + 1] = clamp(iy, 0, imgH - 1);
                    cornerCount++;
                    dragCorner = cornerCount - 1;
                    status = cornerCount < 4
                        ? "Click the " + fullName(cornerCount) + " corner"
                        : "Corners set - adjust by dragging, then Read grid";
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static String fullName(int i) {
        return switch (i) {
            case 1 -> "TOP-RIGHT";
            case 2 -> "BOTTOM-RIGHT";
            default -> "BOTTOM-LEFT";
        };
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event != null) {
            if (panning) {
                viewX += event.x() - panLastX;
                viewY += event.y() - panLastY;
                panLastX = event.x();
                panLastY = event.y();
                return true;
            }
            if (dragCorner >= 0 && image != null) {
                corners[dragCorner * 2] = clamp(toImgX(event.x()), 0, imgW - 1);
                corners[dragCorner * 2 + 1] = clamp(toImgY(event.y()), 0, imgH - 1);
                result = null; // moved corners invalidate the read
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panning = false;
        dragCorner = -1;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inCanvas(mouseX, mouseY) && image != null) {
            double factor = scrollY > 0 ? 1.25 : 0.8;
            double newScale = clamp(viewScale * factor, 0.02, 40.0);
            // Zoom about the cursor: keep the image point under the mouse fixed.
            viewX = mouseX - (mouseX - viewX) * (newScale / viewScale);
            viewY = mouseY - (mouseY - viewY) * (newScale / viewScale);
            viewScale = newScale;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
