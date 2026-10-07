package com.autism.seedcracker.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Voxel-style grid screen for the Bedrock Finder. The player paints bedrock patterns across up
 * to four Y layers (left click = bedrock, right click = not-bedrock, click again to clear),
 * picks floor or nether-roof mode, enters the world seed (auto-filled from SeedCracker if
 * known) and a search radius, then runs the search. Matches are reported in chat.
 *
 * Layer tabs select which layer the grid edits; the other layers' marks ghost through dimmed so
 * columns can be aligned across layers (each extra layer multiplies the match evidence).
 * "Export GPU" writes the pattern file consumed by the standalone bedrock-gpu-cracker tool.
 */
public final class BedrockFinderScreen extends com.autism.seedcracker.gui.AddonScreen {
    public static final int GRID = 16;
    public static final int LAYERS = 4;
    /** [layer][row][col]; floor layer i = Y -60-i, roof layer i = Y 126-i. */
    public static int[][][] grids = new int[LAYERS][GRID][GRID];
    public static int activeLayer = 0;
    public static boolean roofMode = false;
    public static boolean useGpu = false;
    public static String lastSeed = "";
    public static String lastRadius = "100";
    /** Cached device probe (runs once; null = no OpenCL GPU). */
    private static String gpuName;
    private static boolean gpuProbed;

    /** One search hit shown in the in-screen results table. */
    public record ResultRow(long x, long z, String label) {}
    /** Results survive screen reopen (same lifetime as the grids). */
    public static final List<ResultRow> results = java.util.Collections.synchronizedList(new ArrayList<>());
    private int resultsScroll = 0;

    // SWCCS palette (matches the client theme).
    private static final int BG_PANEL = 0xFF1A1A24;
    private static final int BORDER = 0xFF3A3A46;
    private static final int ACCENT = 0xFF3BD7FF;

    private final Screen parent;
    private EditBox seedField;
    private EditBox radiusField;
    private final Button[] layerButtons = new Button[LAYERS];
    private Button roofButton;
    private int gridX;
    private int gridY;
    private final int cellSize = 16;
    private int paintValue = -1; // cell value applied while dragging

    public BedrockFinderScreen(Screen parent) {
        super(Component.literal("Bedrock Finder"));
        this.parent = parent;
    }

    private int gridPx() {
        return GRID * cellSize;
    }

    /** Y level of a layer tab under the current floor/roof mode. */
    public static int layerY(int layer) {
        return roofMode ? 126 - layer : -60 - layer;
    }

