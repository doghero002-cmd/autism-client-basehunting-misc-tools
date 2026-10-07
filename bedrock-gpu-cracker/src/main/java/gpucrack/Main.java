package gpucrack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entry point: no arguments launches the GUI; any argument switches to headless CLI mode.
 *
 * CLI (bedrock):   --seed <long> --pattern <file> [--center <x> <z>] [--radius <n> | --full-world --yes]
 * CLI (rotation):  --mode rotation --pattern <rotfile> --y <n> [--legacy] [--center|--full-world --yes]
 *                  [--device <n>] [--tile <size>] [--list-devices] [--out <file>]
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            // Default GUI: the Rotation Reader (screenshot -> auto-grid -> crack). The bedrock
            // painter GUI is still available via --gui bedrock.
            textureui.RotationReaderApp.launch();
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("--gui")) {
            CrackerGui.launch();
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("--gui")) {
            if (args[1].equalsIgnoreCase("bedrock")) { CrackerGui.launch(); return; }
            if (args[1].equalsIgnoreCase("rotation")) { textureui.RotationReaderApp.launch(); return; }
        }
        try {
            runCli(args);
        } catch (CliError e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("error: " + e);
            System.exit(1);
        }
    }

    private static final class CliError extends RuntimeException {
        CliError(String msg) {
            super(msg);
        }
    }

    private static void runCli(String[] args) throws Exception {
        String mode = "bedrock";
        Long seed = null;
        Path patternPath = null;
        Integer rotY = null;
        boolean legacy = false;
        int centerX = 0, centerZ = 0;
        long radius = 100_000;
        boolean fullWorld = false, yes = false, listOnly = false;
        int deviceIndex = -1;
        int tile = 8192;
        Path out = Path.of("matches.txt");

        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--mode" -> mode = need(args, ++i, "--mode").toLowerCase(Locale.ROOT);
                case "--seed" -> seed = Long.parseLong(need(args, ++i, "--seed"));
                case "--pattern" -> patternPath = Path.of(need(args, ++i, "--pattern"));
                case "--y" -> rotY = Integer.parseInt(need(args, ++i, "--y"));
                case "--legacy" -> legacy = true;
                case "--center" -> {
                    centerX = Integer.parseInt(need(args, ++i, "--center x"));
                    centerZ = Integer.parseInt(need(args, ++i, "--center z"));
                }
                case "--radius" -> radius = Long.parseLong(need(args, ++i, "--radius"));
                case "--full-world" -> fullWorld = true;
                case "--yes" -> yes = true;
                case "--device" -> deviceIndex = Integer.parseInt(need(args, ++i, "--device"));
                case "--tile" -> tile = Integer.parseInt(need(args, ++i, "--tile"));
                case "--out" -> out = Path.of(need(args, ++i, "--out"));
                case "--list-devices" -> listOnly = true;
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                default -> throw new CliError("unknown argument " + args[i] + " (see --help)");
            }
        }

        List<GpuSearch.Device> devices = GpuSearch.listDevices();
        if (listOnly) {
            if (devices.isEmpty()) {
                System.out.println("No OpenCL devices found. Install your GPU vendor's driver.");
            } else {
                for (GpuSearch.Device d : devices) {
                    System.out.printf("[%d] %s - %s%n", d.index(), d.vendor(), d);
                }
            }
            return;
        }

        if (seed == null) throw new CliError("--seed is required");
        if (patternPath == null) throw new CliError("--pattern is required");
        if (!Files.exists(patternPath)) throw new CliError("pattern file not found: " + patternPath);
        if (devices.isEmpty()) throw new CliError("no OpenCL devices found - install your GPU vendor's driver");
        if (fullWorld) {
            radius = 30_000_000L;
            centerX = 0;
            centerZ = 0;
            if (!yes) throw new CliError("--full-world covers ~3.6e15 positions (hours of GPU time); add --yes to confirm");
        }
        // World border is +-30M; beyond ~2.1B the int tile coordinates would overflow silently.
        if (radius > 30_000_000L) {
            radius = 30_000_000L;
            System.out.println("radius clamped to 30,000,000 (world border)");
        }
        if (radius < 1) throw new CliError("--radius must be positive");

        final int wantDevice = deviceIndex;
        GpuSearch.Device device = wantDevice >= 0
            ? devices.stream().filter(d -> d.index() == wantDevice).findFirst()
                .orElseThrow(() -> new CliError("no device with index " + wantDevice))
            : devices.get(0);

        if (mode.equals("rotation") || mode.equals("texture") || mode.equals("texcrack")) {
            runRotation(device, patternPath, rotY, legacy, centerX, centerZ, radius, tile, out);
            return;
        }
        if (!mode.equals("bedrock")) throw new CliError("unknown --mode '" + mode + "' (bedrock|rotation)");

        PatternFile pattern = PatternFile.load(patternPath);
        System.out.printf("Pattern: %d layer(s), %d marked cell(s), %s%n",
            pattern.layers().size(), pattern.markedCells(), pattern.roof() ? "nether roof" : "overworld floor");
        System.out.printf("Device:  %s%n", device);
        System.out.printf("Search:  centre %d,%d radius %,d%n", centerX, centerZ, radius);

        List<String> outLines = new ArrayList<>();
        final int cx = centerX, cz = centerZ;
        try (GpuSearch search = new GpuSearch(device)) {
            List<GpuSearch.Match> matches = search.search(pattern, seed, centerX, centerZ, radius, tile,
                new GpuSearch.Progress() {
                    private long lastPrint = 0;

                    @Override
                    public boolean onProgress(long done, long total, int matchCount, double rate) {
                        long now = System.currentTimeMillis();
                        if (now - lastPrint > 2000) {
                            lastPrint = now;
                            long etaSec = rate > 1 ? (long) ((total - done) / rate) : -1;
                            System.out.printf("%.1f%%  %,.0f anchors/s  %d match(es)%s%n",
                                done * 100.0 / total, rate, matchCount,
                                etaSec >= 0 ? String.format("  ETA %d:%02d:%02d", etaSec / 3600, etaSec % 3600 / 60, etaSec % 60) : "");
                        }
                        return true;
                    }

                    @Override
                    public void onMatch(GpuSearch.Match m) {
                        long dist = Math.round(Math.hypot((double) m.x() - cx, (double) m.z() - cz));
                        System.out.printf("MATCH  x=%d z=%d rot=%d\u00b0 dist=%,d%n", m.x(), m.z(), m.rotation() * 90, dist);
                        outLines.add(m.x() + ", " + m.z() + ", rot" + m.rotation() * 90);
                    }
                });
            if (matches != null) {
                System.out.println("Done: " + matches.size() + " verified match(es).");
                if (!outLines.isEmpty()) {
                    Files.write(out, outLines);
                    System.out.println("Written to " + out);
                }
            }
        }
    }

    /** Seedless texture-rotation full-world cracker. */
    private static void runRotation(GpuSearch.Device device, Path patternPath, Integer y, boolean legacy,
                                    int centerX, int centerZ, long radius, int tile, Path out) throws Exception {
        if (patternPath == null) throw new CliError("--pattern is required");
        if (!Files.exists(patternPath)) throw new CliError("pattern file not found: " + patternPath);
        if (y == null) throw new CliError("--y <block level> is required for rotation mode");
        RotationPattern pattern = RotationPattern.load(patternPath);
        System.out.printf("Rotation grid: %dx%d, %d known cell(s), Y=%d, formula=%s%n",
            pattern.cols(), pattern.rows(), pattern.knownCells(), y, legacy ? "legacy" : "nextInt");
        System.out.printf("Device:  %s%n", device);
        System.out.printf("Search:  centre %d,%d radius %,d%n", centerX, centerZ, radius);

        List<String> outLines = new ArrayList<>();
        final int cx = centerX, cz = centerZ;
        try (RotationGpuSearch search = new RotationGpuSearch(device)) {
            List<RotationGpuSearch.Match> matches = search.search(pattern, y, legacy, centerX, centerZ, radius, tile,
                new RotationGpuSearch.Progress() {
                    private long lastPrint = 0;

                    @Override
                    public boolean onProgress(long done, long total, int matchCount, double rate) {
                        long now = System.currentTimeMillis();
                        if (now - lastPrint > 2000) {
                            lastPrint = now;
                            long etaSec = rate > 1 ? (long) ((total - done) / rate) : -1;
                            System.out.printf("%.1f%%  %,.0f anchors/s  %d match(es)%s%n",
                                done * 100.0 / total, rate, matchCount,
                                etaSec >= 0 ? String.format("  ETA %d:%02d:%02d", etaSec / 3600, etaSec % 3600 / 60, etaSec % 60) : "");
                        }
                        return true;
                    }

                    @Override
                    public void onMatch(RotationGpuSearch.Match m) {
                        long dist = Math.round(Math.hypot((double) m.x() - cx, (double) m.z() - cz));
                        System.out.printf("MATCH  x=%d z=%d rot=%d\u00b0 dist=%,d%n", m.x(), m.z(), m.rotation() * 90, dist);
                        outLines.add(m.x() + ", " + m.z() + ", rot" + m.rotation() * 90);
                    }
                });
            if (matches != null) {
                System.out.println("Done: " + matches.size() + " verified match(es).");
                if (!outLines.isEmpty()) {
                    Files.write(out, outLines);
                    System.out.println("Written to " + out);
                }
            }
        }
    }

    private static String need(String[] args, int i, String what) {
        if (i >= args.length) throw new CliError(what + " needs a value");
        return args[i];
    }

    private static void printHelp() {
        System.out.println("""
            Bedrock GPU Cracker - find world coordinates from a bedrock pattern.

            GUI:   java -jar bedrock-gpu-cracker.jar
            CLI:   java -jar bedrock-gpu-cracker.jar --seed <long> --pattern <file> [options]

            Options:
              --mode <m>          'bedrock' (default) or 'rotation' (texture-rotation, seedless)
              --seed <long>       world seed (bedrock mode, required)
              --pattern <file>    bedrock: optional 'roof' line + 'layer <y>' sections of . / B / N.
                                  rotation: rows of . (unknown) and 0-3 (texture rotation)
              --y <n>             block Y level (rotation mode, required)
              --legacy            use the legacy variant formula (rotation mode; default nextInt)
              --center <x> <z>    search centre (default 0 0)
              --radius <blocks>   search radius (default 100000)
              --full-world        radius 30,000,000 (hours of GPU time; requires --yes)
              --device <n>        OpenCL device index (see --list-devices)
              --tile <size>       tile edge length in anchors (default 8192)
              --out <file>        matches output file (default matches.txt)
              --list-devices      print available OpenCL devices and exit

            Examples:
              Full-world bedrock:   java -jar bedrock-gpu-cracker.jar --seed 12345 --pattern base.txt --full-world --yes
              Full-world rotation:  java -jar bedrock-gpu-cracker.jar --mode rotation --pattern rot.txt --y 64 --full-world --yes""");
    }
}
