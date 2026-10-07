package gpucrack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An observed texture-rotation grid: rows (+Z) x cols (+X) of rotation values 0-3 or -1
 * (unknown). This is the seedless counterpart to {@link PatternFile} - texture variants are a
 * pure function of block position, so cracking them needs no world seed.
 *
 * File format (one row per line, '.' = unknown):
 *   0123
 *   1..0
 *   33.1
 * Rows run +Z downward, columns +X rightward. Optionally a first line "y <level>" sets the
 * block Y level; the search Y can also be passed on the command line / GUI.
 */
public record RotationPattern(int cols, int rows, List<Cell> cells, int[][] grid) {

    public record Cell(int dx, int dz, int rotation) {}

    public int knownCells() {
        return cells.size();
    }

    public static RotationPattern parse(List<String> lines) {
        List<String> rows = new ArrayList<>();
        int cols = -1;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("y ")) continue; // Y level handled by the caller
            rows.add(t);
            if (cols == -1) cols = t.length();
            else if (t.length() != cols) throw new IllegalArgumentException("ragged rows in rotation grid");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("no rows in rotation grid");
        int r = rows.size();
        int c = cols;
        if (c > 16 || r > 16) throw new IllegalArgumentException("rotation grid exceeds 16x16");
        int[][] grid = new int[c][r]; // [col][row]
        List<Cell> cells = new ArrayList<>();
        for (int row = 0; row < r; row++) {
            String line = rows.get(row);
            for (int col = 0; col < c; col++) {
                char ch = line.charAt(col);
                if (ch == '.' || ch == '?' || ch == 'x' || ch == 'X') {
                    grid[col][row] = -1;
                    continue;
                }
                if (ch < '0' || ch > '3') throw new IllegalArgumentException("bad rotation '" + ch + "' (want 0-3 or .)");
                int rot = ch - '0';
                grid[col][row] = rot;
                cells.add(new Cell(col, row, rot));
            }
        }
        if (cells.isEmpty()) throw new IllegalArgumentException("rotation grid has no known cells");
        return new RotationPattern(c, r, List.copyOf(cells), grid);
    }

    public static RotationPattern load(Path path) throws IOException {
        return parse(Files.readAllLines(path));
    }

    public List<String> toLines() {
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                int v = grid[c][r];
                sb.append(v < 0 ? '.' : (char) ('0' + v));
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
