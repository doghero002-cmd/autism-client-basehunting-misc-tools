package com.autism.seedcracker.commands;

import java.io.File;
import java.util.Locale;

import com.autism.seedcracker.texturecrack.TextureCrackEngine;
import com.autism.seedcracker.texturecrack.TextureCrackImageScreen;
import com.autism.seedcracker.texturecrack.TextureImageReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Texture-rotation coordinate cracker. Rotations of dirt/sand/netherrack/gravel tops are a pure
 * function of block position (seed-independent), so a grid of rotations read off a screenshot
 * pins the world coordinates. Grid entry is manual (.texcrack grid) or automated (.texcrack
 * image -> corner-picker GUI + template matching).
 *
 * Usage:
 *   .texcrack image <file|latest> <block> [RxC]        open the corner-picker GUI on a screenshot
 *   .texcrack grid <rows like "0 2 1 3 / 1 . 0 2">     set the grid manually (. or ? = unknown)
 *   .texcrack y <level> [range] | center <x> <z> | radius <blocks>
 *   .texcrack tolerance <n>                            allow up to n misread cells (weighted)
 *   .texcrack facing <north|east|south|west|any>       lock the screenshot's facing
 *   .texcrack formula <auto|nextint|legacy>            variant picker of the SCREENSHOTTER's client
 *   .texcrack alloffsets <on|off>                      test all 16 combos (convention paranoia)
 *   .texcrack solve / cancel / status
 *   .texcrack read [size]                              print the grids under your feet (calibration)
 */
public final class TextureCrackCommand extends Command {

    // Shared solver state (the GUI writes the grid/weights here too).
    public static int[][] grid = null;
    public static float[][] weights = null;
    /** Block whose variant set the solver matches against (set by .texcrack block / image reads). */
    public static String blockName = "dirt";
    public static int obsY = 64;
    public static int yRange = 0;
    public static int centerX = 0, centerZ = 0;
    public static int radius = 3000;
    public static double tolerance = 0;
    public static int facingLock = -1;
    public static int formulaMode = TextureCrackEngine.FORMULA_NEXTINT;
    public static boolean allOffsets = false;
    /** Matches from the most recent completed solve (x,z pairs) - read by .crosscheck. */
    public static volatile java.util.List<long[]> lastMatches = java.util.List.of();
    /** Per-match mismatch cost, parallel to lastMatches (0 = exact) - read by .crosscheck. */
    public static volatile java.util.List<Double> lastCosts = java.util.List.of();

