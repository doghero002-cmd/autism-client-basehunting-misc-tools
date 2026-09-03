package com.autism.seedcracker.baselog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import autismclient.AutismClientAddon;
import net.minecraft.client.Minecraft;

/**
 * Reads / writes the shared {@code bases.txt} file that the RTP stash finder and the
 * Relog Loader append base coordinates to. Provides parsed entries for the Base Log
 * Browser GUI and helpers to delete entries and rewrite the file.
 */
public final class BaseLog {
    private BaseLog() {}

    /** One logged base. */
    public static final class Entry {
        public final int x;
        public final int y;
        public final int z;
        public final String dimension;   // e.g. minecraft:overworld (or "unknown")
        public final String blockId;     // e.g. minecraft:chest (or "" when not recorded)
        public final String timestamp;   // raw timestamp string (or "")
        public final String raw;         // original line

        Entry(int x, int y, int z, String dimension, String blockId, String timestamp, String raw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockId = blockId;
            this.timestamp = timestamp;
            this.raw = raw;
        }

        /** Distance (blocks, 2D) from the given position. */
        public double distanceTo(double px, double pz) {
            double dx = x - px;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public String coord() {
            return x + " " + y + " " + z;
        }
    }

    public static Path file() {
        return AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
    }

    /** Parse every line in bases.txt. Malformed lines are skipped. */
    public static List<Entry> readAll() {
        List<Entry> out = new ArrayList<>();
        Path f = file();
        if (!Files.exists(f)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        for (String line : lines) {
            Entry e = parse(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    // Format A (RTP finder):   "x y z  dimension  timestamp"
    // Format B (relog loader): "x y z  dimension  blockId  timestamp"
    // Fields are separated by runs of whitespace; coords are the first 3 tokens.
    private static Entry parse(String line) {
        if (line == null) return null;
        String raw = line;
        line = line.trim();
        if (line.isEmpty()) return null;
        String[] tok = line.split("\\s+");
        if (tok.length < 3) return null;
        int x, y, z;
        try {
            x = Integer.parseInt(tok[0]);
            y = Integer.parseInt(tok[1]);
            z = Integer.parseInt(tok[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String dim = tok.length >= 4 ? tok[3] : "unknown";
        String block = "";
        String ts = "";
        if (tok.length == 5) {
            // Either blockId or timestamp. A timestamp contains ':' or '-'.
            if (looksLikeTimestamp(tok[4])) ts = tok[4];
            else block = tok[4];
        } else if (tok.length >= 6) {
            block = tok[4];
            StringBuilder sb = new StringBuilder();
            for (int i = 5; i < tok.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(tok[i]);
            }
            ts = sb.toString();
        }
        return new Entry(x, y, z, dim, block, ts, raw);
    }

    private static boolean looksLikeTimestamp(String s) {
        return s.indexOf(':') >= 0 || s.indexOf('-') >= 0;
    }

    /** Rewrite bases.txt without the given entries (matched by raw line). Returns count removed. */
    public static int remove(List<Entry> toRemove) {
        if (toRemove == null || toRemove.isEmpty()) return 0;
        java.util.Set<String> raws = new java.util.HashSet<>();
        for (Entry e : toRemove) raws.add(e.raw.trim());
        Path f = file();
        if (!Files.exists(f)) return 0;
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return 0;
        }
        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String line : lines) {
            if (raws.contains(line.trim())) removed++;
            else keep.add(line);
        }
        try {
            Files.write(f, keep, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            return 0;
        }
        return removed;
    }

    /** Current player position, or null if not in a world. */
    public static double[] playerPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return null;
        return new double[] { mc.player.getX(), mc.player.getZ() };
    }

    /** Nicely shortened dimension label (minecraft:overworld -> overworld). */
    public static String shortDim(String dim) {
        if (dim == null) return "unknown";
        int i = dim.indexOf(':');
        String s = i >= 0 ? dim.substring(i + 1) : dim;
        return s.isEmpty() ? "unknown" : s;
    }

    /** Nicely shortened block label (minecraft:shulker_box -> shulker_box). */
    public static String shortBlock(String id) {
        if (id == null) return "";
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    public static String formatDistance(double d) {
        if (d >= 1000.0) return String.format(Locale.ROOT, "%.2f km", d / 1000.0);
        return String.format(Locale.ROOT, "%.0f m", d);
    }
}
