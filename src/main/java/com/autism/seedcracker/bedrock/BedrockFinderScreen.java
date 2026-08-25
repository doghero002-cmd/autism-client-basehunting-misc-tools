package com.autism.seedcracker.bedrock;

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
 * Voxel-style grid screen for the Bedrock Finder. The player paints a bedrock pattern
 * (left click = bedrock, right click = not-bedrock, click again to clear), enters the world
 * seed (auto-filled from SeedCracker if known) and a search radius, then runs the search.
 * Matches are reported in chat. A progress bar tracks the search.
 */
public final class BedrockFinderScreen extends Screen {
    public static final int GRID = 16;
    public static int[][] grid = new int[GRID][GRID];
    public static String lastSeed = "";
    public static String lastRadius = "100";

    private final Screen parent;
    private EditBox seedField;
    private EditBox radiusField;
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

    @Override
    protected void init() {
        super.init();
        int px = gridPx();
        this.gridX = (this.width - px) / 2 - 70;
        this.gridY = (this.height - px) / 2;
        int panelX = this.gridX + px + 20;

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

        this.addRenderableWidget(Button.builder(Component.literal("Use Cracked Seed"), b -> useCrackedSeed())
            .bounds(panelX, this.gridY + 96, 160, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Clear Grid"), b -> clearGrid())
            .bounds(panelX, this.gridY + 122, 160, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Search"), b -> runSearch())
            .bounds(panelX, this.gridY + 148, 160, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> BedrockFinderEngine.cancel())
            .bounds(panelX, this.gridY + 174, 160, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(panelX, this.gridY + 200, 160, 20).build());
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

    private void clearGrid() {
        grid = new int[GRID][GRID];
    }

    @Override
    public void onClose() {
        if (this.seedField != null) lastSeed = this.seedField.getValue();
        if (this.radiusField != null) lastRadius = this.radiusField.getValue();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private void runSearch() {
        if (BedrockFinderEngine.isSearching) {
            sendMessage("§e[BedrockFinder] A search is already in progress...");
            return;
        }
        lastSeed = this.seedField.getValue();
        lastRadius = this.radiusField.getValue();

        // Compute the bounding box of marked cells.
        int minR = GRID, maxR = -1, minC = GRID, maxC = -1;
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                if (grid[r][c] == 0) continue;
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                if (c < minC) minC = c;
                if (c > maxC) maxC = c;
            }
        }
        if (maxR == -1) {
            sendMessage("§c[BedrockFinder] Grid is empty! Draw a pattern first.");
            return;
        }

        int rows = maxR - minR + 1;
        int cols = maxC - minC + 1;
        int[][] pattern = new int[cols][rows];
        for (int r = minR; r <= maxR; r++) {
            for (int c = minC; c <= maxC; c++) {
                pattern[c - minC][r - minR] = grid[r][c];
            }
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

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            sendMessage("§c[BedrockFinder] You must be in a world to search.");
            return;
        }
        int centerX = (int) Math.floor(player.getX());
        int centerZ = (int) Math.floor(player.getZ());

        long totalChunks = (long) (2 * radius + 1) * (long) (2 * radius + 1);
        sendMessage(String.format("§a[BedrockFinder] Searching %,d chunks (radius ±%,d, seed %d)...", totalChunks, radius, seed));

        final int fRadius = radius;
        final long fSeed = seed;
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                AtomicInteger count = new AtomicInteger(0);
                List<BedrockFinderEngine.Match> matches = BedrockFinderEngine.findPattern(fSeed, fRadius, centerX, centerZ, pattern, m -> {
                    count.incrementAndGet();
                    mc.execute(() -> sendMessage(String.format("  §e-> Match at X: %d, Z: %d (%s)", m.x, m.z, m.rotation)));
                });
                double secs = (System.currentTimeMillis() - start) / 1000.0;
                mc.execute(() -> {
                    if (matches.isEmpty()) {
                        sendMessage(String.format("§c[BedrockFinder] No matches in %,d chunks (±%,d). (%.2fs)", totalChunks, fRadius, secs));
                    } else {
                        sendMessage(String.format("§a[BedrockFinder] Done! %d match(es) in %.2fs.", matches.size(), secs));
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                mc.execute(() -> sendMessage("§c[BedrockFinder] Error: " + e.getMessage()));
            }
        }, "BedrockFinder-Search").start();
    }

    private static void sendMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int px = gridPx();
        ctx.centeredText(this.font, Component.literal("Bedrock Finder"), this.width / 2, 12, 0xFFFFFFFF);

        int panelX = this.gridX + px + 20;
        ctx.text(this.font, Component.literal("World seed:"), panelX, this.gridY + 6, 0xFFA0A0A0, false);
        ctx.text(this.font, Component.literal("Radius (chunks):"), panelX, this.gridY + 54, 0xFFA0A0A0, false);

        // Grid cells
        for (int col = 0; col < GRID; col++) {
            for (int row = 0; row < GRID; row++) {
                int x = this.gridX + col * cellSize;
                int y = this.gridY + row * cellSize;
                int cell = grid[row][col];
                int fill = cell == 1 ? 0xFF2E7D32 : (cell == 2 ? 0xFFC62828 : 0xFF1A1A24);
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
        ctx.text(this.font, Component.literal("§a■ bedrock  §c■ not bedrock  §7■ unknown"), this.gridX, legendY, 0xFFBBBBBB, false);

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
    }

    private int cellAt(double mx, double my) {
        int px = gridPx();
        if (mx < this.gridX || mx >= this.gridX + px || my < this.gridY || my >= this.gridY + px) return -1;
        int col = (int) ((mx - this.gridX) / cellSize);
        int row = (int) ((my - this.gridY) / cellSize);
        if (col < 0 || col >= GRID || row < 0 || row >= GRID) return -1;
        return row * GRID + col;
    }

    private void paint(int index, int button) {
        int row = index / GRID;
        int col = index % GRID;
        int current = grid[row][col];
        int target = button == 0 ? 1 : (button == 1 ? 2 : 1);
        grid[row][col] = current == target ? 0 : target;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && event.buttonInfo() != null) {
            int index = cellAt(event.x(), event.y());
            if (index >= 0) {
                int button = event.buttonInfo().button();
                int row = index / GRID;
                int col = index % GRID;
                int target = button == 0 ? 1 : (button == 1 ? 2 : 1);
                // Starting a drag: if clicking the same value again, we clear during the drag.
                this.paintValue = grid[row][col] == target ? 0 : target;
                grid[row][col] = this.paintValue;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.paintValue >= 0 && event != null) {
            int index = cellAt(event.x(), event.y());
            if (index >= 0) {
                grid[index / GRID][index % GRID] = this.paintValue;
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.paintValue = -1;
        return super.mouseReleased(event);
    }
}