    public TextureCrackCommand() {
        super("texcrack", "Crack coordinates from block texture rotations.", "tc", "texturecrack");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            help();
            return SUCCESS;
        });

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("image")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("args", StringArgumentType.greedyString())
                .executes(ctx -> {
                    openImage(StringArgumentType.getString(ctx, "args"));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("grid")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("rows", StringArgumentType.greedyString())
                .executes(ctx -> {
                    parseGrid(StringArgumentType.getString(ctx, "rows"));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("block")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    setBlock(StringArgumentType.getString(ctx, "name"));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("y")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("level", IntegerArgumentType.integer(-64, 320))
                .executes(ctx -> {
                    obsY = IntegerArgumentType.getInteger(ctx, "level");
                    yRange = 0;
                    msg("Y = " + obsY + " (exact)");
                    return SUCCESS;
                })
                .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("range", IntegerArgumentType.integer(0, 128))
                    .executes(ctx -> {
                        obsY = IntegerArgumentType.getInteger(ctx, "level");
                        yRange = IntegerArgumentType.getInteger(ctx, "range");
                        msg("Y = " + obsY + " +-" + yRange);
                        return SUCCESS;
                    }))));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("center")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("x", IntegerArgumentType.integer())
                .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("z", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        centerX = IntegerArgumentType.getInteger(ctx, "x");
                        centerZ = IntegerArgumentType.getInteger(ctx, "z");
                        msg("Centre = " + centerX + ", " + centerZ);
                        return SUCCESS;
                    }))));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("radius")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("blocks", IntegerArgumentType.integer(16, 1000000))
                .executes(ctx -> {
                    radius = IntegerArgumentType.getInteger(ctx, "blocks");
                    msg("Radius = " + String.format("%,d", radius));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("tolerance")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("cells", IntegerArgumentType.integer(0, 8))
                .executes(ctx -> {
                    tolerance = IntegerArgumentType.getInteger(ctx, "cells");
                    msg("Tolerance = " + (int) tolerance + " misread cell(s)");
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("facing")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("dir", StringArgumentType.word())
                .executes(ctx -> {
                    String d = StringArgumentType.getString(ctx, "dir").toLowerCase(Locale.ROOT);
                    facingLock = switch (d) {
                        case "north", "n" -> 0;
                        case "east", "e" -> 1;
                        case "south", "s" -> 2;
                        case "west", "w" -> 3;
                        default -> -1;
                    };
                    msg("Facing = " + (facingLock < 0 ? "any (all 4 tried)" : d));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("formula")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("mode", StringArgumentType.word())
                .executes(ctx -> {
                    String m = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
                    formulaMode = switch (m) {
                        case "legacy", "old" -> TextureCrackEngine.FORMULA_LEGACY;
                        case "auto", "both" -> TextureCrackEngine.FORMULA_AUTO;
                        default -> TextureCrackEngine.FORMULA_NEXTINT;
                    };
                    msg("Formula = " + switch (formulaMode) {
                        case TextureCrackEngine.FORMULA_LEGACY -> "legacy";
                        case TextureCrackEngine.FORMULA_AUTO -> "auto (both)";
                        default -> "nextInt (modern clients)";
                    });
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("alloffsets")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("state", StringArgumentType.word())
                .executes(ctx -> {
                    allOffsets = StringArgumentType.getString(ctx, "state").toLowerCase(Locale.ROOT).startsWith("on");
                    msg("All offsets = " + (allOffsets ? "ON (16 combos, more FPs)" : "off (coupled pairs)"));
                    return SUCCESS;
                })));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("solve").executes(ctx -> {
            solve();
            return SUCCESS;
        }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("cancel").executes(ctx -> {
            TextureCrackEngine.cancel();
            msg("Cancelled.");
            return SUCCESS;
        }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("status").executes(ctx -> {
            int known = countKnown();
            msg(TextureCrackEngine.status
                + " | grid " + (grid == null ? "unset" : grid.length + " row(s), " + known + " known")
                + " | Y=" + obsY + (yRange > 0 ? "+-" + yRange : "")
                + " | centre " + centerX + "," + centerZ + " r=" + String.format("%,d", radius)
                + " | tol=" + (int) tolerance + " | facing=" + (facingLock < 0 ? "any" : "NESW".charAt(facingLock)));
            return SUCCESS;
        }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("read")
            .executes(ctx -> {
                readUnderPlayer(5);
                return SUCCESS;
            })
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("size", IntegerArgumentType.integer(2, 16))
                .executes(ctx -> {
                    readUnderPlayer(IntegerArgumentType.getInteger(ctx, "size"));
                    return SUCCESS;
                })));
    }

    private static void help() {
        msg("Texture crack - coordinates from screenshot texture rotations:");
        msg("§7.texcrack image latest dirt §f- corner-picker GUI on your newest screenshot (auto-read)");
        msg("§7.texcrack grid 0 2 1m / 3 . 0 §f- manual entry (rows split by /, . = unknown, m = mirrored)");
        msg("§7.texcrack block <name> §f- set + inspect the block's variant set (stone/bedrock have mirrors)");
        msg("§7.texcrack y <lvl> [rng] §f| §7center <x> <z> §f| §7radius <blocks> §f| §7tolerance <n> §f| §7facing <dir>");
        msg("§7.texcrack solve §f- search | §7.texcrack read [size] §f- calibration grid under your feet");
    }

    private static void setBlock(String name) {
        try {
            com.autism.seedcracker.texturecrack.BlockVariantSet vs =
                com.autism.seedcracker.texturecrack.BlockVariantSet.load(name);
            if (vs.degenerate()) {
                msg("§c" + name + " has only one visual variant - nothing to crack.");
                return;
            }
            blockName = name;
            msg("Block = " + vs + (vs.uniformWeights() ? "" : " §7(weighted)")
                + (vs.hasMirrors() ? " §7- mirrored variants: read cells as rot+m" : ""));
        } catch (Throwable t) {
            msg("§c" + t.getMessage());
        }
    }

    /** args = "<file|latest> <block> [RxC]" - opens the corner-picker GUI. */
    private static void openImage(String args) {
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            msg("§cUsage: .texcrack image <file|latest> <block> [rowsxcols]  e.g. .texcrack image latest dirt 5x5");
            return;
        }
        File file = resolveImage(parts[0]);
        if (file == null || !file.exists()) {
            msg("§cImage not found" + (parts[0].equalsIgnoreCase("latest") ? " - no screenshots yet?" : ": " + parts[0]));
            return;
        }
        String block = parts[1];
        int rows = 5, cols = 5;
        if (parts.length >= 3) {
            try {
                String[] rc = parts[2].toLowerCase(Locale.ROOT).split("x");
                rows = Integer.parseInt(rc[0]);
                cols = rc.length > 1 ? Integer.parseInt(rc[1]) : rows;
            } catch (Exception e) {
                msg("§cBad size '" + parts[2] + "' (want e.g. 5x5). Using 5x5.");
            }
        }
        rows = Math.max(2, Math.min(16, rows));
        cols = Math.max(2, Math.min(16, cols));

        Minecraft mc = Minecraft.getInstance();
        final File f = file;
        final int fr = rows, fc = cols;
        mc.execute(() -> {
            try {
                mc.gui.setScreen(new TextureCrackImageScreen(mc.gui.screen(), f, block, fr, fc));
            } catch (Throwable t) {
                msg("§cFailed to open image: " + t.getMessage());
            }
        });
    }

    private static File resolveImage(String name) {
        Minecraft mc = Minecraft.getInstance();
        File shots = new File(mc.gameDirectory, "screenshots");
        if (name.equalsIgnoreCase("latest")) {
            File[] all = shots.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
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

    private static void parseGrid(String raw) {
        try {
            String[] rows = raw.trim().split("/");
            int[][] g = new int[rows.length][];
            int known = 0;
            for (int r = 0; r < rows.length; r++) {
                String[] toks = rows[r].trim().split("[\\s,]+");
                g[r] = new int[toks.length];
                for (int c = 0; c < toks.length; c++) {
                    String t = toks[c].trim().toLowerCase(Locale.ROOT);
                    if (t.equals(".") || t.equals("?") || t.equals("x")) {
                        g[r][c] = -1;
                    } else {
                        // "m" suffix marks a mirrored variant (stone/bedrock-style blocks).
                        boolean mirrored = t.endsWith("m");
                        if (mirrored) t = t.substring(0, t.length() - 1);
                        int v = Integer.parseInt(t);
                        // Accept degrees (0/90/180/270) or quarter turns (0-3).
                        g[r][c] = ((v >= 90 ? v / 90 : v) & 3)
                            | (mirrored ? com.autism.seedcracker.texturecrack.BlockVariantSet.T_MIRROR : 0);
                        known++;
                    }
                }
            }
            grid = g;
            weights = null; // manual entry: uniform weights
            msg("Grid set: " + g.length + " row(s), " + known + " known cell(s)."
                + (known < 16 ? " §e16+ recommended for a unique match." : ""));
        } catch (Throwable t) {
            msg("§cBad grid. Example: .texcrack grid 0 2 1 3 / 1 . 0 2 / 3 3 . 1");
        }
    }

    private static int countKnown() {
        if (grid == null) return 0;
        int n = 0;
        for (int[] row : grid) for (int v : row) if (v >= 0) n++;
        return n;
    }

    public static void solve() {
        if (grid == null) { msg("§cSet a grid first (.texcrack grid ... or .texcrack image ...)."); return; }
        if (TextureCrackEngine.searching) { msg("§cAlready searching (.texcrack cancel to stop)."); return; }
        int known = countKnown();
        if (known - 2 * (int) tolerance < 16) {
            msg("§eWeak evidence: " + known + " known cell(s) at tolerance " + (int) tolerance
                + " - expect false positives. 16+ effective cells recommended.");
        }
        msg("Searching r=" + String.format("%,d", radius) + " around " + centerX + "," + centerZ
            + " Y=" + obsY + (yRange > 0 ? "+-" + yRange : "")
            + " tol=" + (int) tolerance + (facingLock >= 0 ? " facing=" + "NESW".charAt(facingLock) : "") + "...");
        Minecraft mc = Minecraft.getInstance();
        com.autism.seedcracker.texturecrack.BlockVariantSet vs = null;
        try {
            vs = com.autism.seedcracker.texturecrack.BlockVariantSet.load(blockName);
        } catch (Throwable t) {
            msg("§e" + blockName + ": " + t.getMessage() + " - assuming plain 4-rotation.");
        }
        java.util.List<long[]> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<Double> costs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        TextureCrackEngine.solve(grid, weights, vs, obsY, yRange, centerX, centerZ, radius,
            formulaMode, facingLock, allOffsets, tolerance, 20,
            m -> {
                collected.add(new long[]{m.x(), m.z()});
                costs.add(m.cost());
                mc.execute(() -> msg("§aMATCH §f" + m.x() + " " + m.y() + " " + m.z()
                    + " §7(" + (m.cost() <= 0 ? "exact" : String.format("cost %.2f", m.cost()))
                    + ", grid facing " + switch (m.orientation()) {
                        case 1 -> "east"; case 2 -> "south"; case 3 -> "west"; default -> "north"; }
                    + ", " + m.formula() + ")"));
            },
            () -> {
                lastMatches = java.util.List.copyOf(collected);
                lastCosts = java.util.List.copyOf(costs);
                mc.execute(() -> msg(TextureCrackEngine.status));
            });
    }

    /** Calibration helper: print the computed rotation grids for the blocks under the player. */
    private static void readUnderPlayer(int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        net.minecraft.core.BlockPos base = mc.player.blockPosition().below();
        StringBuilder ni = new StringBuilder();
        StringBuilder lg = new StringBuilder();
        for (int dz = 0; dz < size; dz++) {
            if (dz > 0) { ni.append(" / "); lg.append(" / "); }
            for (int dx = 0; dx < size; dx++) {
                long seed = TextureCrackEngine.posSeed(base.getX() + dx, base.getY(), base.getZ() + dz);
                ni.append(TextureCrackEngine.idxNextInt(seed, 4));
                lg.append(TextureCrackEngine.idxLegacy(seed, 4));
                if (dx < size - 1) { ni.append(' '); lg.append(' '); }
            }
        }
        msg("Computed grids " + size + "x" + size + " from " + base.getX() + "," + base.getY() + "," + base.getZ()
            + " (rows = +Z, cols = +X):");
        msg("§7nextInt: §f" + ni);
        msg("§7legacy:  §f" + lg);
        msg("§7Compare against what you SEE on dirt/netherrack tops to calibrate the formula.");
    }

    private static void msg(String s) {
        AutismClientMessaging.sendPrefixed(s);
    }
}