    @Override
    protected void init() {
        super.init();
        int px = gridPx();
        this.gridX = (screenWidth() - px) / 2 - 70;
        this.gridY = (screenHeight() - px) / 2;
        int panelX = this.gridX + px + 20;

        // Layer tabs above the grid.
        int tabW = px / LAYERS - 2;
        for (int i = 0; i < LAYERS; i++) {
            final int layer = i;
            layerButtons[i] = button(Component.literal("Y" + layerY(i)), b -> {
                activeLayer = layer;
                refreshLayerButtons();
            }).bounds(this.gridX + i * (tabW + 2), this.gridY - 24, tabW, 20).build();
            this.addRenderableWidget(layerButtons[i]);
        }
        refreshLayerButtons();

        this.seedField = new EditBox(this.font, panelX, this.gridY + 18, 160, 20, Component.literal("Seed"));
        this.seedField.setMaxLength(32);
        this.seedField.setValue(resolvedSeed());
        this.seedField.setResponder(s -> lastSeed = s);
        this.addRenderableWidget(this.seedField);

        this.radiusField = new EditBox(this.font, panelX, this.gridY + 66, 160, 20, Component.literal("Radius (chunks)"));
        this.radiusField.setMaxLength(8);
        this.radiusField.setValue(lastRadius);
        this.radiusField.setResponder(s -> lastRadius = s);
        this.addRenderableWidget(this.radiusField);

        this.roofButton = button(roofLabel(), b -> {
            roofMode = !roofMode;
            b.setMessage(roofLabel());
            refreshLayerButtons();
        }).bounds(panelX, this.gridY + 92, 160, 20).build();
        this.addRenderableWidget(this.roofButton);

        // Engine toggle: probe once off-thread on first open (native load can stutter briefly).
        if (!gpuProbed) {
            gpuProbed = true;
            new Thread(() -> gpuName = BedrockGpuEngine.availability(), "BedrockGpu-Probe").start();
        }
        Button engineButton = button(engineLabel(), b -> {
            if (gpuName == null) return;
            useGpu = !useGpu;
            b.setMessage(engineLabel());
        }).bounds(panelX, this.gridY + 116, 160, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                gpuName != null ? gpuName : "No GPU: " + BedrockGpuEngine.probeFailureReason())))
            .build();
        this.addRenderableWidget(engineButton);

        // Load caps: CPU scales worker threads, GPU duty-cycles tile dispatch (keeps the game responsive).
        this.addRenderableWidget(new LoadSlider(panelX, this.gridY + 140, 160, "CPU load",
            BedrockFinderEngine.cpuLoadPercent, v -> BedrockFinderEngine.cpuLoadPercent = v));
        this.addRenderableWidget(new LoadSlider(panelX, this.gridY + 162, 160, "GPU load",
            BedrockGpuEngine.gpuLoadPercent, v -> BedrockGpuEngine.gpuLoadPercent = v));

        this.addRenderableWidget(button(Component.literal("Use Cracked Seed"), b -> useCrackedSeed())
            .bounds(panelX, this.gridY + 186, 160, 20).build());
        this.addRenderableWidget(button(Component.literal("Clear Layer"), b -> grids[activeLayer] = new int[GRID][GRID])
            .bounds(panelX, this.gridY + 210, 78, 20).build());
        this.addRenderableWidget(button(Component.literal("Clear All"), b -> clearAll())
            .bounds(panelX + 82, this.gridY + 210, 78, 20).build());
        this.addRenderableWidget(button(Component.literal("Search"), b -> runSearch())
            .bounds(panelX, this.gridY + 234, 160, 20).build());
        this.addRenderableWidget(button(Component.literal("Cancel"), b -> BedrockFinderEngine.cancel())
            .bounds(panelX, this.gridY + 258, 78, 20).build());
        this.addRenderableWidget(button(Component.literal("Export GPU"), b -> exportForGpu())
            .bounds(panelX + 82, this.gridY + 258, 78, 20).build());
        this.addRenderableWidget(button(Component.literal("Close"), b -> onClose())
            .bounds(panelX, this.gridY + 282, 160, 20).build());
    }

    /** 10-100% load slider persisting into the engine statics as it drags. */
    private static final class LoadSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
        private final String label;
        private final java.util.function.IntConsumer sink;

        LoadSlider(int x, int y, int w, String label, int initialPercent, java.util.function.IntConsumer sink) {
            super(x, y, w, 20, Component.empty(), (Math.max(10, Math.min(100, initialPercent)) - 10) / 90.0);
            this.label = label;
            this.sink = sink;
            updateMessage();
        }

        private int percent() {
            return 10 + (int) Math.round(this.value * 90.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + percent() + "%"));
        }

        @Override
        protected void applyValue() {
            sink.accept(percent());
        }
    }

    private Component engineLabel() {
        if (gpuName == null) return Component.literal("Engine: CPU (no GPU found)");
        return Component.literal(useGpu ? "Engine: §aGPU" : "Engine: CPU");
    }

    private Component roofLabel() {
        return Component.literal(roofMode ? "Layer: Nether roof" : "Layer: Overworld floor");
    }

    private void refreshLayerButtons() {
        for (int i = 0; i < LAYERS; i++) {
            if (layerButtons[i] == null) continue;
            layerButtons[i].setMessage(Component.literal((i == activeLayer ? "\u00a7a\u25b8 " : "") + "Y" + layerY(i)));
        }
    }

    private void useCrackedSeed() {
        Long seed = SeedSeedProvider.crackedSeed();
        if (seed != null) {
            this.seedField.setValue(String.valueOf(seed));
        } else {
            sendMessage("§c[BedrockFinder] No cracked seed yet. Crack it with the SeedCracker module or type the seed manually.");
        }
    }

    /** Auto-fills from the SeedCracker module's cracked seed when available, else keeps the last value. */
    private static String resolvedSeed() {
        Long cracked = SeedSeedProvider.crackedSeed();
        if (cracked != null) {
            lastSeed = String.valueOf(cracked);
        }
        return lastSeed;
    }

    private void clearAll() {
        grids = new int[LAYERS][GRID][GRID];
    }

    @Override
    public void onClose() {
        if (this.seedField != null) lastSeed = this.seedField.getValue();
        if (this.radiusField != null) lastRadius = this.radiusField.getValue();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    /** Bounding box of marked cells across ALL layers (shared box keeps columns aligned). */
    private int[] sharedBounds() {
        int minR = GRID, maxR = -1, minC = GRID, maxC = -1;
        for (int l = 0; l < LAYERS; l++) {
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) {
                    if (grids[l][r][c] == 0) continue;
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                    if (c < minC) minC = c;
                    if (c > maxC) maxC = c;
                }
            }
        }
        return maxR == -1 ? null : new int[]{minR, maxR, minC, maxC};
    }

    private boolean layerHasMarks(int layer) {
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                if (grids[layer][r][c] != 0) return true;
            }
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean searchThreadActive =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runSearch() {
        // Guard the THREAD, not just the engine flag: isSearching flips false between the GPU
        // fallback and CPU retry, so spam-clicking Search could stack parallel full searches.
        if (BedrockFinderEngine.isSearching || searchThreadActive.get()) {
            sendMessage("§e[BedrockFinder] A search is already in progress...");
            return;
        }
        lastSeed = this.seedField.getValue();
        lastRadius = this.radiusField.getValue();

        int[] b = sharedBounds();
        if (b == null) {
            sendMessage("§c[BedrockFinder] Grid is empty! Draw a pattern first.");
            return;
        }
        int minR = b[0], maxR = b[1], minC = b[2], maxC = b[3];
        int rows = maxR - minR + 1;
        int cols = maxC - minC + 1;

        // Collect non-empty layers into aligned [col][row] patterns.
        List<int[][]> patterns = new ArrayList<>();
        List<Integer> layerYs = new ArrayList<>();
        for (int l = 0; l < LAYERS; l++) {
            if (!layerHasMarks(l)) continue;
            int[][] pattern = new int[cols][rows];
            for (int r = minR; r <= maxR; r++) {
                for (int c = minC; c <= maxC; c++) {
                    pattern[c - minC][r - minR] = grids[l][r][c];
                }
            }
            patterns.add(pattern);
            layerYs.add(layerY(l));
        }

        long seed;
        try {
            seed = Long.parseLong(lastSeed.trim());
        } catch (Exception e) {
            sendMessage("§c[BedrockFinder] Invalid seed. Enter a number or use the cracked seed.");
            return;
        }

        int radius = 100;
        try {
            radius = Integer.parseInt(lastRadius.trim());
        } catch (Exception ignored) {}
        // Hard cap: 20k chunks (=320k blocks) keeps worst-case CPU searches in the minutes
        // range instead of letting a typo'd radius peg the machine for days.
        if (radius > 20000) {
            radius = 20000;
            sendMessage("§e[BedrockFinder] Radius capped at 20,000 chunks. Use the standalone GPU tool for bigger searches.");
        }
        if (radius < 1) radius = 1;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            sendMessage("§c[BedrockFinder] You must be in a world to search.");
            return;
        }
        int centerX = (int) Math.floor(player.getX());
        int centerZ = (int) Math.floor(player.getZ());
        results.clear();
        resultsScroll = 0;

        long totalChunks = (long) (2 * radius + 1) * (long) (2 * radius + 1);
        sendMessage(String.format("§a[BedrockFinder] Searching %,d chunks (radius ±%,d, %d layer%s, seed %d)...",
            totalChunks, radius, patterns.size(), patterns.size() == 1 ? "" : "s", seed));

        final int fRadius = radius;
        final long fSeed = seed;
        final int[][][] fPatterns = patterns.toArray(new int[0][][]);
        final int[] fLayerYs = layerYs.stream().mapToInt(Integer::intValue).toArray();
        final boolean fRoof = roofMode;
        final boolean fGpu = useGpu && gpuName != null;
        if (!searchThreadActive.compareAndSet(false, true)) {
            sendMessage("§e[BedrockFinder] A search is already in progress...");
            return;
        }
        Thread searchThread = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                AtomicInteger count = new AtomicInteger(0);
                java.util.function.Consumer<BedrockFinderEngine.Match> report = m -> {
                    count.incrementAndGet();
                    results.add(new ResultRow(m.x, m.z, String.valueOf(m.rotation)));
                    mc.execute(() -> sendMessage(String.format("  §e-> Match at X: %d, Z: %d (%s)", m.x, m.z, m.rotation)));
                };
                List<BedrockFinderEngine.Match> matches;
                if (fGpu) {
                    try {
                        matches = BedrockGpuEngine.findPatternMulti(
                            fSeed, fRadius, centerX, centerZ, fPatterns, fLayerYs, fRoof, report);
                    } catch (Throwable gpuErr) {
                        mc.execute(() -> sendMessage("§e[BedrockFinder] GPU failed (" + gpuErr.getMessage()
                            + ") - falling back to CPU."));
                        matches = BedrockFinderEngine.findPatternMulti(
                            fSeed, fRadius, centerX, centerZ, fPatterns, fLayerYs, fRoof, report);
                    }
                } else {
                    matches = BedrockFinderEngine.findPatternMulti(
                        fSeed, fRadius, centerX, centerZ, fPatterns, fLayerYs, fRoof, report);
                }
                double secs = (System.currentTimeMillis() - start) / 1000.0;
                final List<BedrockFinderEngine.Match> fMatches = matches;
                mc.execute(() -> {
                    if (fMatches.isEmpty()) {
                        sendMessage(String.format("§c[BedrockFinder] No matches in %,d chunks (±%,d). (%.2fs)", totalChunks, fRadius, secs));
                    } else {
                        sendMessage(String.format("§a[BedrockFinder] Done! %d match(es) in %.2fs.", fMatches.size(), secs));
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                mc.execute(() -> sendMessage("§c[BedrockFinder] Error: " + e.getMessage()));
            } finally {
                searchThreadActive.set(false);
            }
        }, "BedrockFinder-Search");
        searchThread.setDaemon(true); // never block game shutdown on a running search
        searchThread.start();
    }

    /** Writes the pattern file for the standalone GPU tool + prints the command line. */
    private void exportForGpu() {
        int[] b = sharedBounds();
        if (b == null) {
            sendMessage("§c[BedrockFinder] Grid is empty! Draw a pattern first.");
            return;
        }
        int minR = b[0], maxR = b[1], minC = b[2], maxC = b[3];
        try {
            List<String> lines = new ArrayList<>();
            if (roofMode) lines.add("roof");
            for (int l = 0; l < LAYERS; l++) {
                if (!layerHasMarks(l)) continue;
                lines.add("layer " + layerY(l));
                for (int r = minR; r <= maxR; r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = minC; c <= maxC; c++) {
                        int cell = grids[l][r][c];
                        sb.append(cell == 1 ? 'B' : cell == 2 ? 'N' : '.');
                    }
                    lines.add(sb.toString());
                }
            }
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bedrock-pattern.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, lines);
            String seed = this.seedField != null ? this.seedField.getValue().trim() : lastSeed;
            sendMessage("§a[BedrockFinder] Pattern exported to §f" + f);
            sendMessage("§7Run: §fjava -jar bedrock-gpu-cracker.jar --seed " + (seed.isEmpty() ? "<seed>" : seed)
                + " --pattern \"" + f + "\" --radius 100000");
            sendMessage("§7(or just double-click the jar and use Import pattern)");
        } catch (Exception e) {
            sendMessage("§c[BedrockFinder] Export failed: " + e.getMessage());
        }
    }

    private static void sendMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int px = gridPx();
        ctx.centeredText(this.font, Component.literal("Bedrock Finder"), screenWidth() / 2, 12, 0xFFFFFFFF);

        int panelX = this.gridX + px + 20;
        ctx.text(this.font, Component.literal("World seed:"), panelX, this.gridY + 6, 0xFFA0A0A0, false);
        ctx.text(this.font, Component.literal("Radius (chunks):"), panelX, this.gridY + 54, 0xFFA0A0A0, false);

        // Grid cells: active layer full-colour; other layers ghost through dimmed for alignment.
        for (int col = 0; col < GRID; col++) {
            for (int row = 0; row < GRID; row++) {
                int x = this.gridX + col * cellSize;
                int y = this.gridY + row * cellSize;
                int cell = grids[activeLayer][row][col];
                int fill = cell == 1 ? 0xFF2E7D32 : (cell == 2 ? 0xFFC62828 : 0xFF1A1A24);
                if (cell == 0) {
                    for (int l = 0; l < LAYERS; l++) {
                        if (l == activeLayer || grids[l][row][col] == 0) continue;
                        fill = grids[l][row][col] == 1 ? 0xFF16321A : 0xFF321616; // ghost tint
                        break;
                    }
                }
                int border = 0xFF3A3A46;
                if (mouseX >= x && mouseX < x + cellSize && mouseY >= y && mouseY < y + cellSize) {
                    border = 0xFFFFFFFF;
                    if (cell == 0) fill = 0xFF33333F;
                }
                ctx.fill(x + 1, y + 1, x + cellSize - 1, y + cellSize - 1, fill);
                ctx.fill(x, y, x + cellSize, y + 1, border);
                ctx.fill(x, y + cellSize - 1, x + cellSize, y + cellSize, border);
                ctx.fill(x, y, x + 1, y + cellSize, border);
                ctx.fill(x + cellSize - 1, y, x + cellSize, y + cellSize, border);
            }
        }

        // Legend
        int legendY = this.gridY + px + 6;
        ctx.text(this.font, Component.literal("§a■ bedrock  §c■ not bedrock  §7■ unknown  §8■ other layer"),
            this.gridX, legendY, 0xFFBBBBBB, false);

        // Progress bar
        int barY = legendY + 14;
        int barW = px;
        int barH = 14;
        ctx.fill(this.gridX, barY, this.gridX + barW, barY + barH, 0xFF1A1A24);
        int border = 0xFF3A3A46;
        ctx.fill(this.gridX, barY, this.gridX + barW, barY + 1, border);
        ctx.fill(this.gridX, barY + barH - 1, this.gridX + barW, barY + barH, border);
        ctx.fill(this.gridX, barY, this.gridX + 1, barY + barH, border);
        ctx.fill(this.gridX + barW - 1, barY, this.gridX + barW, barY + barH, border);
        float progress = Math.max(0.0f, Math.min(1.0f, BedrockFinderEngine.currentProgress));
        if (progress > 0.0f) {
            int w = (int) ((barW - 2) * progress);
            ctx.fill(this.gridX + 1, barY + 1, this.gridX + 1 + w, barY + barH - 1, 0xFF2E7D32);
        }
        ctx.centeredText(this.font, Component.literal(BedrockFinderEngine.statusText), this.gridX + barW / 2, barY + 3, 0xFFFFFFFF);

        renderResults(ctx, mouseX, mouseY);
    }

    // ---- in-screen results table (replaces chat-only reporting) ----

    private int[] resultsBounds() {
        int px = gridPx();
        int x = this.gridX + px + 20 + 170;      // right of the settings column
        int w = Math.max(150, screenWidth() - x - 12);
        int y = this.gridY - 24;
        int h = px + 24;
        return new int[]{x, y, w, h};
    }

    private int resultRowsVisible(int h) {
        return Math.max(1, (h - 18) / 12);
    }

    private void renderResults(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        int[] rb = resultsBounds();
        int x = rb[0], y = rb[1], w = rb[2], h = rb[3];
        if (w < 120) return; // window too narrow
        ctx.fill(x, y, x + w, y + h, BG_PANEL);
        ctx.fill(x, y, x + w, y + 1, BORDER);
        ctx.fill(x, y + h - 1, x + w, y + h, BORDER);
        ctx.fill(x, y, x + 1, y + h, BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, BORDER);
        java.util.List<ResultRow> snapshot;
        synchronized (results) { snapshot = new ArrayList<>(results); }
        // Nearest-first feels right for basehunting.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            long px0 = (long) mc.player.getX(), pz0 = (long) mc.player.getZ();
            snapshot.sort(java.util.Comparator.comparingLong(r ->
                (r.x - px0) * (r.x - px0) + (r.z - pz0) * (r.z - pz0)));
        }
        ctx.text(this.font, Component.literal("Results (" + snapshot.size() + ") §8click=copy §7right=/tp"),
            x + 6, y + 5, ACCENT, false);
        int rows = resultRowsVisible(h);
        resultsScroll = Math.max(0, Math.min(resultsScroll, Math.max(0, snapshot.size() - rows)));
        int rowY = y + 18;
        for (int i = resultsScroll; i < Math.min(snapshot.size(), resultsScroll + rows); i++) {
            ResultRow r = snapshot.get(i);
            boolean hover = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= rowY && mouseY < rowY + 12;
            if (hover) ctx.fill(x + 1, rowY, x + w - 1, rowY + 12, 0xFF2A2A38);
            String dist = mc.player != null
                ? "  §8" + (int) Math.sqrt(Math.pow(r.x - mc.player.getX(), 2) + Math.pow(r.z - mc.player.getZ(), 2)) + "m"
                : "";
            ctx.text(this.font, Component.literal("§f" + r.x + ", " + r.z + " §7" + r.label + dist),
                x + 6, rowY + 2, 0xFFDDDDDD, false);
            rowY += 12;
        }
        if (snapshot.size() > rows) {
            // slim scrollbar
            int trackH = h - 20;
            int thumbH = Math.max(10, trackH * rows / snapshot.size());
            int thumbY = y + 18 + (trackH - thumbH) * resultsScroll / Math.max(1, snapshot.size() - rows);
            ctx.fill(x + w - 4, y + 18, x + w - 2, y + 18 + trackH, 0xFF2A2A38);
            ctx.fill(x + w - 4, thumbY, x + w - 2, thumbY + thumbH, ACCENT);
        }
    }

    /** Row index under the mouse in the (sorted) results list, or -1. */
    private int resultRowAt(double mx, double my) {
        int[] rb = resultsBounds();
        int x = rb[0], y = rb[1], w = rb[2], h = rb[3];
        if (w < 120 || mx < x || mx >= x + w || my < y + 18 || my >= y + h) return -1;
        int idx = resultsScroll + (int) ((my - (y + 18)) / 12);
        return idx < results.size() ? idx : -1;
    }

    /** Results re-sorted the same way the renderer sorts, so click rows line up. */
    private List<ResultRow> sortedResults() {
        java.util.List<ResultRow> snapshot;
        synchronized (results) { snapshot = new ArrayList<>(results); }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            long px0 = (long) mc.player.getX(), pz0 = (long) mc.player.getZ();
            snapshot.sort(java.util.Comparator.comparingLong(r ->
                (r.x - px0) * (r.x - px0) + (r.z - pz0) * (r.z - pz0)));
        }
        return snapshot;
    }

    @Override
    protected boolean onScroll(double mx, double my, double hx, double vy) {
        int[] rb = resultsBounds();
        if (rb[2] >= 120 && mx >= rb[0] && mx < rb[0] + rb[2] && my >= rb[1] && my < rb[1] + rb[3]) {
            resultsScroll -= (int) Math.signum(vy);
            return true;
        }
        return false;
    }

    private int cellAt(double mx, double my) {
        int px = gridPx();
        if (mx < this.gridX || mx >= this.gridX + px || my < this.gridY || my >= this.gridY + px) return -1;
        int col = (int) ((mx - this.gridX) / cellSize);
        int row = (int) ((my - this.gridY) / cellSize);
        if (col < 0 || col >= GRID || row < 0 || row >= GRID) return -1;
        return row * GRID + col;
    }

    @Override
    protected boolean onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && event.buttonInfo() != null) {
            int rowIdx = resultRowAt(event.x(), event.y());
            if (rowIdx >= 0) {
                List<ResultRow> sorted = sortedResults();
                if (rowIdx < sorted.size()) {
                    ResultRow r = sorted.get(rowIdx);
                    boolean right = event.buttonInfo().button() == 1;
                    String text = right ? "/tp " + r.x + " ~ " + r.z : r.x + " " + r.z;
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    sendMessage("§a[BedrockFinder] Copied: §f" + text);
                }
                return true;
            }
            int index = cellAt(event.x(), event.y());
            if (index >= 0) {
                int button = event.buttonInfo().button();
                int row = index / GRID;
                int col = index % GRID;
                int target = button == 0 ? 1 : (button == 1 ? 2 : 1);
                // Starting a drag: if clicking the same value again, we clear during the drag.
                this.paintValue = grids[activeLayer][row][col] == target ? 0 : target;
                grids[activeLayer][row][col] = this.paintValue;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onDrag(MouseButtonEvent event, double dx, double dy) {
        if (this.paintValue >= 0 && event != null) {
            int index = cellAt(event.x(), event.y());
            if (index >= 0) {
                grids[activeLayer][index / GRID][index % GRID] = this.paintValue;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onRelease(MouseButtonEvent event) {
        this.paintValue = -1;
        return false;
    }
}
