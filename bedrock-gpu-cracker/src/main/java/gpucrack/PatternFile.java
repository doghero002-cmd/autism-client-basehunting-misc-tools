package gpucrack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pattern text format shared with the mod's "Export GPU" button:
 * optional "roof" line, then one or more "layer <y>" sections each followed by grid rows of
 * '.' (unknown) / 'B' (bedrock) / 'N' (not bedrock). All layers must share dimensions.
 * Rows run +Z downward, columns +X rightward (rotation handled at search time).
 */
public record PatternFile(boolean roof, int cols, int rows, List<Layer> layers) {

    public record Cell(int dx, int dz, boolean bedrock) {}

    public record Layer(int y, List<Cell> cells, int[][] grid) {}

    public int markedCells() {
        int n = 0;
        for (Layer l : layers) n += l.cells().size();
        return n;
    }

    public static PatternFile parse(List<String> lines) {
        boolean roof = false;
        List<Layer> layers = new ArrayList<>();
        Integer curY = null;
        List<String> curRows = new ArrayList<>();
        int cols = -1;

        List<String> meaningful = new ArrayList<>();
        for (String raw : lines) {
            String t = raw.trim();
            if (!t.isEmpty() && !t.startsWith("#")) meaningful.add(t);
        }
        meaningful.add("layer end"); // sentinel flushes the last section

        for (String line : meaningful) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("roof")) {
                roof = true;
            } else if (lower.startsWith("layer")) {
                if (curY != null) {
                    Layer layer = buildLayer(curY, curRows);
                    if (cols == -1) cols = layer.grid().length == 0 ? 0 : curRows.get(0).length();
                    if (!curRows.isEmpty() && curRows.get(0).length() != cols) {
                        throw new IllegalArgumentException("all layers must share the same dimensions");
                    }
                    layers.add(layer);
                    curRows = new ArrayList<>();
                }
                if (!lower.equals("layer end")) {
                    curY = Integer.parseInt(line.substring(5).trim());
                }
            } else {
                if (curY == null) throw new IllegalArgumentException("grid row before any 'layer <y>' header");
                curRows.add(line);
            }
        }

        if (layers.isEmpty()) throw new IllegalArgumentException("no layers in pattern file");
        int rows = layers.get(0).grid().length == 0 ? 0 : layers.get(0).grid()[0].length;
        for (Layer l : layers) {
            if (l.grid().length != cols || (l.grid().length > 0 && l.grid()[0].length != rows)) {
                throw new IllegalArgumentException("all layers must share the same dimensions");
            }
        }
        if (cols > 16 || rows > 16) throw new IllegalArgumentException("pattern exceeds 16x16");
        int marked = 0;
        for (Layer l : layers) marked += l.cells().size();
        if (marked == 0) throw new IllegalArgumentException("pattern has no marked cells");
        return new PatternFile(roof, cols, rows, List.copyOf(layers));
    }

    private static Layer buildLayer(int y, List<String> rowLines) {
        if (rowLines.isEmpty()) throw new IllegalArgumentException("layer " + y + " has no rows");
        int width = rowLines.get(0).length();
        int height = rowLines.size();
        int[][] grid = new int[width][height]; // [col][row]
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            String row = rowLines.get(r);
            if (row.length() != width) throw new IllegalArgumentException("ragged rows in layer " + y);
            for (int c = 0; c < width; c++) {
                char ch = Character.toUpperCase(row.charAt(c));
                switch (ch) {
                    case 'B' -> { grid[c][r] = 1; cells.add(new Cell(c, r, true)); }
                    case 'N' -> { grid[c][r] = 2; cells.add(new Cell(c, r, false)); }
                    case '.' -> grid[c][r] = 0;
                    default -> throw new IllegalArgumentException("bad cell '" + ch + "' in layer " + y);
                }
            }
        }
        return new Layer(y, List.copyOf(cells), grid);
    }

    public static PatternFile load(Path path) throws IOException {
        return parse(Files.readAllLines(path));
    }

    public List<String> toLines() {
        List<String> lines = new ArrayList<>();
        if (roof) lines.add("roof");
        for (Layer l : layers) {
            lines.add("layer " + l.y());
            for (int r = 0; r < rows; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    int v = l.grid()[c][r];
                    sb.append(v == 1 ? 'B' : v == 2 ? 'N' : '.');
                }
                lines.add(sb.toString());
            }
        }
        return lines;
    }

    public void save(Path path) throws IOException {
        Files.write(path, toLines());
    }
}
