package com.autism.seedcracker.modules;

import java.io.File;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.commands.TextureCrackCommand;
import com.autism.seedcracker.texturecrack.TextureCrackEngine;
import com.autism.seedcracker.texturecrack.TextureCrackImageScreen;

import autismclient.api.module.ActionSetting;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Texture Cracker module. Cracks your coordinates from block-texture rotations (dirt/netherrack/
 * stone tops) read off a screenshot. A first-class module counterpart to Bedrock Finder: opens
 * the corner-picker GUI (auto-reads the grid), with the search parameters as module settings,
 * and runs the CPU solver or the in-game GPU cracker from here too.
 *
 * The .texcrack command does everything this does; the module just makes it discoverable in the
 * module menu and keeps the settings in one place. Settings mirror the command's shared state.
 */
public final class TextureCrackerModule extends Module {

    public enum FormulaMode { NEXTINT, LEGACY, AUTO }

    private final IntSetting radius = add(new IntSetting(
            "radius", "Search radius (blocks)", 3000, 16, 1000000, 100)
        .description("Blocks around the search centre to scan.")
        .group("Search"));
    private final IntSetting yLevel = add(new IntSetting(
            "y-level", "Block Y level", 64, -64, 320, 1)
        .description("Y level of the blocks in the screenshot.")
        .group("Search"));
    private final IntSetting yRange = add(new IntSetting(
            "y-range", "Y range", 0, 0, 128, 1)
        .description("Also try Y +- this many levels (0 = exact).")
        .group("Search"));
    private final IntSetting tolerance = add(new IntSetting(
            "tolerance", "Tolerance (cells)", 0, 0, 8, 1)
        .description("Allow up to this many misread cells (weighted). 0 = exact.")
        .group("Search"));
    private final EnumSetting<FormulaMode> formula = add(new EnumSetting<>(
            "formula", "Variant formula", FormulaMode.NEXTINT, FormulaMode.values())
        .description("The SCREENSHOTTER's client variant picker: NEXTINT = modern clients (1.21.2+), LEGACY = old clients, AUTO = try both.")
        .group("Search"));
    private final BoolSetting allOffsets = add(new BoolSetting(
            "all-offsets", "All orientation combos", false)
        .description("Test all 16 orientation x offset combos (paranoia; default couples them - fewer false positives).")
        .group("Search"));

    private final StringSetting imagePath = add(new StringSetting(
            "image-path", "Screenshot path", "latest")
        .description("Screenshot to read ('latest' = your newest screenshot, or a filename/path).")
        .group("Read"));
    private final StringSetting blockName = add(new StringSetting(
            "block-name", "Block", "dirt")
        .description("The block whose texture rotations to read (dirt/netherrack/stone/sand/gravel...).")
        .group("Read"));
    private final IntSetting gridSize = add(new IntSetting(
            "grid-size", "Grid size", 5, 2, 16, 1)
        .description("Rotation grid rows x cols read from the screenshot.")
        .group("Read"));

    private final ActionSetting openGui = add(new ActionSetting(
            "open-gui", "Open corner-picker GUI", this::openScreen)
        .buttonLabel("Open GUI")
        .description("Open the screenshot corner-picker: place 4 corners, Read grid, then solve or GPU search.")
        .group("General"));
    private final ActionSetting solveNow = add(new ActionSetting(
            "solve", "Solve (CPU)", this::doSolve)
        .buttonLabel("Solve")
        .description("Run the CPU solver on the grid you read (or set manually).")
        .group("General"));
    private final ActionSetting status = add(new ActionSetting(
            "status", "Status", this::showStatus)
        .buttonLabel("Status")
        .description("Print the current grid + search settings.")
        .group("General"));

    public TextureCrackerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":texture-cracker", "Texture Cracker", category,
            "Cracks coordinates from block-texture rotations on a screenshot (corner-picker GUI + GPU search).");
    }

    private void applySettings() {
        TextureCrackCommand.radius = radius.get();
        TextureCrackCommand.obsY = yLevel.get();
        TextureCrackCommand.yRange = yRange.get();
        TextureCrackCommand.tolerance = tolerance.get();
        TextureCrackCommand.formulaMode = switch (formula.get()) {
            case LEGACY -> TextureCrackEngine.FORMULA_LEGACY;
            case AUTO -> TextureCrackEngine.FORMULA_AUTO;
            default -> TextureCrackEngine.FORMULA_NEXTINT;
        };
        TextureCrackCommand.allOffsets = allOffsets.get();
        TextureCrackCommand.blockName = blockName.get().trim().isEmpty() ? "dirt" : blockName.get().trim();
    }

    private void openScreen() {
        applySettings();
        Minecraft mc = Minecraft.getInstance();
        String path = imagePath.get().trim();
        File file = resolveImage(path);
        if (file == null || !file.exists()) {
            AutismClientMessaging.sendPrefixed("§cTexture Cracker: image not found" +
                (path.equalsIgnoreCase("latest") ? " - take a screenshot first (F2)." : ": " + path));
            return;
        }
        int size = Math.max(2, Math.min(16, gridSize.get()));
        mc.execute(() -> {
            try {
                mc.gui.setScreen(new TextureCrackImageScreen(mc.gui.screen(), file,
                    TextureCrackCommand.blockName, size, size));
            } catch (Throwable t) {
                AutismClientMessaging.sendPrefixed("§cTexture Cracker: failed to open GUI: " + t.getMessage());
            }
        });
    }

    private void doSolve() {
        applySettings();
        if (TextureCrackCommand.grid == null) {
            AutismClientMessaging.sendPrefixed("§cTexture Cracker: read a grid first (Open GUI -> Read grid), or set one with .texcrack grid.");
            return;
        }
        TextureCrackCommand.solve();
    }

    private void showStatus() {
        applySettings();
        int known = 0;
        if (TextureCrackCommand.grid != null) {
            for (int[] row : TextureCrackCommand.grid) for (int v : row) if (v >= 0) known++;
        }
        AutismClientMessaging.sendPrefixed("§7[TextureCracker] " + TextureCrackEngine.status
            + " | grid " + (TextureCrackCommand.grid == null ? "unset" : known + " known cells")
            + " | Y=" + yLevel.get() + (yRange.get() > 0 ? "+-" + yRange.get() : "")
            + " | r=" + String.format("%,d", radius.get()) + " | tol=" + tolerance.get()
            + " | " + formula.get().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static File resolveImage(String name) {
        Minecraft mc = Minecraft.getInstance();
        File shots = new File(mc.gameDirectory, "screenshots");
        if (name == null || name.isBlank() || name.equalsIgnoreCase("latest")) {
            File[] all = shots.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            if (all == null || all.length == 0) return null;
            File newest = all[0];
            for (File f : all) if (f.lastModified() > newest.lastModified()) newest = f;
            return newest;
        }
        File direct = new File(name);
        if (direct.isAbsolute()) return direct;
        File inShots = new File(shots, name);
        return inShots.exists() ? inShots : direct;
    }

    @Override
    public String info() {
        return TextureCrackEngine.searching ? "searching" : (TextureCrackCommand.grid != null ? "grid set" : "no grid");
    }
}
